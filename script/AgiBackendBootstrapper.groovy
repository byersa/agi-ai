package org.moqui.ai

import org.slf4j.LoggerFactory
import org.moqui.context.ExecutionContext

def logger = LoggerFactory.getLogger("org.moqui.ai.AgiBackendBootstrapper")
logger.info("⚡ [AGI BOOTSTRAP] Initializing workspace script context and lifecycle bootstrapper.")

// Perform backend heartbeat warning check to standard server logs
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

// Safe helpers to inject unique assets into the screen rendering context if available
def addUniqueStyle = { url ->
    def hs = context.html_stylesheets ?: ec.context.get("html_stylesheets")
    if (hs != null && !hs.contains(url)) hs.add(url)
}
def addUniqueScript = { url ->
    def fs = context.footer_scripts ?: ec.context.get("footer_scripts")
    if (fs != null && !fs.contains(url)) fs.add(url)
}

long ts = System.currentTimeMillis()

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

// 3. Inject Core Utility Libraries and our Dynamic Universal WebMCP Client
addUniqueScript("/libs/moment.js/moment-with-locales.min.js")
addUniqueScript("/libs/jquery/jquery.min.js")
addUniqueScript("/agi-ai-assets/webmcp.js?v=${ts}")

long scriptTs = System.currentTimeMillis()
addUniqueScript("/agi-ai-assets/webmcp.js?v=${scriptTs}")

logger.info("⚡ [AGI-AI BOOTSTRAP] Bootstrapping advanced AGI platform kernel and tool registrations...")

