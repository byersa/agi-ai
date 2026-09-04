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

// =====================================================================================
// STEP 1: DYNAMIC MCP TOOLS DISCOVERY WITH SIDE-EFFECT FILTERING
// =====================================================================================
Map toolsResult = ec.service.sync().name("org.moqui.ai.AgiMcpBridgeServices.list#Tools").call()
List rawTools = toolsResult.tools ?: toolsResult.toolsList ?: []

List openAiTools = []
rawTools.each { tool ->
    boolean isReadOnly = tool.readOnly == true

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
ec.logger.info("🔧 [PROXY LOOP TOOLS] Exposing ${openAiTools.size()} MCP tool specs for mode '${currentMode}' (Total discovered: ${rawTools.size()}).")

// =====================================================================================
// STEP 2: DYNAMICALLY ASSEMBLE LAYERED SYSTEM INSTRUCTION & INITIAL MESSAGES
// =====================================================================================
String effectiveArtifactUri = artifactUri ? cleanScreenUri(artifactUri, targetComponent) : "component://${targetComponent}/screen/${targetComponent}.xml"
Map facetsMap = [:]
if (context.facets instanceof Map) {
    facetsMap = context.facets
}

Map assembleResult = ec.service.sync().name("org.moqui.ai.mcp.McpPayloadServices.assemble#SystemInstruction").parameters([
    artifactUri    : effectiveArtifactUri,
    mode           : currentMode,
    facets         : facetsMap,
    targetComponent: targetComponent
]).call()

String systemInstruction = assembleResult.systemInstruction ?: """You are an expert software engineer and architectural peer specializing in the Moqui Ecosystem.
Favor XML configuration, screen definitions, and declarative entity models over imperative code.
Always emit standard W3C XML attributes without leading @ characters (e.g. name="...", not @name="...").
Place all screen <parameter> tags directly under <screen>, preceding <actions> and <widgets>.
"""

if (currentMode == "plan") {
    systemInstruction += """\n
### MANDATORY OUTPUT FORMAT FOR PLAN MODE:
You are acting strictly as an Architectural Formulator. You must return your final response as a valid JSON completion object matching this schema:
{
  "status": "PLANNED",
  "cleanArtifactUri": "component://${targetComponent}/screen/${targetComponent}/[CleanSubdirectory]/[ScreenName].xml",
  "recommendedArchetype": "lookup-modal | master-detail | single-form | blank",
  "suggestedEntities": [
    "mantle.party.Party",
    "mantle.party.Person"
  ],
  "architectureSummary": "High-level architectural design, HIPAA encryption rules, and UDM extensions...",
  "formulationSteps": [
    "1. Declare screen parameters...",
    "2. Prepare entity-find-one actions for Person...",
    "3. Structure read-only summary card...",
    "4. Add form-lists for prescriptions and allergies..."
  ]
}
Ensure 'cleanArtifactUri' contains NO repeated 'screen/screen' segments.
"""
}

ec.logger.info("📜 [SYSTEM INSTRUCTION ASSEMBLED] Mode: ${currentMode}, Detected Type: ${assembleResult.detectedArtifactType}, Total length: ${systemInstruction.length()} chars")

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
int MAX_TURNS = currentMode == "plan" ? 3 : 6
String finalArtifactUri = null
String finalMessage = ""
boolean executionSuccess = false

try {
    while (currentTurn < MAX_TURNS && !executionSuccess) {
        currentTurn++
        ec.logger.info("📡 [AGI PROXY LOOP] Starting Turn ${currentTurn} of ${MAX_TURNS} (Mode: ${currentMode}, Model: ${modelName})...")

        Map requestPayload = [
            model: modelName,
            messages: messages,
            temperature: currentMode == "plan" ? 0.3 : 0.2
        ]
        if (openAiTools.size() > 0) {
            requestPayload.tools = openAiTools
            requestPayload.tool_choice = "auto"
        }

        // 3.1 HTTP POST to Model Endpoint
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
            boolean onlyReadOnlyTools = true

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

                def matchedTool = rawTools.find { t ->
                    t.name == calledName || 
                    t.serviceCallName == calledName ||
                    (t.command && t.command.replace("/", "").replace("-", "_") == calledName)
                }

                boolean isToolReadOnly = matchedTool?.readOnly == true
                if (!isToolReadOnly) {
                    onlyReadOnlyTools = false
                }

                ec.logger.info("🚀 [AGENT TOOL EXECUTION - Turn ${currentTurn}] Model called [${calledName}] (readOnly: ${isToolReadOnly}) with args: ${toolArgs}")

                if (!matchedTool || !matchedTool.serviceCallName) {
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

                String serviceName = matchedTool.serviceCallName
                if (!toolArgs.targetComponent) toolArgs.targetComponent = targetComponent

                // Clean & normalize URIs in arguments
                if (toolArgs.artifactUri) {
                    toolArgs.artifactUri = cleanScreenUri(toolArgs.artifactUri.toString(), targetComponent)
                } else if (artifactUri) {
                    toolArgs.artifactUri = cleanScreenUri(artifactUri, targetComponent)
                }

                if (serviceName == "McpServices.mcp#ToolsCall") {
                    toolArgs.name = calledName
                }
                ec.logger.info("🔧 [HARNESS CALL] Invoking ${serviceName} with: ${toolArgs}")

                Map toolResult = [:]
                boolean executionFailed = false
                String caughtExceptionMsg = null

                try {
                    ec.transaction.runRequireNew(0, "Executing isolated agent tool ${calledName}", {
                        toolResult = ec.service.sync().name(serviceName).parameters(toolArgs).call()
                        if (ec.message.hasError()) {
                            executionFailed = true
                        }
                    })
                } catch (Exception ex) {
                    executionFailed = true
                    caughtExceptionMsg = ex.message
                    ec.logger.warn("⚠️ Exception during isolated tool execution: ${ex.message}", ex)
                }

                if (executionFailed || ec.message.hasError() || toolResult?.status == "error") {
                    String serviceErrors = ec.message.getErrorsString() ?: caughtExceptionMsg ?: toolResult?.error ?: "Unknown tool execution error"
                    ec.message.clearAll()
                    turnHadErrors = true

                    ec.logger.warn("⚠️ [TOOL ERROR - Turn ${currentTurn}] ${serviceName} failed: ${serviceErrors}")

                    messages.add([
                        role: "tool",
                        tool_call_id: toolCallId,
                        name: calledName,
                        content: JsonOutput.toJson([
                            status: "error",
                            error: serviceErrors,
                            message: "Tool '${calledName}' execution failed: ${serviceErrors}. Check file paths or parameters and try again."
                        ])
                    ])
                } else {
                    if (toolResult?.artifactUri) {
                        finalArtifactUri = cleanScreenUri(toolResult.artifactUri.toString(), targetComponent)
                    } else if (toolResult?.targetArtifactUri) {
                        finalArtifactUri = cleanScreenUri(toolResult.targetArtifactUri.toString(), targetComponent)
                    } else if (toolArgs.artifactUri && !isToolReadOnly) {
                        finalArtifactUri = cleanScreenUri(toolArgs.artifactUri.toString(), targetComponent)
                    }

                    ec.logger.info("✅ [TOOL SUCCESS - Turn ${currentTurn}] ${serviceName} returned: ${toolResult?.keySet()}")

                    messages.add([
                        role: "tool",
                        tool_call_id: toolCallId,
                        name: calledName,
                        content: JsonOutput.toJson([
                            status: "success",
                            result: toolResult ?: [:]
                        ])
                    ])
                }
            }

            if (!turnHadErrors && !onlyReadOnlyTools) {
                executionSuccess = true
                finalMessage = "Successfully executed dynamic tool sequence."
            } else if (onlyReadOnlyTools && !turnHadErrors) {
                ec.logger.info("🔄 [CONTINUING MULTI-TURN] Turn ${currentTurn} read-only inspection complete. Requesting formulation synthesis from model...")
            } else {
                ec.logger.info("🔄 [SELF-HEALING RE-PROMPT] Feeding error response back to Model for Turn ${currentTurn + 1}...")
            }

        } else {
            finalMessage = assistantMessage.content ?: "Prompt processed with no direct tool calls."
            executionSuccess = true
        }
    }

    // =================================================================================
    // STEP 4: FINALIZE RESPONSE & MAP TO ESAT PROTOCOL
    // =================================================================================
    if (executionSuccess) {
        String cleanTargetUri = cleanScreenUri(finalArtifactUri ?: artifactUri, targetComponent)
        String strippedText = stripMarkdownFences(finalMessage)

        // Try to parse model text as JSON completion envelope
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
                status              : "PLANNED",
                type                : "PLAN_FORMULATION",
                targetArtifactUri   : cleanTargetUri,
                createdArtifactUri  : cleanTargetUri,
                recommendedArchetype: parsedContent?.recommendedArchetype ?: "master-detail",
                suggestedEntities  : parsedContent?.suggestedEntities ?: ["mantle.party.Person"],
                architectureSummary : parsedContent?.architectureSummary ?: finalMessage,
                formulationSteps    : parsedContent?.formulationSteps ?: [],
                message             : parsedContent?.architectureSummary ?: finalMessage,
                rawXmlContent       : null,
                astTree             : null,
                files               : []
            ]

            context.completionText     = JsonOutput.toJson(planResponse)
            context.status             = "PLANNED"
            context.createdArtifactUri = cleanTargetUri
            context.rawXmlContent      = null
        } else {
            // BUILD / MUTATION MODE:
            // Propagate rawXmlContent, files, and astTree cleanly to ESAT
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