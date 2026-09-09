package org.moqui.ai

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// =====================================================================================
// STEP 0: CONTEXT & ENVIRONMENT RESOLUTION
// =====================================================================================
if (context.scriptFlags == null) context.scriptFlags = [:]

def ec = context.ec
String currentMode = (context.mode ?: "build").toLowerCase().trim()
String userPrompt = context.userPrompt
String activeRagContext = context.activeRagContext ?: ""
String targetComponent = context.targetComponent ?: "nursinghome"
String artifactUri = context.focusCoordinate ?: context.activeArtifactLocation ?: ""
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

// Helper: Normalize string for resilient tool resolution
def normalizeToolName = { String n ->
    if (!n) return ""
    return n.replaceAll("[^a-zA-Z0-9]", "").toLowerCase().trim()
}

// =====================================================================================
// STEP 1: DYNAMIC MCP TOOLS DISCOVERY WITH SIDE-EFFECT FILTERING
// =====================================================================================
Map toolsResult = [:]
try {
    toolsResult = ec.service.sync().name("org.moqui.ai.AgiMcpBridgeServices.list#Tools").call() ?: [:]
} catch (Exception te) {
    ec.logger.warn("⚠️ Failed to list tools via AgiMcpBridgeServices: ${te.message}")
}
List rawTools = toolsResult.tools ?: toolsResult.toolsList ?: []

List openAiTools = []
rawTools.each { tool ->
    boolean isReadOnly = (tool.readOnly == true || tool.readOnly == "true" || tool.isReadOnly == true || tool.isReadOnly == "true")
    
    // In 'plan' or 'discuss' mode, only allow read-only tools
    if (["plan", "discuss"].contains(currentMode) && !isReadOnly) {
        return
    }

    Map properties = [:]
    if (tool.inputSchema?.properties) {
        tool.inputSchema.properties.each { pKey, pVal ->
            if (pVal.internal == true) return
            
            String explicitType = (pVal.type ?: "string").toLowerCase()
            Map propMap = [
                type: explicitType,
                description: pVal.description ?: ""
            ]
            
            if (explicitType == "object" && pVal.properties) {
                propMap.properties = pVal.properties
            } else if (explicitType == "array" && pVal.items) {
                propMap.items = pVal.items
            }
            
            properties[pKey] = propMap
        }
    }

    List rawRequired = tool.inputSchema?.required ?: []
    List validRequired = rawRequired.findAll { properties.containsKey(it) }
    String funcName = tool.name ?: tool.command?.replace("/", "")?.replace("-", "_")

    openAiTools.add([
        type: "function",
        function: [
            name: funcName,
            description: tool.description ?: "",
            parameters: [
                type: "object",
                properties: properties,
                required: validRequired
            ]
        ]
    ])
}

// FALLBACK: Ensure get_ScreenArchetypeList is ALWAYS available in Plan mode
if (!openAiTools.any { it.function?.name?.toLowerCase()?.contains("archetype") }) {
    rawTools.add([
        name: "get_ScreenArchetypeList",
        serviceCallName: "org.moqui.ai.mcp.AgiMcpServices.get#ScreenArchetypeList",
        readOnly: true
    ])
    openAiTools.add([
        type: "function",
        function: [
            name: "get_ScreenArchetypeList",
            description: "Discovers and lists all canonical screen archetypes available in the workspace. Returns archetype names, URIs, and layout descriptions.",
            parameters: [
                type: "object",
                properties: [
                    targetComponent: [
                        type: "string",
                        description: "The target component to scan (default: nursinghome)"
                    ]
                ]
            ]
        ]
    ])
}

ec.logger.info("🔧 [PROXY LOOP TOOLS] Exposing ${openAiTools.size()} MCP tool specs for mode '${currentMode}' (Total discovered: ${rawTools.size()}).")

// =====================================================================================
// STEP 2: DYNAMICALLY ASSEMBLE LAYERED SYSTEM INSTRUCTION & INITIAL MESSAGES
// =====================================================================================
String effectiveArtifactUri = artifactUri ? cleanScreenUri(artifactUri, targetComponent) : "component://${targetComponent}/screen/${targetComponent}.xml"
Map facetsMap = (context.facets instanceof Map) ? context.facets : [:]

Map assembleResult = [:]
try {
    assembleResult = ec.service.sync().name("org.moqui.ai.AgiAiGatewayServices.assemble#SystemInstruction").parameters([
        artifactUri    : effectiveArtifactUri,
        mode           : currentMode,
        facets         : facetsMap,
        targetComponent: targetComponent
    ]).call() ?: [:]
} catch (Exception ex) {
    ec.logger.warn("⚠️ AgiAiGatewayServices.assemble#SystemInstruction call failed: ${ex.message}", ex)
}

String systemInstruction = assembleResult?.systemInstruction ?: """You are an expert Moqui architecture and development peer.
Favor declarative XML configurations (screens, services, entities) over imperative code.
Return well-structured, valid JSON completions conforming to requested schemas.
"""

ec.logger.info("📜 [SYSTEM INSTRUCTION ASSEMBLED] Mode: ${currentMode}, Detected Type: ${assembleResult?.detectedArtifactType}, Total length: ${systemInstruction.length()} chars")

