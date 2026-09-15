package org.moqui.ai

import org.slf4j.LoggerFactory
import org.moqui.context.ExecutionContext

def logger = LoggerFactory.getLogger("org.moqui.ai.AgiBackendBootstrapper")
logger.info("⚡ [AGI BOOTSTRAP] Initializing workspace script context and lifecycle bootstrapper.")

// Perform backend heartbeat check for local sidecar server
def sidecarPort = 4797
boolean isNodeUp = false
try {
    new java.net.Socket("127.0.0.1", sidecarPort).withCloseable { isNodeUp = true }
} catch (Exception e) {}

if (!isNodeUp) {
    logger.warn("⚠️ AGI Shell: Local WebMCP Node server (4797) is offline. Start it manually with start-sidecar.sh.")
} else {
    logger.info("✅ AGI Shell: WebMCP Node Server detected on port 4797.")
}

// Helpers to inject unique assets into the screen rendering context if available
def addUniqueStyle = { url ->
    def hs = context.html_stylesheets ?: ec.context.get("html_stylesheets")
    if (hs != null && !hs.contains(url)) hs.add(url)
}
def addUniqueScript = { url ->
    def fs = context.footer_scripts ?: ec.context.get("footer_scripts")
    if (fs != null && !fs.contains(url)) fs.add(url)
}

// 1. Inject Platform Fonts and Quasar Core Stylesheets
addUniqueStyle("https://fonts.googleapis.com/css?family=Roboto:100,300,400,500,700,900|Material+Icons|Material+Icons+Outlined")
addUniqueStyle("https://unpkg.com/quasar@2.12.6/dist/quasar.prod.css")

// 2. Inject Vue 3, Quasar, and Konva Engines
String instancePurpose = System.getProperty("instance_purpose")
boolean isProd = !instancePurpose || instancePurpose == 'production'

if (isProd) {
    addUniqueScript("https://unpkg.com/vue@3.3.4/dist/vue.global.prod.js")
    addUniqueScript("https://unpkg.com/quasar@2.12.6/dist/quasar.umd.prod.js")
} else {
    addUniqueScript("https://unpkg.com/vue@3.3.4/dist/vue.global.js")
    addUniqueScript("https://unpkg.com/quasar@2.12.6/dist/quasar.umd.js")
}
addUniqueScript("https://unpkg.com/konva@10/konva.js")

// 3. Inject Core Utility Libraries
addUniqueScript("/libs/moment.js/moment-with-locales.min.js")
addUniqueScript("/libs/jquery/jquery.min.js")

logger.info("⚡ [AGI-AI BOOTSTRAP] Discovering runtime environment configuration...")

try {
    // Dynamic search for global API keys in local environment config
    String apiKey = System.getenv('GEMINI_API_KEY') ?: System.getProperty('GEMINI_API_KEY') ?: ''
    
    ExecutionContext ec = org.moqui.Moqui.getExecutionContext()
    
    if (!apiKey && ec) {
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

    String modelName = System.getenv('GEMINI_MODEL') ?: System.getProperty('GEMINI_MODEL') ?: 'gemini-1.5-pro'

    logger.info("✨ [AGI-AI BOOTSTRAP] AGI AI runtime initialized (Configured Model: ${modelName}).")

    // Seed default context values for downstream scripts and templates
    context.put("isDesignMode", "Y")
    context.put("defaultChannel", "facility-alerts")
    context.put("connectionToken", apiKey ?: context.get("webmcpToken"))
    context.put("systemReady", true)
} catch (Exception e) {
    logger.error("❌ [AGI-AI BOOTSTRAP ERROR] Error initializing environment configuration", e)
}