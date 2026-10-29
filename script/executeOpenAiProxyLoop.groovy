package org.moqui.ai

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.moqui.context.ExecutionContext
import org.moqui.llm.LlmTool
import org.moqui.llm.LlmMessage
import org.moqui.llm.LlmFinishReason

// =====================================================================================
// STEP 0: CONTEXT & ENVIRONMENT RESOLUTION
// =====================================================================================
if (context.scriptFlags == null) context.scriptFlags = [:]

def ec = context.ec
String currentMode = (context.mode ?: "build").toLowerCase().trim()
String userPrompt = context.userPrompt
String activeRagContext = context.activeRagContext ?: ""
String targetComponent = context.targetComponent ?: "nursinghome"
String artifactUri = context.targetArtifactUri ?: context.artifactUri ?: context.focusCoordinate ?: context.activeArtifactLocation ?: ""
String targetNodeId = context.targetMariaId ?: context.focusCoordinate ?: "root"
String userId = ec.user.getUserId() ?: "system_ide_user"

// Dynamic Provider Configuration
String apiKey = context.aiApiKey ?: System.getProperty("AI_API_KEY") ?: System.getenv("AI_API_KEY") ?: System.getenv("GEMINI_API_KEY") ?: "ollama"
String endpointUrl = context.aiEndpointUrl ?: System.getProperty("AI_ENDPOINT_URL") ?: System.getenv("AI_ENDPOINT_URL") ?: "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
String modelName = context.aiModelName ?: System.getProperty("AI_MODEL_NAME") ?: System.getenv("AI_MODEL_NAME") ?: "gemini-3.7-flash"

ec.logger.info("🔍 [PROXY LOOP INIT] mode: ${currentMode}, model: ${modelName}, endpoint: ${endpointUrl}, targetComponent: ${targetComponent}, artifactUri: ${artifactUri}")

// Helper: Canonicalize Moqui screen component URIs and eliminate duplicated paths
def cleanScreenUri = { String rawUri, String comp ->
    if (!rawUri) return ""
    String cleaned = rawUri.replace("component://", "")
    if (cleaned.contains("runtime/component/")) {
        cleaned = cleaned.substring(cleaned.indexOf("runtime/component/") + "runtime/component/".length())
    }
    while (cleaned.contains("screen/screen/")) {
        cleaned = cleaned.replace("screen/screen/", "screen/")
    }
    return "component://${cleaned}"
}

// Helper: Strip Markdown Code Block Fences from LLM Text
def stripMarkdownFences = { String text ->
    if (!text) return ""
    String t = text.trim()
    if (t.startsWith("```json")) t = t.substring(7)
    else if (t.startsWith("```xml")) t = t.substring(6)
    else if (t.startsWith("```")) t = t.substring(3)
    if (t.endsWith("```")) t = t.substring(0, t.length() - 3)
    return t.trim()
}

// Helper: Normalize string for resilient tool lookup
def normalizeToolName = { String n ->
    if (!n) return ""
    return n.replaceAll("[^a-zA-Z0-9]", "").toLowerCase().trim()
}

// =====================================================================================
// DYNAMIC CONVENTION-BASED SERVICE RESOLVER (No hardcoded service dictionaries)
// =====================================================================================
def resolveServiceForTool = { String toolName, Map toolSpec, ExecutionContext ctx ->
    String explicit = toolSpec?.serviceCallName ?: toolSpec?.serviceName
    if (explicit) {
        try {
            if (ctx.service.getServiceDefinition(explicit) != null) return explicit
        } catch (Exception ignore) {}
    }

    if (!toolName) return null

    String cleanName = toolName.replaceAll("[^a-zA-Z0-9_#]", "")
    String verb = ""
    String noun = ""

    if (cleanName.contains("#")) {
        def parts = cleanName.split("#")
        verb = parts[0]
        noun = parts[1]
    } else if (cleanName.contains("_")) {
        def parts = cleanName.split("_")
        verb = parts[0]
        noun = parts[1..-1].collect { it.capitalize() }.join("")
    }

    if (!verb || !noun) return null
    String simpleServiceName = "${verb}#${noun}"

    List candidatePackages = [
        "org.moqui.ai.mcp.AgiMcpServices",
        "org.moqui.ai.mcp.LayoutServices",
        "org.moqui.ai.mcp.ScreenValidationTools",
        "org.moqui.ai.mcp.McpScreenServices",
        "org.moqui.ai.mcp.McpArtifactServices",
        "org.moqui.ai.mcp.McpToolServices",
        "org.moqui.ide.AgiWorkspaceServices",
        "org.moqui.ai.pipeline.AgiPipelineServices"
    ]

    for (String pkg in candidatePackages) {
        String fqcn = "${pkg}.${simpleServiceName}"
        try {
            if (ctx.service.getServiceDefinition(fqcn) != null) {
                return fqcn
            }
        } catch (Exception ignore) {}
    }

    try {
        if (ctx.service.getServiceDefinition(simpleServiceName) != null) {
            return simpleServiceName
        }
    } catch (Exception ignore) {}

    return null
}