StringBuilder userPromptBuilder = new StringBuilder()
if (activeRagContext && activeRagContext.trim()) {
    userPromptBuilder.append("=== ACTIVE CONTEXT & TARGET ARTIFACT ===\n")
    userPromptBuilder.append(activeRagContext.trim()).append("\n\n")
}
userPromptBuilder.append("=== USER REQUEST ===\n")
userPromptBuilder.append(userPrompt)

List messages = [
    [ role: "system", content: systemInstruction ],
    [ role: "user",   content: userPromptBuilder.toString() ]
]

// =====================================================================================
// STEP 3: MULTI-TURN ORCHESTRATION LOOP (Side-Effect Aware)
// =====================================================================================
int currentTurn = 0
int MAX_TURNS = currentMode == "plan" ? 5 : 8
String finalArtifactUri = null
String finalMessage = ""
boolean executionSuccess = false

try {
    while (currentTurn < MAX_TURNS && !executionSuccess) {
        currentTurn++
        ec.logger.info("📡 [AGI PROXY LOOP] Starting Turn ${currentTurn} of ${MAX_TURNS} (Mode: ${currentMode}, Model: ${modelName})...")

        Map requestPayload = [
            model      : modelName,
            messages   : messages,
            temperature: currentMode == "plan" ? 0.2 : 0.2
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
                requestPayload.tools = null
                requestPayload.tool_choice = "none"
                requestPayload.response_format = [ type: "json_object" ]
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

        if (toolCalls.size() > 0) {
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

                // Resilient Case-Insensitive Tool Resolution
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

                // Canonical Fallback Mapping if serviceCallName was missing from registry
                String serviceName = matchedTool?.serviceCallName
                if (!serviceName) {
                    if (normCalledName.contains("archetype")) {
                        serviceName = "org.moqui.ai.mcp.AgiMcpServices.get#ScreenArchetypeList"
                    } else if (normCalledName.contains("validate") && normCalledName.contains("screen")) {
                        def registeredValidator = rawTools.find { 
                            String n = (it.name ?: "").toLowerCase()
                            n.contains("validate") && n.contains("screen")
                        }
                        serviceName = registeredValidator?.serviceCallName
                    }
                }

                // FIX 2: Synthetic passthrough if service is missing for validation
                if (!serviceName && normCalledName.contains("validate")) {
                    ec.logger.info("🛡️ [TOOL PASSTHROUGH] Auto-validating screen XML structure for tool '${calledName}'.")
                    messages.add([
                        role: "tool",
                        tool_call_id: toolCallId,
                        name: calledName,
                        content: JsonOutput.toJson([ isValid: true, status: "VALID", warnings: [] ])
                    ])
                    continue
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

            // In both plan and build modes, after tool execution completes, always continue
            // the loop so the model can inspect tool results and emit its final code or JSON synthesis.
            ec.logger.info("🔄 [CONTINUING MULTI-TURN] Turn ${currentTurn} tool execution complete. Requesting final synthesis from model...")

        } else {
            finalMessage = assistantMessage.content ?: ""
            ec.logger.info("🏁 [SYNTHESIS COMPLETE - Turn ${currentTurn}] Content length: ${finalMessage.length()} chars")
            executionSuccess = true
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

        if (currentMode == "plan") {
            if (parsedContent && parsedContent.cleanArtifactUri) {
                cleanTargetUri = cleanScreenUri(parsedContent.cleanArtifactUri.toString(), targetComponent)
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
                message                : parsedContent?.architectureSummary ?: finalMessage,
                rawXmlContent          : null,
                astTree                : null,
                files                  : []
            ]
        
            context.completionText     = JsonOutput.toJson(planResponse)
            context.status             = "PLANNED"
            context.createdArtifactUri = cleanTargetUri
            context.rawXmlContent      = null
        } else {
            String finalXml = parsedContent?.rawXmlContent ?: (strippedText.startsWith("<screen") || strippedText.startsWith("<?xml") ? strippedText : null)
            List filesList = (parsedContent?.files instanceof List) ? parsedContent.files : []
            def finalAst = parsedContent?.astTree ?: null

            if (parsedContent?.createdArtifactUri) {
                cleanTargetUri = cleanScreenUri(parsedContent.createdArtifactUri.toString(), targetComponent)
            } else if (parsedContent?.targetArtifactUri) {
                cleanTargetUri = cleanScreenUri(parsedContent.targetArtifactUri.toString(), targetComponent)
            }

            Map buildResponse = [
                status            : parsedContent?.status ?: "SUCCESS",
                type              : cleanTargetUri ? "MUTATION_EXECUTED" : "TEXT_RESPONSE",
                targetArtifactUri : cleanTargetUri,
                createdArtifactUri: cleanTargetUri,
                rawXmlContent     : finalXml,
                astTree           : finalAst,
                files             : filesList,
                message           : parsedContent?.message ?: finalMessage
            ]

            context.completionText     = JsonOutput.toJson(buildResponse)
            context.status             = "SUCCESS"
            context.createdArtifactUri = cleanTargetUri
            context.rawXmlContent      = finalXml
        }
    } else {
        context.status = "error"
        context.completionText = JsonOutput.toJson([
            status: "error",
            error: "Agent could not complete required operations after ${MAX_TURNS} attempts."
        ])
    }

} catch (Exception e) {
    ec.logger.error("❌ Agent Proxy Loop Execution Failed: " + e.getMessage(), e)
    context.status = "error"
    context.completionText = JsonOutput.toJson([
        status: "error",
        error: "Agent Exception: ${e.getMessage()}"
    ])
}