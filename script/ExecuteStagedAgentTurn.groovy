import groovy.json.JsonSlurper
import groovy.json.JsonOutput

// =============================================================================
// Helper: Recursive In-Place AST Node Mutation
// =============================================================================
def mutateNodeInTree(Map root, String targetName, Map newAttributes) {
    if (!root) return false

    // Check if current node matches target name or mariaId
    if (root.attributes?.name == targetName || root.name == targetName || root.mariaId?.endsWith(targetName)) {
        if (!root.attributes) root.attributes = [:]
        root.attributes.putAll(newAttributes)
        return true
    }

    // Recurse into children / widgets
    List children = root.children ?: root.widgets
    if (children instanceof List) {
        for (def child : children) {
            if (child instanceof Map) {
                boolean updated = mutateNodeInTree(child, targetName, newAttributes)
                if (updated) return true
            }
        }
    }
    return false
}

// =============================================================================
// 1. Direct MCP Tool Execution Path (if slash command tool invocation)
// =============================================================================
if (mcpTool && !mcpTool.trim().isEmpty()) {
    String cleanToolName = mcpTool.trim().replaceFirst("^/", "").replaceAll("/", "__").replaceAll("-", "_")
    ec.logger.info("🛠️ [ExecuteStagedAgentTurn] Direct MCP tool execution requested: ${cleanToolName}")

    try {
        Map callResult = ec.service.sync().name("org.moqui.ai.mcp.McpToolServices.call#Tool").parameters([
            name     : cleanToolName,
            arguments: mcpParams ?: [:]
        ]).call()

        if (Boolean.TRUE.equals(callResult.isError)) {
            context.status = "error"
            context.message = callResult.content ? callResult.content[0]?.text : "MCP tool execution returned an error."
            return
        }

        context.status = "SUCCESS"
        context.isDraft = false
        context.message = "Tool executed successfully: ${cleanToolName}"
        context.completionText = callResult.content ? callResult.content[0]?.text : ""
        context.createdArtifactUri = artifactUri
        return
    } catch (Exception toolEx) {
        ec.logger.error("❌ [ExecuteStagedAgentTurn] Failed tool call: ${toolEx.message}", toolEx)
        context.status = "error"
        context.message = "Failed executing tool ${cleanToolName}: ${toolEx.message}"
        return
    }
}

// =============================================================================
// 2. Normalize and Resolve Prompt Text
// =============================================================================
String effectivePrompt = userPrompt ?: originalPrompt ?: ""
if (adHocPrompt && adHocPrompt.trim()) {
    effectivePrompt += "\n\n### AD-HOC DIRECTIVES & CONSTRAINTS:\n" + adHocPrompt.trim()
}

// =============================================================================
// 3. Resolve Selected Archetype Resources via McpResourceServices
// =============================================================================
StringBuilder archetypeBuilder = new StringBuilder()
if (selectedArchetypes instanceof List && !selectedArchetypes.isEmpty()) {
    archetypeBuilder.append("\n### SELECTED CANONICAL ARCHETYPE BLUEPRINTS:\n")
    for (archUri in selectedArchetypes) {
        if (!archUri) continue
        try {
            Map resResult = ec.service.sync().name("org.moqui.ai.mcp.McpResourceServices.get#ResourceContent")
                .parameters([uri: archUri.toString()])
                .call()
            
            String archXml = resResult.contents ? resResult.contents[0]?.text : null
            if (archXml) {
                archetypeBuilder.append("<!-- Archetype Blueprint: ${archUri} -->\n")
                archetypeBuilder.append(archXml.trim())
                archetypeBuilder.append("\n\n")
            }
        } catch (Exception archEx) {
            ec.logger.warn("⚠️ [ExecuteStagedAgentTurn] Could not fetch archetype ${archUri}: ${archEx.message}")
        }
    }
}

// =============================================================================
// 4. Parse and Format Staged RAG Context & Intent Records
// =============================================================================
List resolvedRagItems = []
if (ragContext != null) {
    resolvedRagItems = ragContext
} else if (contextPayloadJson) {
    try {
        resolvedRagItems = new JsonSlurper().parseText(contextPayloadJson) as List
    } catch (Exception e) {
        ec.logger.warn("Could not parse contextPayloadJson: ${e.message}")
    }
}

StringBuilder ragBuilder = new StringBuilder()
if (resolvedRagItems) {
    ragBuilder.append("\n### STAGED RAG CONTEXT & DOMAIN KNOWLEDGE:\n")
    for (item in resolvedRagItems) {
        if (item instanceof Map && (item.enabled == null || item.enabled == true)) {
            ragBuilder.append("- [${item.category ?: 'GENERAL'}] ${item.title ?: ''}: ${item.snippet ?: ''}\n")
        }
    }
}

// Append Selected Intent Node IDs if present
if (selectedIntents) {
    ragBuilder.append("\n### ATTACHED INTENT WORK EFFORTS:\n")
    for (intentId in selectedIntents) {
        ragBuilder.append("- Intent Reference ID: ${intentId}\n")
    }
}

// =============================================================================
// 5. Assemble Composite Payload for AI Proxy
// =============================================================================
Map proxyParams = [
    userPrompt          : effectivePrompt,
    targetComponent     : targetComponent ?: 'nursinghome',
    focusCoordinate     : focusCoordinate ?: artifactUri,
    focusCoordinateArray: focusCoordinateArray ?: [],
    activeRagContext    : (activeRagContext ?: "") + archetypeBuilder.toString() + ragBuilder.toString(),
    moquiSessionToken   : ec.web?.sessionToken ?: ""
]
ec.logger.info("In ExecuteStagedAgentTurn, proxyParams: ${proxyParams}")