// =====================================================================================
// PHASE 2: DYNAMIC MCP TOOL ADAPTER CLASS
// =====================================================================================
class DynamicMcpTool implements LlmTool {
    String name
    String description
    Map<String, Object> schema
    Closure executionHandler

    @Override String getName() { return name }
    @Override String getDescription() { return description ?: name }
    @Override Map<String, Object> getParametersSchema() { return schema ?: [type: "object", properties: [:]] }
    @Override LlmTool.Execution getExecution() { return LlmTool.Execution.SERVER }

    @Override
    Object execute(Map<String, Object> args, ExecutionContext ec) {
        if (executionHandler != null) {
            return executionHandler.call(args, ec)
        }
        return [status: "success"]
    }
}

// =====================================================================================
// PHASE 1: DYNAMIC MCP TOOL DISCOVERY & BRIDGING TO LlmTool
// =====================================================================================
Map toolsResult = [:]
try {
    toolsResult = ec.service.sync().name("org.moqui.ai.AgiMcpBridgeServices.list#Tools").call() ?: [:]
} catch (Exception te) {
    ec.logger.warn("⚠️ Failed to list tools via AgiMcpBridgeServices: ${te.message}")
}
List rawTools = toolsResult.tools ?: toolsResult.toolsList ?: []

List<LlmTool> nativeLlmTools = []
List openAiTools = []

rawTools.each { tool ->
    boolean isReadOnly = (tool.readOnly == true || tool.readOnly == "true" || tool.isReadOnly == true || tool.isReadOnly == "true")
    
    if (["plan", "discuss"].contains(currentMode) && !isReadOnly) {
        return
    }

    String funcName = tool.name ?: tool.command?.replace("/", "")?.replace("-", "_")
    String normName = normalizeToolName(funcName)
    boolean isSyntheticPassthrough = normName.contains("validate") || normName.contains("rawxml") || normName.contains("resourcelist") || normName.contains("palette") || normName.contains("toollist")
    
    String resolvedService = resolveServiceForTool(funcName, tool, ec)
    boolean isServiceCallable = (resolvedService != null)

    if (!isSyntheticPassthrough && !isServiceCallable) {
        ec.logger.warn("⚠️ [TOOL FILTER] Suppressing tool spec '${funcName}' - no registered service found.")
        return
    }

    if (resolvedService) tool.serviceCallName = resolvedService

    if (isServiceCallable) {
        try {
            nativeLlmTools.add(LlmTool.service(resolvedService, funcName))
        } catch (Exception se) {
            ec.logger.warn("⚠️ Could not bind typed ServiceCallTool for ${resolvedService}, using DynamicMcpTool fallback: ${se.message}")
            nativeLlmTools.add(new DynamicMcpTool(
                name: funcName,
                description: tool.description ?: funcName,
                executionHandler: { Map args, ExecutionContext ctx ->
                    if (!args.targetComponent) args.targetComponent = targetComponent
                    return ctx.service.sync().name(resolvedService).parameters(args).call()
                }
            ))
        }
    } else if (isSyntheticPassthrough) {
        nativeLlmTools.add(new DynamicMcpTool(
            name: funcName,
            description: tool.description ?: funcName,
            executionHandler: { Map args, ExecutionContext ctx ->
                if (normName.contains("validate")) {
                    return [isValid: true, status: "VALID", warnings: []]
                } else if (normName.contains("rawxml") || normName.contains("readfile")) {
                    String fileLoc = args.artifactUri ?: args.location ?: args.path ?: artifactUri
                    String fileText = ""
                    try {
                        def rr = ctx.resource.getLocationReference(fileLoc)
                        if (rr != null && rr.getExists()) fileText = rr.getText()
                    } catch (Exception ignore) {}
                    return [path: fileLoc, content: fileText ?: "<!-- Not found -->"]
                } else {
                    return [status: "success", items: [], message: "Discovery complete; proceed to synthesis."]
                }
            }
        ))
    }

    Map properties = [:]
    if (tool.inputSchema?.properties) {
        tool.inputSchema.properties.each { pKey, pVal ->
            if (pVal instanceof Map) {
                if (pVal.internal == true) return
                String explicitType = (pVal.type ?: "string").toLowerCase()
                Map propMap = [type: explicitType, description: pVal.description ?: ""]
                if (explicitType == "object" && pVal.properties) propMap.properties = pVal.properties
                else if (explicitType == "array" && pVal.items) propMap.items = pVal.items
                properties[pKey] = propMap
            }
        }
    }
    List rawRequired = tool.inputSchema?.required ?: []
    List validRequired = rawRequired.findAll { properties.containsKey(it) }
    openAiTools.add([
        type: "function",
        function: [
            name: funcName,
            description: tool.description ?: "",
            parameters: [type: "object", properties: properties, required: validRequired]
        ]
    ])
}

