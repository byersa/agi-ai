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
            ExecutionContext ec = ExecutionContextFactory.getActiveExecutionContext()
            if (!ec) {
                logger.warn("⚠️ No active execution context found inside get_artifact tool call.")
                return [error: "No active execution context"]
            }

            def serviceResult = ec.service.sync()
                .name("org.moqui.ide.AgiMcpServices.get#XmlArtifactBlueprint")
                .parameters([targetComponent: targetComponent, artifactPath: artifactPath])
                .call()
            
            return [
                success: true,
                component: targetComponent,
                path: artifactPath,
                blueprint: serviceResult.blueprintJson ?: [:]
            ]
        } catch (Exception e) {
            logger.error("❌ Failed to execute get_artifact tool", e)
            return [error: "Failed to execute tool: " + e.getMessage()]
        }
    }
}