try {
    // Dynamic Hierarchical Search for global API keys in local environment config
    String apiKey = System.getenv('GEMINI_API_KEY') ?: System.getProperty('GEMINI_API_KEY') ?: ''
    
    // Safely obtain active execution context
    ExecutionContext ec = org.moqui.Moqui.getExecutionContext()
    
    if (!apiKey && ec) {
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

    String modelName = System.getenv('GEMINI_MODEL') ?: System.getProperty('GEMINI_MODEL') ?: 'gemini-1.5-pro'

    String baseInstruction = """\
You are the Automation Groups International (AGI) Platform Kernel.
You operate via strict declarative parameters. You communicate safely and deterministically.
Use the 'get_artifact' tool to retrieve live XML blueprints or canvas files of components.
"""

    def dynamicTools = []
    if (ec) {
        try {
            def serviceResult = ec.service.sync().name("org.moqui.ai.AdkMcpBridge.load#DynamicTools").call()
            if (serviceResult && serviceResult.toolsList) {
                dynamicTools = serviceResult.toolsList
            }
        } catch (Exception e) {
            logger.warn("⚠️ Could not load dynamic MCP tools during startup, falling back to static discovery: ${e.message}")
            try {
                def bridgeClass = Thread.currentThread().getContextClassLoader().loadClass("org.moqui.ai.AdkMcpBridge")
                dynamicTools = (List<Object>) bridgeClass.getMethod("getTools").invoke(null)
                logger.info("Successfully loaded dynamic tools via reflective bridge layer.")
            } catch (ClassNotFoundException ex) {
                logger.warn("AdkMcpBridge class not found by transient loader. Falling back to clean default arrays.")
                dynamicTools = []
            } catch (Exception ex) {
                logger.error("Failed to map tools via reflective bridge: " + ex.getMessage())
                dynamicTools = []
            }
        }
    } else {
        logger.warn("⚠️ No active ExecutionContext available during tool discovery, falling back to static bridge.")
        try {
            def bridgeClass = Thread.currentThread().getContextClassLoader().loadClass("org.moqui.ai.AdkMcpBridge")
            dynamicTools = (List<Object>) bridgeClass.getMethod("getTools").invoke(null)
            logger.info("Successfully loaded dynamic tools via reflective bridge layer.")
        } catch (ClassNotFoundException ex) {
            logger.warn("AdkMcpBridge class not found by transient loader. Falling back to clean default arrays.")
            dynamicTools = []
        } catch (Exception ex) {
            logger.error("Failed to map tools via reflective bridge: " + ex.getMessage())
            dynamicTools = []
        }
    }

    // =========================================================================
    // REMEDIATION: Dynamic Class Resolution for Static Tool Definitions
    // =========================================================================
    // Combine custom AGI developer tools and Ean's screen-browsing/ERP tools
    def functionToolClass = Thread.currentThread().getContextClassLoader().loadClass("com.google.adk.tools.FunctionTool")
    def agiAiToolsClass = Thread.currentThread().getContextClassLoader().loadClass("org.moqui.ai.AgiAITools")
  
    def allTools = [functionToolClass.getMethod("create", Class.class, String.class).invoke(null, agiAiToolsClass, "get_artifact")]
    allTools.addAll(dynamicTools)
    // =========================================================================

    logger.info("📡 [AGI-AI BOOTSTRAP] Grafting ${allTools.size()} dynamic tools into the AGI Platform Kernel agent...")

    // Build unified high-performance LLM agent combining Google ADK tools with AGI developer tools
    def llmAgentClass = Thread.currentThread().getContextClassLoader().loadClass("com.google.adk.agents.LlmAgent")
    def unifiedAgent = llmAgentClass.builder()
        .name("agi-platform-kernel")
        .description("Unified AGI AI Agent Platform")
        .instruction(baseInstruction)
        .model(modelName)
        .tools(allTools)
        .build()

    // =========================================================================
    // REMEDIATION: Type-Safe String Unwrapping for ADK Manager Initialization
    // =========================================================================
    
    def adkManagerClass = Thread.currentThread().getContextClassLoader().loadClass("org.moqui.adk.AdkManager")

    // 1. Extract the name as a string
    String agentName = unifiedAgent.name()

    // 2. Safely unwrap the Model Optional to get its underlying string name
    var modelObj = unifiedAgent.model()
    String finalModelName = modelObj instanceof Optional ? (modelObj.isPresent() ? modelObj.get().toString() : "gemini-1.5-pro") : modelObj.toString()

    // 3. Extract the clean string text out of the Static Instruction wrapper
    var instructionObj = unifiedAgent.instruction()
    String finalInstructionText = instructionObj != null ? instructionObj.toString() : ""

    logger.info("⚡ [AGI-AI BOOTSTRAP] Initializing AdkManager with extracted string signatures...")
    
    // 4. Invoke the method using the exact (String, String, String, String) signature it expects
    adkManagerClass.getMethod("init", String.class, String.class, String.class, String.class).invoke(
        null, // Static method target is null
        agentName,
        finalModelName,
        finalInstructionText,
        apiKey
    )
    // =========================================================================

    // Crucial Override: Reflectively set static fields to bypass Groovy meta-property collisions
    // This forces our fully hydrated unifiedAgent (with tools attached) into the runtime container
    def agentField = adkManagerClass.getDeclaredField("agent")
    agentField.setAccessible(true)
    agentField.set(null, unifiedAgent)
    
    // Dynamically retrieve the application name variable string from the class context
    def appNameField = adkManagerClass.getDeclaredField("APP_NAME")
    appNameField.setAccessible(true)
    String activeAppName = (String) appNameField.get(null)
    
    // Reconstruct the InMemoryRunner context securely using the runtime interface class mapping
    def runnerClass = Thread.currentThread().getContextClassLoader().loadClass("com.google.adk.runner.InMemoryRunner")
    
    // Find any 2-argument constructor on InMemoryRunner to avoid rigid class-type matching wars
    def targetConstructor = runnerClass.getConstructors().find { it.getParameterCount() == 2 }
    
    if (!targetConstructor) {
        throw new NoSuchMethodException("Could not locate a valid 2-argument constructor for InMemoryRunner.")
    }
    
    // Force the arguments array into an explicit Object array to guarantee clean reflection binding
    def newRunnerInstance = targetConstructor.newInstance([unifiedAgent, activeAppName] as Object[])
    
    def runnerField = adkManagerClass.getDeclaredField("runner")
    runnerField.setAccessible(true)
    runnerField.set(null, newRunnerInstance)

    logger.info("✨ [AGI-AI BOOTSTRAP] Google ADK successfully bound to unified AGI developer tools (Model: ${modelName})!")
    
    // Seed default context values for downstream chaining script bootstrappers
    context.put("isDesignMode", "Y")
    context.put("defaultChannel", "facility-alerts")
    context.put("connectionToken", apiKey ?: context.get("webmcpToken"))
    context.put("systemReady", true)
} catch (Exception e) {
    logger.error("❌ [AGI-AI BOOTSTRAP ERROR] Failed to bind custom tools to ADK", e)
}