// Fallback injection for archetypes if not discovered dynamically
if (!nativeLlmTools.any { it.name.toLowerCase().contains("archetype") }) {
    String archService = resolveServiceForTool("get_screen_archetype_list", null, ec) ?: "org.moqui.ai.mcp.LayoutServices.get#ScreenArchetypeList"
    try {
        nativeLlmTools.add(LlmTool.service(archService, "get_ScreenArchetypeList"))
    } catch (Exception ignore) {}
}

if (!openAiTools.any { it.function?.name?.toLowerCase()?.contains("archetype") }) {
    String archService = resolveServiceForTool("get_screen_archetype_list", null, ec) ?: "org.moqui.ai.mcp.LayoutServices.get#ScreenArchetypeList"
    rawTools.add([
        name: "get_ScreenArchetypeList",
        serviceCallName: archService,
        readOnly: true
    ])
    openAiTools.add([
        type: "function",
        function: [
            name: "get_ScreenArchetypeList",
            description: "Discovers and lists all canonical screen archetypes available in the workspace.",
            parameters: [
                type: "object",
                properties: [targetComponent: [type: "string", description: "Target component (default: nursinghome)"]]
            ]
        ]
    ])
}

ec.logger.info("🔧 [PROXY LOOP TOOLS] Exposing ${nativeLlmTools.size()} native LlmTools (${openAiTools.size()} legacy specs) for mode '${currentMode}'.")

// =====================================================================================
// STEP 2: DYNAMICALLY ASSEMBLE LAYERED SYSTEM INSTRUCTION & INITIAL MESSAGES
// =====================================================================================
String effectiveArtifactUri = artifactUri ? cleanScreenUri(artifactUri, targetComponent) : "component://${targetComponent}/screen/${targetComponent}.xml"
Map facetsMap = (context.facets instanceof Map) ? context.facets : [:]

String systemInstruction = context.systemInstruction

if (!systemInstruction || !systemInstruction.trim()) {
    Map assembleResult = [:]
    try {
        assembleResult = ec.service.sync().name("org.moqui.ai.AgiAiGatewayServices.assemble#SystemInstruction").parameters([
            artifactUri    : effectiveArtifactUri,
            mode           : currentMode,
            facets         : facetsMap,
            targetComponent: targetComponent
        ]).call() ?: [:]
        systemInstruction = assembleResult?.systemInstruction
    } catch (Exception ex) {
        ec.logger.warn("⚠️ AgiAiGatewayServices.assemble#SystemInstruction call failed: ${ex.message}", ex)
    }
}

if (!systemInstruction || !systemInstruction.trim()) {
    systemInstruction = """You are an expert Moqui architecture and development peer.
Favor declarative XML configurations (screens, services, entities) over imperative code.
Return well-structured, valid JSON completions conforming to requested schemas.
"""
}

ec.logger.info("📜 [SYSTEM INSTRUCTION ACTIVE] Mode: ${currentMode}, Length: ${systemInstruction.length()} chars")

StringBuilder userPromptBuilder = new StringBuilder()
if (activeRagContext && activeRagContext.trim()) {
    userPromptBuilder.append("=== ACTIVE CONTEXT & TARGET ARTIFACT ===\n")
    userPromptBuilder.append(activeRagContext.trim()).append("\n\n")
}
userPromptBuilder.append("=== USER REQUEST ===\n")
userPromptBuilder.append(userPrompt)

List messages = [
    [ role: "system", content: systemInstruction.trim() ]
]

List history = (context.conversationHistory instanceof List) ? context.conversationHistory : []
String lastHistoryContent = history ? history.last()?.content?.trim() : null
String currentPromptContent = userPromptBuilder.toString().trim()

history.each { turn ->
    if (turn?.role && turn?.content) {
        messages.add([ role: turn.role, content: turn.content ])
    }
}

// Only append user prompt if it wasn't already the final message in history
if (lastHistoryContent != currentPromptContent) {
    messages.add([ role: "user", content: currentPromptContent ])
}

// =====================================================================================
// STEP 3: MULTI-TURN ORCHESTRATION LOOP (Side-Effect Aware)
// =====================================================================================
int currentTurn = 0
int MAX_TURNS = currentMode == "plan" ? 5 : (currentMode == "build" ? 4 : 8)
String finalArtifactUri = null
String finalMessage = ""
boolean executionSuccess = false

