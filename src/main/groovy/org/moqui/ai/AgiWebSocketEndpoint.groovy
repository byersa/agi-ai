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

@CompileStatic
class AgiWebSocketEndpoint extends MoquiAbstractEndpoint {
    private final static Logger logger = LoggerFactory.getLogger(AgiWebSocketEndpoint.class)

    // Store active WebSocket connections by channel
    private static final Map<String, Set<Session>> channels = new ConcurrentHashMap<>()

    AgiWebSocketEndpoint() { super() }

    static Map<String, Set<Session>> getChannels() { return channels }

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
        channels.computeIfAbsent(channel, { k -> ConcurrentHashMap.newKeySet() }).add(session)

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
            } else if (type == "userMessage") {
                String text = payload.text
                String componentId = payload.componentId ?: channel

                logger.info("🧠 [AGI-AI WS] Processing userMessage for component: ${componentId}")

                // Run the Gemini conversation loop in an asynchronous worker thread to avoid blocking WebSocket connection threads
                Thread.start {
                    try {
                        def ec = getEcf().getEci()
                        
                        // Grounding details / prompting logic
                        def serviceResult = ec.service.sync().name("org.moqui.ai.GeminiServices.send#Prompt")
                            .parameters([promptText: text, componentId: componentId])
                            .call()
                        
                        String responseText = serviceResult.responseText
                        if (responseText) {
                            responseText = responseText.trim()
                            
                            // If response is JSON, treat as a visual canvas command
                            if (responseText.startsWith("{") && responseText.endsWith("}")) {
                                try {
                                    def commandData = slurper.parseText(responseText)
                                    Map commandPayload = [
                                        type: "command",
                                        componentId: componentId,
                                        data: commandData
                                    ]
                                    session.getBasicRemote().sendText(new JsonBuilder(commandPayload).toString())
                                    logger.info("🎯 [AGI-AI WS] Dispatched visual command payload back to client on channel ${channel}")
                                } catch (Exception e) {
                                    // Fallback to text notification
                                    Map reply = [
                                        type: "notification",
                                        componentId: componentId,
                                        text: responseText
                                    ]
                                    session.getBasicRemote().sendText(new JsonBuilder(reply).toString())
                                }
                            } else {
                                Map reply = [
                                    type: "notification",
                                    componentId: componentId,
                                    text: responseText
                                ]
                                session.getBasicRemote().sendText(new JsonBuilder(reply).toString())
                                logger.info("💬 [AGI-AI WS] Relayed chat notification back to client on channel ${channel}")
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Error processing user message via Gemini", e)
                        try {
                            Map errorPayload = [
                                type: "error",
                                componentId: componentId,
                                message: "Error processing prompt: " + e.getMessage()
                            ]
                            session.getBasicRemote().sendText(new JsonBuilder(errorPayload).toString())
                        } catch (Exception ex) {}
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
