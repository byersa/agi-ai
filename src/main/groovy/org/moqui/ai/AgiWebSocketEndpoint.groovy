package org.moqui.ai

import groovy.transform.CompileStatic
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import org.moqui.impl.webapp.MoquiAbstractEndpoint
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import jakarta.websocket.CloseReason
import jakarta.websocket.EndpointConfig
import jakarta.websocket.Session
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID
import org.moqui.adk.AdkManager

@CompileStatic
class AgiWebSocketEndpoint extends MoquiAbstractEndpoint {
    private final static Logger logger = LoggerFactory.getLogger(AgiWebSocketEndpoint.class)

    // Store active WebSocket connections by channel
    private static final Map<String, Set<Session>> channels = new ConcurrentHashMap<>()

    // Store active human-in-the-loop approval contexts
    private static final Map<String, ApprovalContext> activeApprovals = new ConcurrentHashMap<>()

    AgiWebSocketEndpoint() { super() }

    static Map<String, Set<Session>> getChannels() { return channels }
    static Map<String, ApprovalContext> getActiveApprovals() { return activeApprovals }

    @Override
    void onOpen(Session session, EndpointConfig config) {
        super.onOpen(session, config)
        
        // Extract the channel from path parameter
        String channel = session.getPathParameters().get("channel") ?: "default"
        
        // Parse token and channel from query string (fallback check)
        String token = null
        String query = session.getQueryString()
        if (query) {
            for (String param : query.split("&")) {
                def parts = param.split("=")
                if (parts.length == 2) {
                    if (parts[0] == "token") {
                        token = parts[1]
                    } else if (parts[0] == "channel") {
                        channel = parts[1]
                    }
                }
            }
        }

        logger.info("🟢 [AGI-AI WS] Client connecting to channel: ${channel}")

        // Authorize connection natively using our in-memory/env token validation
        String expectedToken = getAuthToken(getEcf())
        if (token != expectedToken) {
            logger.warn("❌ [AGI-AI WS] Unauthorized connection attempt to channel ${channel} with token: ${token}")
            try {
                session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "Unauthorized - Invalid token"))
            } catch (Exception e) {
                logger.error("Error closing unauthorized session", e)
            }
            return
        }

        // Register session in the channel
        Set<Session> set = channels.get(channel)
        if (set == null) {
            set = ConcurrentHashMap.newKeySet()
            channels.put(channel, set)
        }
        set.add(session)

        // Send welcome message
        Map welcome = [
            type: "welcome",
            channel: "/" + channel,
            message: "Connected to path: /${channel}"
        ]
        session.getBasicRemote().sendText(new JsonBuilder(welcome).toString())
        logger.info("🟢 [AGI-AI WS] Client successfully registered and welcomed on channel: ${channel}")
    }

    @Override
    void onMessage(String message) {
        if (!message) return
        
        String channel = session.getPathParameters().get("channel") ?: "default"
        logger.info("📥 [AGI-AI WS] Received message on channel ${channel}: ${message}")

        def slurper = new JsonSlurper()
        def payload = null
        try {
            payload = slurper.parseText(message)
        } catch (Exception e) {
            logger.error("Failed to parse WebSocket message", e)
            return
        }

        if (payload instanceof Map) {
            String type = payload.type
            if (type == "ping") {
                Map pong = [
                    id: payload.id,
                    type: "pong",
                    timestamp: System.currentTimeMillis()
                ]
                session.getBasicRemote().sendText(new JsonBuilder(pong).toString())
            } else if (type == "approvalResponse") {
                String token = (String) payload.token
                boolean approved = (boolean) (payload.approved ?: false)
                String rejectReason = (String) (payload.rejectReason ?: "Rejected by user")

                logger.info("🛡️ [HITL SAFEGUARD] Received approval response for token: ${token}, approved: ${approved}")

                ApprovalContext approval = activeApprovals.get(token)
                if (approval) {
                    approval.approved = approved
                    approval.rejectReason = rejectReason
                    approval.latch.countDown() // Release the blocked tool execution thread!
                    
                    // Reply down the socket to acknowledge receipt
                    Map ack = [
                        type: "notification",
                        componentId: (String) (payload.componentId ?: channel),
                        text: "Transaction ${approved ? 'approved' : 'rejected'}. Releasing agent..."
                    ]
                    session.getBasicRemote().sendText(new JsonBuilder(ack).toString())
                } else {
                    logger.warn("⚠️ [HITL SAFEGUARD] No active approval request found matching token: ${token}")
                }
            } else if (type == "userMessage") {
                String text = payload.text
                String componentId = payload.componentId ?: channel

                logger.info("🧠 [AGI-AI WS] Processing userMessage for component: ${componentId}")

                // Run the ADK Agent loop in an asynchronous worker thread to avoid blocking WebSocket threads
                def ecf = getEcf()
                Thread.start {
                    // Initialize Thread-Isolated ExecutionContext
                    def ec = ecf.getExecutionContext()
                    try {
                        String userId = "anonymous"
                        String sid = (String) (payload.sessionId ?: channel)

                        // Bind active session and parameters to ExecutionContext to enable downstream tool interception
                        ec.context.put("webSocketSession", session)
                        ec.context.put("activeComponentId", componentId)
                        ec.context.put("activeChannel", channel)

                        // Lazy init the ADK engine if needed
                        AdkManager.lazyInit(ecf)

                        // Accumulation buffer for final text evaluation (commands checking)
                        StringBuilder responseBuffer = new StringBuilder()

                        // Drive prompt asynchronously using official Google ADK dynamic runner
                        AdkManager.runAgentSse(userId, sid, text,
                            { Map event ->
                                Map content = (Map) event.content
                                if (content) {
                                    List parts = (List) content.parts
                                    if (parts) {
                                        for (Object partObj : parts) {
                                            Map part = (Map) partObj
                                            String chunkText = (String) part.text
                                            if (chunkText) {
                                                responseBuffer.append(chunkText)
                                                
                                                // Stream text token instantly for incremental chat UI rendering
                                                Map tokenPayload = [
                                                    type: "textToken",
                                                    componentId: componentId,
                                                    text: chunkText,
                                                    partial: event.containsKey("partial") ? event.partial : true
                                                ]
                                                session.getBasicRemote().sendText(new JsonBuilder(tokenPayload).toString())
                                            }
                                        }
                                    }
                                }
                            },
                            { Throwable err ->
                                if (err) {
                                    logger.error("❌ [AGI-AI WS] Error executing ADK Agent prompt", err)
                                    try {
                                        Map errorPayload = [
                                            type: "error",
                                            componentId: componentId,
                                            message: "Error processing ADK prompt: " + err.getMessage()
                                        ]
                                        session.getBasicRemote().sendText(new JsonBuilder(errorPayload).toString())
                                    } catch (Exception ex) {}
                                } else {
                                    // Successfully completed - check if full accumulated text represents a JSON command
                                    String fullResponse = responseBuffer.toString().trim()
                                    if (fullResponse.startsWith("{") && fullResponse.endsWith("}")) {
                                        try {
                                            def commandData = slurper.parseText(fullResponse)
                                            Map commandPayload = [
                                                type: "command",
                                                componentId: componentId,
                                                data: commandData
                                            ]
                                            session.getBasicRemote().sendText(new JsonBuilder(commandPayload).toString())
                                            logger.info("🎯 [AGI-AI WS] Dispatched parsed JSON visual command back to client on channel ${channel}")
                                        } catch (Exception ex) {
                                            logger.warn("⚠️ Failed to parse accumulated text as JSON: ${ex.message}")
                                        }
                                    }
                                }
                            }
                        )
                    } catch (Exception e) {
                        logger.error("❌ [AGI-AI WS] Error processing user message via ADK", e)
                        try {
                            Map errorPayload = [
                                type: "error",
                                componentId: componentId,
                                message: "Error processing prompt: " + e.getMessage()
                            ]
                            session.getBasicRemote().sendText(new JsonBuilder(errorPayload).toString())
                        } catch (Exception ex) {}
                    } finally {
                        // Crucial: Clean up active thread context to prevent database / memory leaks
                        ecf.destroyActiveExecutionContext()
                    }
                }
            }
        }
    }

    @Override
    void onClose(Session session, CloseReason closeReason) {
        String channel = session.getPathParameters().get("channel") ?: "default"
        if (channels.containsKey(channel)) {
            channels.get(channel).remove(session)
            if (channels.get(channel).isEmpty()) {
                channels.remove(channel)
            }
        }
        logger.info("🛑 [AGI-AI WS] Client disconnected from channel: ${channel}. Reason: ${closeReason.getReasonPhrase()}")
        super.onClose(session, closeReason)
    }

    /**
     * Blocks the active agent execution thread, dispatches an approval request payload
     * down the WebSocket connection, and suspends execution until the user responds or times out.
     */
    static Map requestUserApproval(String toolName, Map arguments) {
        org.moqui.context.ExecutionContext ec = org.moqui.Moqui.getExecutionContext()
        if (!ec) {
            logger.warn("⚠️ [HITL SAFEGUARD] No ExecutionContext found for approval of tool: ${toolName}. Proceeding without approval.")
            return [approved: true]
        }

        Session session = (Session) ec.context.get("webSocketSession")
        if (!session || !session.isOpen()) {
            logger.warn("⚠️ [HITL SAFEGUARD] No active open WebSocket session found in context for approval of tool: ${toolName}. Proceeding without approval.")
            return [approved: true]
        }

        String componentId = (String) ec.context.get("activeComponentId")
        String token = UUID.randomUUID().toString()
        logger.info("🛡️ [HITL SAFEGUARD] Requesting human approval for tool '${toolName}'. Token: ${token}")

        ApprovalContext approval = new ApprovalContext(token, toolName, arguments)
        activeApprovals.put(token, approval)

        try {
            Map requestPayload = [
                type: "approvalRequest",
                token: token,
                componentId: componentId,
                toolName: toolName,
                arguments: arguments
            ]
            session.getBasicRemote().sendText(new JsonBuilder(requestPayload).toString())

            // Block active execution thread for up to 5 minutes waiting for user input
            boolean completed = approval.latch.await(5, TimeUnit.MINUTES)
            if (!completed) {
                logger.warn("⏳ [HITL SAFEGUARD] Tool approval request timed out. Token: ${token}")
                activeApprovals.remove(token)
                return [approved: false, error: "Transaction timed out (Human-in-the-loop protection)"]
            }

            activeApprovals.remove(token)
            if (approval.approved) {
                logger.info("✅ [HITL SAFEGUARD] Tool approval GRANTED. Token: ${token}")
                return [approved: true]
            } else {
                logger.warn("❌ [HITL SAFEGUARD] Tool approval DENIED. Token: ${token}. Reason: ${approval.rejectReason}")
                return [approved: false, error: approval.rejectReason ?: "Transaction rejected by user (Human-in-the-loop protection)"]
            }
        } catch (Exception e) {
            logger.error("❌ [HITL SAFEGUARD] Error during human approval flow", e)
            activeApprovals.remove(token)
            return [approved: false, error: "System error during transaction approval check: " + e.getMessage()]
        }
    }

    private static String getAuthToken(ExecutionContextFactoryImpl ecfi) {
        String token = System.getenv("WEBMCP_SERVER_TOKEN") ?: System.getProperty("WEBMCP_SERVER_TOKEN")
        if (token) return token
        
        // Fallback: check .env files in runtime directory or parent
        List<File> envFiles = [
            new File(ecfi.getRuntimePath(), ".env"),
            new File(ecfi.getRuntimePath(), "../.env")
        ]
        for (File envFile in envFiles) {
            if (envFile.exists()) {
                for (String line : envFile.readLines()) {
                    line = line.trim()
                    if (line.startsWith("WEBMCP_SERVER_TOKEN=")) {
                        token = line.substring("WEBMCP_SERVER_TOKEN=".length()).trim()
                        if (token.startsWith('"') && token.endsWith('"')) token = token.substring(1, token.length() - 1)
                        if (token.startsWith("'") && token.endsWith("'")) token = token.substring(1, token.length() - 1)
                        if (token) return token
                    }
                }
            }
        }
        return "816554a337e2d73431bd2903642f993b" // Dev fallback default
    }
}

@CompileStatic
class ApprovalContext {
    final String token
    final String toolName
    final Map arguments
    final CountDownLatch latch = new CountDownLatch(1)
    volatile boolean approved = false
    volatile String rejectReason = null

    ApprovalContext(String token, String toolName, Map arguments) {
        this.token = token
        this.toolName = toolName
        this.arguments = arguments
    }
}