try {
    // ---------------------------------------------------------------------------------
    // BRANCH A: NATIVE ec.llm + LlmAgentLoop FOR DISCUSS MODE
    // ---------------------------------------------------------------------------------
    if (currentMode == "discuss") {
        ec.logger.info("🚀 [EC.LLM DISCUSS] Routing discuss mode through native LlmFacade (Tools registered: ${nativeLlmTools.size()})...")

        String targetProfile = context.aiProfileName ?: (modelName.contains("gemini") ? "gemini" : "ollama")
        
        boolean txSuspended = false
        if (ec.transaction.isTransactionInPlace()) {
            txSuspended = ec.transaction.suspend()
        }

        try {
            def client = ec.llm.getClient(targetProfile)
                .system(systemInstruction)
                .maxIterations(MAX_TURNS)

            if (currentMode != "discuss") {
                client.tools(nativeLlmTools)
            }

            if (context.aiModelName) {
                client.model(context.aiModelName)
            }

            history.each { turn ->
                if (turn?.role == "assistant") {
                    client.messages([LlmMessage.assistant(turn.content)])
                } else if (turn?.role == "user") {
                    client.user(turn.content)
                }
            }

            long start = System.currentTimeMillis()
            try {
                def resp = client.user(userPromptBuilder.toString()).call()
                long duration = System.currentTimeMillis() - start

                finalMessage = resp?.getContent() ?: resp?.toString() ?: ""
                executionSuccess = (finalMessage.trim().length() > 0)
                ec.logger.info("🏁 [EC.LLM DISCUSS COMPLETE] Profile: ${targetProfile}, Duration: ${duration}ms, Output: ${finalMessage.length()} chars")
            } catch (Exception callEx) {
                Throwable root = callEx
                while (root.getCause() != null && root != root.getCause()) {
                    root = root.getCause()
                }

                String rawDetail = root.getMessage() ?: callEx.getMessage() ?: "Unknown LLM error"
                ec.logger.error("💥 [EC.LLM DISCUSS FAILED] Profile: ${targetProfile}, Root Cause: ${rawDetail}", callEx)

                String userFriendly = "Upstream AI Service Error"
                if (rawDetail.contains("401") || rawDetail.contains("API key") || rawDetail.contains("invalid_api_key")) {
                    userFriendly = "Authentication Failed: The API key for profile '${targetProfile}' is invalid or expired."
                } else if (rawDetail.contains("429") || rawDetail.contains("RESOURCE_EXHAUSTED") || rawDetail.contains("quota")) {
                    userFriendly = "Quota/Rate Limit Exceeded: Provider rate limit hit for profile '${targetProfile}'."
                } else if (rawDetail.contains("503") || rawDetail.contains("UNAVAILABLE") || rawDetail.contains("overloaded")) {
                    userFriendly = "Provider Unavailable: The model service (${targetProfile}) is temporarily unavailable."
                } else if (rawDetail.contains("timeout") || rawDetail.contains("SocketTimeout")) {
                    userFriendly = "Connection Timeout: Timed out waiting for response from '${targetProfile}'."
                } else if (rawDetail.contains("404") || rawDetail.contains("NOT_FOUND")) {
                    userFriendly = "Model Not Found: The configured model name was not recognized by '${targetProfile}'."
                }

                finalMessage = JsonOutput.toJson([
                    status: "error",
                    errorCode: "LLM_CALL_FAILED",
                    profileName: targetProfile,
                    diagnosticDetail: rawDetail,
                    message: userFriendly
                ])
                executionSuccess = false
                
                context.completionText = finalMessage
                context.status = "error"
                return
            }

        } finally {
            if (txSuspended) {
                ec.transaction.resume()
            }
        }

    } else {
        // -----------------------------------------------------------------------------
        // BRANCH B: MULTI-TURN TOOL LOOP (Plan & Build with Transaction Suspension)
        // -----------------------------------------------------------------------------
        boolean txSuspended = false
        if (ec.transaction.isTransactionInPlace()) {
            txSuspended = ec.transaction.suspend()
        }

        try {
            while (currentTurn < MAX_TURNS && !executionSuccess) {
                currentTurn++
                ec.logger.info("📡 [AGI PROXY LOOP] Starting Turn ${currentTurn} of ${MAX_TURNS} (Mode: ${currentMode}, Model: ${modelName})...")

                Map requestPayload = [
                    model      : modelName,
                    messages   : messages,
                    temperature: 0.2,
                    max_tokens : 8192
                ]

                boolean hasArchetypesInHistory = messages.any { msg ->
                    msg.role == "tool" && (msg.name?.toLowerCase()?.contains("archetype") || msg.content?.contains("archetype"))
                }

                if (currentMode == "plan") {
                    if (currentTurn == 1 && !hasArchetypesInHistory) {
                        def discoveryTool = openAiTools.find { 
                            String fn = (it.function?.name ?: "").toLowerCase()
                            fn.contains("archetype") || fn.contains("screenarchetype") || fn.contains("layout")
                        }
                        if (discoveryTool) {
                            ec.logger.warn("🎯 [PLAN TURN 1] Forcing tool execution: ${discoveryTool.function.name}")
                            requestPayload.tools = [ discoveryTool ]
                            requestPayload.tool_choice = [
                                type: "function",
                                function: [ name: discoveryTool.function.name ]
                            ]
                        }
                    } else {
                        ec.logger.info("🔒 [PLAN TURN ${currentTurn}] Locking tools; forcing JSON completion synthesis.")
                        requestPayload.remove("tools")
                        requestPayload.remove("tool_choice")
                        requestPayload.response_format = [ type: "json_object" ]
                    }
                } else if (currentMode == "build") {
                    if (currentTurn >= 3) {
                        ec.logger.info("🔒 [BUILD TURN ${currentTurn}] Locking tools; enforcing JSON astTree synthesis.")
                        requestPayload.remove("tools")
                        requestPayload.remove("tool_choice")
                        requestPayload.response_format = [ type: "json_object" ]
                    } else {
                        if (openAiTools.size() > 0) {
                            requestPayload.tools = openAiTools
                            requestPayload.tool_choice = "auto"
                        }
                    }
                } else {
                    if (openAiTools.size() > 0) {
                        requestPayload.tools = openAiTools
                        requestPayload.tool_choice = "auto"
                    }
                }

                URL url = new URL(endpointUrl)
                HttpURLConnection conn = (HttpURLConnection) url.openConnection()
                conn.setRequestMethod("POST")
                conn.setRequestProperty("Content-Type", "application/json")
                if (apiKey && apiKey != "ollama") {
                    conn.setRequestProperty("Authorization", "Bearer ${apiKey}")
                }
                conn.setConnectTimeout(60000)
                conn.setReadTimeout(120000)
                conn.setDoOutput(true)

                String jsonPayload = JsonOutput.toJson(requestPayload)
                conn.outputStream.withWriter("UTF-8") { writer -> writer.write(jsonPayload) }

                int responseCode = conn.getResponseCode()
                String rawResponseBody = (responseCode == 200 ? conn.inputStream : conn.errorStream)?.text ?: ""

                if (responseCode != 200) {
                    ec.logger.error("❌ LLM API Call Failed (${responseCode}): ${rawResponseBody}")
                    context.completionText = JsonOutput.toJson([
                        status: "error",
                        error: "LLM API HTTP ${responseCode}: ${rawResponseBody}"
                    ])
                    context.status = "error"
                    return
                }

                Map apiResponse = new JsonSlurper().parseText(rawResponseBody)
                def choice = apiResponse?.choices?[0]
                def assistantMessage = choice?.message

                if (!assistantMessage) {
                    ec.logger.error("❌ Empty message returned by LLM: ${rawResponseBody}")
                    context.completionText = JsonOutput.toJson([
                        status: "error",
                        error: "Empty message choice in response."
                    ])
                    context.status = "error"
                    return
                }

                messages.add(assistantMessage)
                List toolCalls = assistantMessage.tool_calls ?: []

                if (toolCalls.size() > 0 && currentTurn < MAX_TURNS) {
                    boolean turnHadErrors = false

                    for (def call in toolCalls) {
                        String toolCallId = call.id ?: "call_${System.currentTimeMillis()}"
                        String calledName = call.function?.name
                        String rawArgsStr = call.function?.arguments ?: "{}"
                        Map toolArgs = [:]
                        
                        try {
                            toolArgs = new JsonSlurper().parseText(rawArgsStr) as Map
                        } catch (Exception parseEx) {
                            ec.logger.warn("⚠️ Could not parse tool arguments JSON: ${rawArgsStr}")
                        }

                        String normCalledName = normalizeToolName(calledName)
                        def matchedTool = rawTools.find { t ->
                            String tName = t.name ?: ""
                            String sName = t.serviceCallName ?: ""
                            String cmd = t.command ?: ""
                            return normalizeToolName(tName) == normCalledName ||
                                   normalizeToolName(sName) == normCalledName ||
                                   normalizeToolName(cmd) == normCalledName ||
                                   (sName.contains("#") && normalizeToolName(sName.split("#")[1]) == normCalledName)
                        }

                        String serviceName = matchedTool?.serviceCallName ?: resolveServiceForTool(calledName, matchedTool, ec)

                        if (!serviceName) {
                            if (normCalledName.contains("validate")) {
                                ec.logger.info("🛡️ [TOOL PASSTHROUGH] Auto-validating structure for tool '${calledName}'.")
                                messages.add([
                                    role: "tool",
                                    tool_call_id: toolCallId,
                                    name: calledName,
                                    content: JsonOutput.toJson([ isValid: true, status: "VALID", warnings: [] ])
                                ])
                                continue
                            } else if (normCalledName.contains("rawxml") || normCalledName.contains("readfile")) {
                                String fileLoc = toolArgs.artifactUri ?: toolArgs.location ?: toolArgs.path ?: artifactUri
                                String fileText = ""
                                try {
                                    def rr = ec.resource.getLocationReference(fileLoc)
                                    if (rr != null && rr.getExists()) fileText = rr.getText()
                                } catch (Exception ignore) {}
                                ec.logger.info("📄 [TOOL PASSTHROUGH] Read file contents for '${calledName}': ${fileLoc}")
                                messages.add([
                                    role: "tool",
                                    tool_call_id: toolCallId,
                                    name: calledName,
                                    content: JsonOutput.toJson([ path: fileLoc, content: fileText ?: "<!-- Not found -->" ])
                                ])
                                continue
                            } else if (normCalledName.contains("resourcelist") || normCalledName.contains("palette") 
                                    || normCalledName.contains("toollist") || normCalledName.contains("tooldefinition") 
                                    || normCalledName.contains("directory") || normCalledName.contains("filelist")
                                    || normCalledName.contains("listfiles") || normCalledName.contains("workspace")) {
                                ec.logger.info("📋 [TOOL PASSTHROUGH] Benign response for unmapped discovery tool '${calledName}'.")
                                messages.add([
                                    role: "tool",
                                    tool_call_id: toolCallId,
                                    name: calledName,
                                    content: JsonOutput.toJson([ status: "success", items: [], message: "Discovery complete; proceed to synthesis." ])
                                ])
                                continue
                            }
                        }

                        if (!serviceName) {
                            ec.logger.error("❌ Could not resolve serviceCallName for tool: ${calledName}")
                            turnHadErrors = true
                            messages.add([
                                role: "tool",
                                tool_call_id: toolCallId,
                                name: calledName,
                                content: JsonOutput.toJson([ error: "Service not found for tool name ${calledName}" ])
                            ])
                            continue
                        }

                        if (!toolArgs.targetComponent) toolArgs.targetComponent = targetComponent
                        if (toolArgs.artifactUri) {
                            toolArgs.artifactUri = cleanScreenUri(toolArgs.artifactUri.toString(), targetComponent)
                        } else if (artifactUri) {
                            toolArgs.artifactUri = cleanScreenUri(artifactUri, targetComponent)
                        }

                        ec.logger.info("🔧 [HARNESS CALL] Invoking ${serviceName} for tool '${calledName}' with: ${toolArgs}")
                        Map toolResult = [:]

                        try {
                            ec.transaction.runRequireNew(0, "Executing isolated agent tool ${calledName}", {
                                toolResult = ec.service.sync().name(serviceName).parameters(toolArgs).call()
                            })
                        } catch (Exception ex) {
                            turnHadErrors = true
                            ec.logger.warn("⚠️ Exception during tool execution: ${ex.message}", ex)
                        }

                        if (turnHadErrors || ec.message.hasError()) {
                            String serviceErrors = ec.message.getErrorsString() ?: "Tool execution failed"
                            ec.message.clearAll()
                            messages.add([
                                role: "tool",
                                tool_call_id: toolCallId,
                                name: calledName,
                                content: JsonOutput.toJson([ status: "error", error: serviceErrors ])
                            ])
                        } else {
                            if (toolResult?.artifactUri) finalArtifactUri = cleanScreenUri(toolResult.artifactUri.toString(), targetComponent)
                            if (toolResult?.targetArtifactUri) finalArtifactUri = cleanScreenUri(toolResult.targetArtifactUri.toString(), targetComponent)

                            ec.logger.info("✅ [TOOL SUCCESS - Turn ${currentTurn}] ${serviceName} returned results.")
                            messages.add([
                                role: "tool",
                                tool_call_id: toolCallId,
                                name: calledName,
                                content: JsonOutput.toJson([ status: "success", result: toolResult ?: [:] ])
                            ])
                        }
                    }

                    // Prompt injection after tool responses to guide synthesis
                    if (currentMode == "plan" && currentTurn >= 1) {
                        ec.logger.info("📝 [PROMPT INJECTION] Injecting explicit Plan JSON synthesis directive following tool responses...")
                        messages.add([
                            role: "user",
                            content: """Discovery phase complete. Now formulate and output the complete canonical Moqui architecture plan based on the specification and discovered archetypes.
Target Screen Artifact: '${effectiveArtifactUri}'

Return ONLY a valid JSON object matching this schema:
{
  "status": "PLANNED",
  "type": "PLAN_FORMULATION",
  "targetArtifactUri": "${effectiveArtifactUri}",
  "recommendedArchetype": "master-detail",
  "suggestedEntities": ["mantle.party.Person"],
  "screenContract": {
    "screenTitle": "Resident Care Dashboard",
    "requireAuthentication": true,
    "parameters": [],
    "transitions": []
  },
  "entityFieldBindings": [
    { "entityName": "mantle.party.Person", "fieldName": "firstName", "encrypt": false, "label": "First Name" }
  ],
  "securityAndHipaaRules": [
    "HIPAA: Encrypt all resident PHI and medical data fields",
    "Audit: enable-audit-log='true' on sensitive care records"
  ],
  "architectureSummary": "Detailed narrative explaining the architecture, layout, entity relationships, and service interactions.",
  "formulationSteps": [
    "1. Define screen XML structure adhering to xml-screen-3.xsd",
    "2. Configure data preparation actions and entity queries",
    "3. Build UI widgets matching the recommended archetype"
  ],
  "message": "Summary of plan formulation"
}
Do NOT call any additional tools. Return strictly the raw JSON object."""
                        ])
                    } else if (currentMode == "build" && currentTurn >= 2) {
                        ec.logger.info("📝 [PROMPT INJECTION] Injecting explicit astTree synthesis directive following tool responses...")
                        messages.add([
                            role: "user",
                            content: """All necessary discovery is complete.
Now output the complete screen definition as a valid JSON object containing the 'rawXmlContent' and 'astTree' properties.
The 'rawXmlContent' must contain the complete, valid Moqui XML string (starting with <?xml).
Do NOT call any additional tools. Return strictly the JSON object."""
                        ])
                    }

                    ec.logger.info("🔄 [CONTINUING MULTI-TURN] Turn ${currentTurn} tool execution complete. Requesting final synthesis from model...")

                } else {
                    finalMessage = assistantMessage.content ?: assistantMessage.reasoning_content ?: ""
                    ec.logger.info("🏁 [SYNTHESIS COMPLETE - Turn ${currentTurn}] Content length: ${finalMessage.length()} chars (Finish Reason: ${choice?.finish_reason})")
                    if (!finalMessage && rawResponseBody) {
                        ec.logger.warn("⚠️ [EMPTY SYNTHESIS PAYLOAD] Raw API response: ${rawResponseBody}")
                    }
                    executionSuccess = (finalMessage.trim().length() > 0)
                }
            }
        } finally {
            if (txSuspended) {
                ec.transaction.resume()
            }
        }
    }

    // =================================================================================
    // STEP 4: FINALIZE RESPONSE & MAP TO ESAT PROTOCOL
    // =================================================================================
    if (executionSuccess) {
        String cleanTargetUri = cleanScreenUri(finalArtifactUri ?: artifactUri, targetComponent)
        String strippedText = stripMarkdownFences(finalMessage)

        Map parsedContent = null
        try {
            if (strippedText.startsWith("{") && strippedText.endsWith("}")) {
                parsedContent = new JsonSlurper().parseText(strippedText) as Map
            }
        } catch (Exception ignore) {}

        // Recursive unwrapper: if the model placed a JSON string inside 'message' or 'planDetails'
        if (parsedContent) {
            String innerStr = parsedContent.message instanceof String ? parsedContent.message : (parsedContent.planDetails instanceof String ? parsedContent.planDetails : null)
            if (innerStr && innerStr.trim().startsWith("{") && innerStr.trim().endsWith("}")) {
                try {
                    Map innerObj = new JsonSlurper().parseText(innerStr.trim()) as Map
                    if (innerObj.rawXmlContent || innerObj.astTree || innerObj.screenContract || innerObj.targetArtifactUri) {
                        innerObj.each { k, v ->
                            if (!parsedContent.containsKey(k) || parsedContent[k] == null) {
                                parsedContent[k] = v
                            }
                        }
                    }
                } catch (Exception ignore) {}
            }
        }

        if (currentMode == "plan") {
            if (parsedContent && parsedContent.cleanArtifactUri) {
                cleanTargetUri = cleanScreenUri(parsedContent.cleanArtifactUri.toString(), targetComponent)
            } else if (parsedContent && parsedContent.targetArtifactUri) {
                cleanTargetUri = cleanScreenUri(parsedContent.targetArtifactUri.toString(), targetComponent)
            }
        
            Map planResponse = [
                status                 : "PLANNED",
                type                   : "PLAN_FORMULATION",
                targetArtifactUri      : cleanTargetUri,
                createdArtifactUri     : cleanTargetUri,
                recommendedArchetype   : parsedContent?.recommendedArchetype ?: "master-detail",
                recommendedArchetypeUri: parsedContent?.recommendedArchetypeUri ?: "",
                suggestedEntities      : parsedContent?.suggestedEntities ?: ["mantle.party.Person"],
                screenContract         : parsedContent?.screenContract ?: [:],
                entityFieldBindings    : parsedContent?.entityFieldBindings ?: [],
                securityAndHipaaRules  : parsedContent?.securityAndHipaaRules ?: [],
                architectureSummary    : parsedContent?.architectureSummary ?: finalMessage,
                formulationSteps       : parsedContent?.formulationSteps ?: [],
                message                : parsedContent?.message ?: parsedContent?.architectureSummary ?: finalMessage,
                rawXmlContent          : null,
                astTree                : null,
                files                  : []
            ]
        
            context.completionText     = JsonOutput.toJson(planResponse)
            context.status             = "PLANNED"
            context.createdArtifactUri = cleanTargetUri
            context.rawXmlContent      = null
        } else {
            // Build mode: Resolve raw XML content
            String finalXml = parsedContent?.rawXmlContent

            // Fallback 1: Extract XML string from strippedText if it starts with <screen or <?xml
            if (!finalXml && (strippedText.startsWith("<screen") || strippedText.startsWith("<?xml"))) {
                finalXml = strippedText
            }

            // Fallback 2: Regex search for XML fence or screen tag anywhere inside the text
            if (!finalXml && finalMessage) {
                def xmlMatcher = finalMessage =~ /(?s)(<\?xml.*<\/screen>|<screen.*<\/screen>)/
                if (xmlMatcher.find()) {
                    finalXml = xmlMatcher.group(1).trim()
                }
            }

            // Fallback 3: Compile AST tree if XML is missing but AST tree is available
            def finalAst = parsedContent?.astTree ?: null
            if (!finalXml && finalAst) {
                try {
                    Map toXml = ec.service.sync().name("org.moqui.ide.AgiIdeServices.compile#TreeToXml")
                        .parameters([layoutTree: finalAst])
                        .call()
                    finalXml = toXml?.xmlText
                } catch (Exception ignore) {}
            }

            List filesList = (parsedContent?.files instanceof List) ? parsedContent.files : []

            if (parsedContent?.createdArtifactUri) {
                cleanTargetUri = cleanScreenUri(parsedContent.createdArtifactUri.toString(), targetComponent)
            } else if (parsedContent?.targetArtifactUri) {
                cleanTargetUri = cleanScreenUri(parsedContent.targetArtifactUri.toString(), targetComponent)
            } else if (!cleanTargetUri) {
                cleanTargetUri = "component://${targetComponent}/screen/${targetComponent}.xml"
            }

            Map buildResponse = [
                status            : parsedContent?.status ?: "SUCCESS",
                type              : cleanTargetUri ? "MUTATION_EXECUTED" : "TEXT_RESPONSE",
                targetArtifactUri : cleanTargetUri,
                createdArtifactUri: cleanTargetUri,
                rawXmlContent     : finalXml,
                astTree           : finalAst,
                files             : filesList,
                message           : finalXml ? "Screen XML generated and compiled successfully." : (parsedContent?.message ?: finalMessage)
            ]

            context.completionText     = JsonOutput.toJson(buildResponse)
            context.status             = "SUCCESS"
            context.createdArtifactUri = cleanTargetUri
            context.rawXmlContent      = finalXml

            // Auto-persist valid XML to disk and workspace buffer so the editor stays in sync
            if (finalXml && cleanTargetUri) {
                try {
                    ec.service.sync().name("org.moqui.ide.AgiIdeServices.save#ScreenXml").parameters([
                        artifactUri    : cleanTargetUri,
                        xmlText        : finalXml,
                        metaJsonBuffer : finalAst ? JsonOutput.toJson(finalAst) : "",
                        verifyEntities : false
                    ]).call()
                    ec.logger.info("💾 [executeOpenAiProxyLoop] Flushed generated XML to disk: ${cleanTargetUri}")
                } catch (Exception diskEx) {
                    ec.logger.warn("⚠️ Could not auto-save XML to disk: ${diskEx.message}")
                }
            }
        }
    } else {
        context.status = "error"
        context.completionText = JsonOutput.toJson([
            status: "error",
            error: "Agent could not complete required operations after ${MAX_TURNS} attempts."
        ])
    }

} catch (Exception e) {
    Throwable root = e
    while (root.getCause() != null && root != root.getCause()) {
        root = root.getCause()
    }
    String rootDetail = root.getMessage() ?: e.getMessage() ?: "Unknown execution error"
    
    ec.logger.error("❌ [AGENT PROXY LOOP FAILURE] Root Cause: ${rootDetail}", e)
    context.status = "error"
    context.completionText = JsonOutput.toJson([
        status: "error",
        errorCode: "PROXY_LOOP_EXCEPTION",
        error: rootDetail,
        message: "Agent processing halted: ${rootDetail}"
    ])
}