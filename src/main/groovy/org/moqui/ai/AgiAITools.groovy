package org.moqui.ai

import com.google.adk.tools.Annotations.Schema
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class AgiAITools {
    private static final Logger logger = LoggerFactory.getLogger(AgiAITools.class)

    @Schema(description = 'Retrieve the declarative visual XML canvas or files of a target Moqui component')
    static Map<String, Object> get_artifact(
            @Schema(name = 'targetComponent', description = 'Name of the component containing the artifact (e.g. agi-ide)') String targetComponent,
            @Schema(name = 'artifactPath', description = 'Path to the target artifact (e.g. screen/agi-ide/MceShell.xml)') String artifactPath) {
        
        logger.info("🛠️ [AGI-AI TOOLS] get_artifact invoked for component: ${targetComponent}, path: ${artifactPath}")
        
        try {
            ExecutionContext ec = org.moqui.Moqui.getExecutionContext()
            if (!ec) {
                logger.warn("⚠️ No active execution context found inside get_artifact tool call.")
                return [error: "No active execution context"]
            }

            def serviceResult = ec.service.sync()
                .name("org.moqui.ide.AgiMcpServices.get#XmlArtifactBlueprint")
                .parameters([targetComponent: targetComponent, artifactPath: artifactPath])
                .call()
            
            def resultPayload = [
                success: true,
                component: targetComponent,
                path: artifactPath,
                blueprint: serviceResult.blueprintJson ?: [:]
            ]

            // Intercept and dispatch visual frame instantly down the active WebSocket connection
            try {
                def wsSessionObj = ec.context.get("webSocketSession")
                if (wsSessionObj) {
                    jakarta.websocket.Session wsSession = (jakarta.websocket.Session) wsSessionObj
                    if (wsSession.isOpen()) {
                        Map frame = [
                            type: "visualFrame",
                            tool: "get_artifact",
                            componentId: ec.context.get("activeComponentId") ?: targetComponent,
                            data: resultPayload
                        ]
                        wsSession.getBasicRemote().sendText(new groovy.json.JsonBuilder(frame).toString())
                        logger.info("🎨 [AGI-AI TOOLS] Dispatched visual frame for get_artifact to WebSocket session: ${wsSession.id}")
                    }
                }
            } catch (Exception ex) {
                logger.error("❌ Failed to transmit visual frame down WebSocket", ex)
            }

            return resultPayload
        } catch (Exception e) {
            logger.error("❌ Failed to execute get_artifact tool", e)
            return [error: "Failed to execute tool: " + e.getMessage()]
        }
    }
}