// =============================================================================
// 6. Invoke AI Gateway Proxy Service
// =============================================================================
Map proxyResult = ec.service.sync().name("org.moqui.ide.AgiMcpServices.run#OpenAiProxy").parameters(proxyParams).call()
ec.logger.info("In ExecuteStagedAgentTurn, proxyResult: ${proxyResult}")

if (proxyResult.error || proxyResult.status == "error") {
    context.status = "error"
    context.message = proxyResult.error ?: proxyResult.message ?: "AI Gateway execution error"
    return
}

// =============================================================================
// 7. Parse Completion Payload
// =============================================================================
def completion = proxyResult.completionText
def parsed = null
if (completion instanceof String) {
    try {
        parsed = new JsonSlurper().parseText(completion)
    } catch (Exception e) {
        parsed = [rawXmlContent: completion]
    }
} else if (completion instanceof Map) {
    parsed = completion
} else {
    parsed = proxyResult
}

List filesGenerated = []

// 8. Handle Batch File Manifest: { "files": [ { "artifactUri": "...", "content": "..." } ] }
if (parsed?.files instanceof List) {
    for (fileItem in parsed.files) {
        String fileUri = fileItem.artifactUri ?: fileItem.location
        String fileContent = fileItem.content ?: fileItem.rawXmlContent
        if (fileUri && fileContent != null) {
            ec.service.sync().name("org.moqui.ide.AgiWorkspaceServices.store#WorkspaceBuffer").parameters([
                artifactUri     : fileUri,
                rawXmlContent   : fileContent,
                userId          : ec.user?.userId ?: 'ANONYMOUS'
            ]).call()
            filesGenerated.add([artifactUri: fileUri, status: "BUFFERED_DRAFT"])
        }
    }
    context.status = "SUCCESS"
    context.isDraft = true
    context.message = "Successfully staged ${filesGenerated.size()} artifact files in buffer."
    context.createdArtifactUri = artifactUri
} else {

// 9. Handle Single Artifact Creation / Mutation
String finalUri = parsed?.createdArtifactUri ?: parsed?.targetArtifactUri ?: parsed?.targetScreenUri ?: parsed?.workspaceBuffer?.artifactUri ?: proxyResult.createdArtifactUri ?: proxyResult.targetArtifactUri ?: artifactUri
String finalXml = parsed?.rawXmlContent ?: proxyResult.rawXmlContent ?: parsed?.workspaceBuffer?.rawXmlContent
def finalAstTree = parsed?.astTree ?: parsed?.workspaceBuffer?.metaJsonBuffer ?: null

if (finalAstTree instanceof String && finalAstTree.trim().startsWith('{')) {
    try {
        finalAstTree = new JsonSlurper().parseText(finalAstTree)
    } catch (Exception ignored) {}
}

if (!finalXml && finalAstTree instanceof Map) {
    String targetElemName = focusCoordinate ? focusCoordinate.split('#').last() : null
    if (targetElemName && parsed?.nodeMutation instanceof Map) {
        mutateNodeInTree(finalAstTree as Map, targetElemName, parsed.nodeMutation as Map)
    }
}

String bufferJson = finalAstTree ? (finalAstTree instanceof String ? finalAstTree : JsonOutput.toJson(finalAstTree)) : null

if (finalUri && (finalXml || bufferJson)) {
    ec.service.sync().name("org.moqui.ide.AgiWorkspaceServices.store#WorkspaceBuffer").parameters([
        artifactUri     : finalUri,
        metaJsonBuffer  : bufferJson,
        rawXmlContent   : finalXml,
        userId          : ec.user?.userId ?: 'ANONYMOUS'
    ]).call()
    filesGenerated.add([artifactUri: finalUri, status: "BUFFERED_DRAFT"])

    // 🎯 Ensure AgiArtifact DB record exists so it appears in the Artifact Palette
    try {
        def existingArt = ec.entity.find("org.moqui.ai.AgiArtifact")
            .condition("artifactPath", finalUri)
            .one()
        if (!existingArt) {
            def newArt = ec.entity.makeValue("org.moqui.ai.AgiArtifact")
            newArt.set("artifactPath", finalUri)
            newArt.set("artifactTypeEnumId", "AatXmlScreen")
            newArt.set("artifactStatusEnumId", "AasDraft")
            newArt.set("totalIntentCount", 1)
            newArt.set("completedIntentCount", 0)
            newArt.set("completionPercentage", 0.0)
            newArt.setSequencedIdPrimary()
            newArt.create()
            ec.logger.info("📦 [ExecuteStagedAgentTurn] Auto-registered new AgiArtifact for: ${finalUri}")
        }
    } catch (Exception artEx) {
        ec.logger.warn("⚠️ [ExecuteStagedAgentTurn] Could not register AgiArtifact: ${artEx.message}")
    }
}

context.completionText     = (completion instanceof String) ? completion : JsonOutput.toJson(parsed)
context.createdArtifactUri = finalUri
context.rawXmlContent      = finalXml
context.mutatedTree        = finalAstTree
context.isDraft            = true
context.status             = "SUCCESS"
context.message            = parsed?.message ?: proxyResult.message ?: "Staged turn applied to workspace buffer (Unsaved Draft)."
}

context.filesGenerated = filesGenerated