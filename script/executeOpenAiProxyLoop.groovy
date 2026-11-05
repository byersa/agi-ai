package org.moqui.ai

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.moqui.context.ExecutionContext
import org.moqui.llm.LlmTool
import org.moqui.llm.LlmMessage
import org.moqui.llm.LlmResponse
import org.moqui.impl.llm.tools.BrowseTool
import org.moqui.ai.tools.WriteScreenXmlTool

// =====================================================================================
// STEP 0: CONTEXT & PROFILE RESOLUTION
// =====================================================================================
def ec = context.ec
String currentMode = (context.mode ?: "build").toLowerCase().trim()
String userPrompt = context.userPrompt
String targetComponent = context.targetComponent ?: "nursinghome"
String artifactUri = context.targetArtifactUri ?: context.artifactUri ?: "component://${targetComponent}/screen/${targetComponent}.xml"

String targetProfile = context.aiProfileName ?: (context.aiModelName?.contains("gemini") ? "gemini" : "ollama")
int maxTurns = currentMode == "plan" ? 5 : (currentMode == "build" ? 4 : 8)

ec.logger.info("🚀 [EC.LLM UNIFIED LOOP] mode: ${currentMode}, profile: ${targetProfile}, artifactUri: ${artifactUri}")

// Helper: Canonicalize Moqui screen component URIs
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

String effectiveArtifactUri = cleanScreenUri(artifactUri, targetComponent)

// =====================================================================================
// STEP 1: TOOL REGISTRATION (Native BrowseTool + Custom WriteScreenXmlTool + Dynamic MCP)
// =====================================================================================
List<LlmTool> activeTools = []

// Core discovery tool
activeTools.add(new BrowseTool())

// AGI-IDE screen persistence tool (active for build mode)
if (currentMode == "build") {
    activeTools.add(new WriteScreenXmlTool())
}

// Discover and register any declared MCP services
try {
    Map toolsResult = ec.service.sync().name("org.moqui.ai.AgiMcpBridgeServices.list#Tools").call() ?: [:]
    List rawTools = toolsResult.tools ?: toolsResult.toolsList ?: []
    rawTools.each { tool ->
        boolean isReadOnly = (tool.readOnly == true || tool.readOnly == "true" || tool.isReadOnly == true || tool.isReadOnly == "true")
        if (["plan", "discuss"].contains(currentMode) && !isReadOnly) return

        String funcName = tool.name ?: tool.command?.replace("/", "")?.replace("-", "_")
        if (tool.serviceCallName && ec.service.getServiceDefinition(tool.serviceCallName) != null) {
            try {
                activeTools.add(LlmTool.service(tool.serviceCallName, funcName))
            } catch (Exception ignore) {}
        }
    }
} catch (Exception te) {
    ec.logger.warn("⚠️ Could not dynamically discover MCP tools: ${te.message}")
}

ec.logger.info("🔧 [UNIFIED TOOLS] Registered ${activeTools.size()} LlmTool instances for mode '${currentMode}'")

// =====================================================================================
// STEP 2: ASSEMBLE SYSTEM INSTRUCTIONS & CONVERSATION HISTORY
// =====================================================================================
String systemInstruction = context.systemInstruction
if (!systemInstruction || !systemInstruction.trim()) {
    Map assembleResult = [:]
    try {
        assembleResult = ec.service.sync().name("org.moqui.ai.AgiAiGatewayServices.assemble#SystemInstruction").parameters([
            artifactUri    : effectiveArtifactUri,
            mode           : currentMode,
            facets         : context.facets ?: [:],
            targetComponent: targetComponent
        ]).call() ?: [:]
        systemInstruction = assembleResult.systemInstruction
    } catch (Exception ex) {
        ec.logger.warn("⚠️ Error assembling system instruction: ${ex.message}")
    }
}

if (!systemInstruction || !systemInstruction.trim()) {
    systemInstruction = """You are an expert Moqui framework architect and application developer.
Favor declarative XML configurations (screens, forms, transitions, entities) over imperative code.
When building or modifying screens, call the 'write_screen_xml' tool with the complete, valid Moqui screen XML.
"""
}

