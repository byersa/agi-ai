package org.moqui.ai

import org.moqui.adk.AdkManager
import com.google.adk.agents.LlmAgent
import com.google.adk.tools.FunctionTool
import org.slf4j.LoggerFactory

def logger = LoggerFactory.getLogger("org.moqui.ai.AgiAdkLifecycleBinding")
logger.info("⚡ [AGI-AI LIFECYCLE] Grafting advanced WebMCP tools into Google ADK manager...")

try {
    // Dynamic Hierarchical Search for global API keys in local environment config
    String apiKey = System.getenv('GEMINI_API_KEY') ?: System.getProperty('GEMINI_API_KEY') ?: ''
    if (!apiKey) {
        // Fallback: check .env files in runtime directory or parent
        def runtimePath = ec.factory.runtimePath
        List<File> envFiles = [
            new File(runtimePath, ".env"),
            new File(runtimePath, "../.env")
        ]
        for (File envFile in envFiles) {
            if (envFile.exists()) {
                for (String line : envFile.readLines()) {
                    line = line.trim()
                    if (line.startsWith("GEMINI_API_KEY=")) {
                        apiKey = line.substring("GEMINI_API_KEY=".length()).trim()
                        if (apiKey.startsWith('"') && apiKey.endsWith('"')) apiKey = apiKey.substring(1, apiKey.length() - 1)
                        if (apiKey.startsWith("'") && apiKey.endsWith("'")) apiKey = apiKey.substring(1, apiKey.length() - 1)
                        if (apiKey) break
                    }
                }
            }
            if (apiKey) break
        }
    }

    String modelName = System.getenv('GEMINI_MODEL') ?: System.getProperty('GEMINI_MODEL') ?: 'gemini-2.0-flash'

    String baseInstruction = """\
You are the Automation Groups International (AGI) Platform Kernel.
You operate via strict declarative parameters. You communicate safely and deterministically.
Use the 'get_artifact' tool to retrieve live XML blueprints or canvas files of components.
"""

    // Build unified high-performance LLM agent combining Google ADK tools with AGI developer tools
    def unifiedAgent = LlmAgent.builder()
        .name("agi-platform-kernel")
        .description("Unified AGI AI Agent Platform")
        .instruction(baseInstruction)
        .model(modelName)
        .tools([
            FunctionTool.create(AgiAITools.class, "get_artifact")
        ])
        .build()

    // Seed the ADK Manager with our unified agent and runner instance
    AdkManager.init(
        unifiedAgent.name(),
        unifiedAgent.model(),
        unifiedAgent.instruction(),
        apiKey
    )

    logger.info("✨ [AGI-AI LIFECYCLE] Google ADK successfully bound to unified AGI developer tools (Model: ${modelName})!")
} catch (Exception e) {
    logger.error("❌ [AGI-AI LIFECYCLE ERROR] Failed to bind custom tools to ADK", e)
}
