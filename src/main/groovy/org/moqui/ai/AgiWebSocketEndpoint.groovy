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
import org.moqui.context.NotificationMessage
import org.moqui.context.NotificationMessageListener

@CompileStatic
class AgiWebSocketEndpoint extends MoquiAbstractEndpoint implements NotificationMessageListener {
    private final static Logger logger = LoggerFactory.getLogger(AgiWebSocketEndpoint.class)

    // Store active WebSocket connections by channel
    private static final Map<String, Set<Session>> channels = new ConcurrentHashMap<>()

    // Store active human-in-the-loop approval contexts
    private static final Map<String, ApprovalContext> activeApprovals = new ConcurrentHashMap<>()
    
    // Dynamic bus tracking state flag
    private static boolean isRegistered = false

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

        // Safely bind this endpoint instance to the Moqui Core notification bus on first connection
        if (!isRegistered) {
            getEcf().registerNotificationMessageListener(this)
            isRegistered = true
            logger.info("📡 [WS REGISTER] AgiWebSocketEndpoint bound to Moqui Notification Message Bus.")
        }

        // Send welcome message
        Map welcome = [
            type: "welcome",
            channel: "/" + channel,
            message: "Connected to path: /${channel}"
        ]
        session.getBasicRemote().sendText(new JsonBuilder(welcome).toString())
        logger.info("🟢 [AGI-AI WS] Client successfully registered and welcomed on channel: ${channel}")
    }

    // Intercepts core framework messages and pipes them directly out to the browser canvas
    @Override
    void onMessage(NotificationMessage nm) {
        String topic = nm.getTopic()
        String payload = nm.getMessageJson()

        String targetChannel = topic == "agi-ide-canvas" ? "global_canvas" : topic
        
        Set<Session> sessions = channels.get(targetChannel)
        if (sessions && !sessions.isEmpty()) {
            logger.info("📤 [WS ROUTING] Routing topic '${topic}' broadcast to ${sessions.size()} active web canvas sessions")
            for (Session session : sessions) {
                if (session.isOpen()) {
                    session.getBasicRemote().sendText(payload)
                }
            }
        }
    }

    @Override
    void init(org.moqui.context.ExecutionContextFactory ecf) {
        // Lifecycle initialization stub
    }

    @Override
    void destroy() {
        // Lifecycle teardown stub
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
                    approval.latch.countDown()
                    
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
                String text = (String) payload.text
                String componentId = (String) (payload.componentId ?: channel)
                String activeArtifactUri = (String) (payload.artifactUri ?: "")

                logger.info("🧠 [AGI-AI WS] Processing userMessage for component: ${componentId}")

                def ecf = getEcf()
                Thread.start {
                    def ec = ecf.getExecutionContext()
                    try {
                        ec.context.put("webSocketSession", session)
                        ec.context.put("activeComponentId", componentId)
                        ec.context.put("activeChannel", channel)

                        // Dispatch turn via standard AgiAiGatewayServices / Proxy Loop
                        Map turnResult = ec.service.sync()
                            .name("org.moqui.ai.AgiAiGatewayServices.execute#StagedAgentTurn")
                            .parameters([
                                userPrompt      : text,
                                targetComponent : componentId,
                                artifactUri     : activeArtifactUri,
                                mode            : (String) (payload.mode ?: "plan")
                            ])
                            .call()

                        String completionText = (String) turnResult?.completionText ?: ""
                        Map responsePayload = [
                            type        : "agentResponse",
                            componentId : componentId,
                            status      : (String) turnResult?.status ?: "SUCCESS",
                            data        : completionText
                        ]
                        session.getBasicRemote().sendText(new JsonBuilder(responsePayload).toString())

                    } catch (Exception e) {
                        logger.error("❌ [AGI-AI WS] Error processing user message", e)
                        try {
                            Map errorPayload = [
                                type        : "error",
                                componentId : componentId,
                                message     : "Error processing prompt: " + e.getMessage()
                            ]
                            session.getBasicRemote().sendText(new JsonBuilder(errorPayload).toString())
                        } catch (Exception ex) {}
                    } finally {
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
        return "816554a337e2d73431bd2903642f993b"
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