// =====================================================================================
// STEP 3: DISPATCH VIA ec.llm.client() & LlmAgentLoop
// =====================================================================================
boolean txSuspended = false
if (ec.transaction.isTransactionInPlace()) {
    txSuspended = ec.transaction.suspend()
}

try {
    def client = ec.llm.client(targetProfile)
        .system(systemInstruction)
        .maxIterations(maxTurns)
        .tools(activeTools)

    if (context.aiModelName) client.model(context.aiModelName)

    // Replay conversation history
    List history = (context.conversationHistory instanceof List) ? context.conversationHistory : []
    history.each { turn ->
        if (turn?.role == "assistant") {
            client.messages([LlmMessage.assistant(turn.content)])
        } else if (turn?.role == "user") {
            client.user(turn.content)
        }
    }

    // Append active user request
    client.user(userPrompt)

    long startTime = System.currentTimeMillis()
    LlmResponse resp = client.call()
    long duration = System.currentTimeMillis() - startTime

    String completionText = resp?.getContent() ?: ""
    ec.logger.info("🏁 [EC.LLM TURN COMPLETE] Status: ${resp?.finishReason}, Duration: ${duration}ms, Output: ${completionText.length()} chars")

    // =================================================================================
    // STEP 4: PACKAGE RESPONSE CONTRACT FOR AGI PIPELINE
    // =================================================================================
    String finalXml = null
    Map finalAst = null

    // Check if write_screen_xml tool was executed during this turn
    if (resp?.toolResults) {
        def xmlToolResult = resp.toolResults.find { it.name == "write_screen_xml" }
        if (xmlToolResult && xmlToolResult.content instanceof Map) {
            Map resMap = (Map) xmlToolResult.content
            if (resMap.status == "SUCCESS") {
                effectiveArtifactUri = resMap.artifactUri ?: effectiveArtifactUri
            }
        }
    }

    // Try reading disk state if build mode updated the screen file
    if (currentMode == "build") {
        try {
            def rr = ec.resource.getLocationReference(effectiveArtifactUri)
            if (rr != null && rr.exists) {
                finalXml = rr.getText()
            }
        } catch (Exception ignore) {}
    }

    if (currentMode == "plan") {
        Map planPayload = [
            status             : "PLANNED",
            type               : "PLAN_FORMULATION",
            targetArtifactUri  : effectiveArtifactUri,
            createdArtifactUri : effectiveArtifactUri,
            architectureSummary: completionText,
            message            : completionText
        ]

        // If completion is raw JSON, unpack structured fields
        if (completionText.trim().startsWith("{") && completionText.trim().endsWith("}")) {
            try {
                Map jsonMap = new JsonSlurper().parseText(completionText.trim()) as Map
                planPayload.putAll(jsonMap)
            } catch (Exception ignore) {}
        }

        context.completionText     = JsonOutput.toJson(planPayload)
        context.status             = "PLANNED"
        context.createdArtifactUri = effectiveArtifactUri
        context.rawXmlContent      = null
    } else {
        Map buildPayload = [
            status            : "SUCCESS",
            type              : "MUTATION_EXECUTED",
            targetArtifactUri : effectiveArtifactUri,
            createdArtifactUri: effectiveArtifactUri,
            rawXmlContent     : finalXml,
            astTree           : finalAst,
            message           : completionText ?: "Screen XML processed through write_screen_xml."
        ]

        context.completionText     = JsonOutput.toJson(buildPayload)
        context.status             = "SUCCESS"
        context.createdArtifactUri = effectiveArtifactUri
        context.rawXmlContent      = finalXml
    }

} catch (Exception ex) {
    Throwable root = ex
    while (root.getCause() != null && root != root.getCause()) {
        root = root.getCause()
    }
    String rootDetail = root.getMessage() ?: ex.getMessage() ?: "Unknown execution error"

    ec.logger.error("💥 [EC.LLM EXECUTION FAILED] Profile: ${targetProfile}, Error: ${rootDetail}", ex)
    context.status = "error"
    context.completionText = JsonOutput.toJson([
        status          : "error",
        errorCode       : "LLM_CALL_FAILED",
        profileName     : targetProfile,
        diagnosticDetail: rootDetail,
        error           : rootDetail,
        message         : "AI Turn failed: ${rootDetail}"
    ])
} finally {
    if (txSuspended) {
        ec.transaction.resume()
    }
}