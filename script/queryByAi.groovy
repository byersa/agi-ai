package org.moqui.ai

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// Resolve API environment and model defaults
String apiKey = context.aiApiKey ?: System.getProperty("AI_API_KEY") ?: System.getenv("AI_API_KEY") ?: System.getenv("GEMINI_API_KEY") ?: "ollama"
String endpointUrl = context.aiEndpointUrl ?: System.getProperty("AI_ENDPOINT_URL") ?: System.getenv("AI_ENDPOINT_URL") ?: "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
String modelName = context.aiModelName ?: System.getProperty("AI_MODEL_NAME") ?: System.getenv("AI_MODEL_NAME") ?: "gemini-3.7-flash"

String userPrompt = context.userPrompt
String systemInstruction = context.systemInstruction ?: "You are a helpful data assistant. Analyze the provided context and respond accurately to the user query."
List ragContextList = context.ragContextList ?: []
String responseFormat = context.responseFormat ?: "json"

// Assemble RAG Context block
StringBuilder contextBuilder = new StringBuilder()
if (ragContextList) {
    contextBuilder.append("\n=== RELEVANT CONTEXT & CANDIDATE DATA ===\n")
    ragContextList.eachWithIndex { item, idx ->
        if (item instanceof Map || item instanceof org.moqui.entity.EntityValue) {
            contextBuilder.append("[Record ${idx + 1}]: ").append(JsonOutput.toJson(item)).append("\n")
        } else {
            contextBuilder.append("[Item ${idx + 1}]: ").append(item.toString()).append("\n")
        }
    }
}

if ("json".equalsIgnoreCase(responseFormat) || "entity-list".equalsIgnoreCase(responseFormat)) {
    systemInstruction += "\nIMPORTANT: Your output MUST be a valid JSON array or object with no markdown fences, headers, or explanations."
}

List messages = [
    [role: "system", content: systemInstruction],
    [role: "user", content: "${contextBuilder.toString()}\n\nUser Query: ${userPrompt}"]
]

Map requestPayload = [
    model: modelName,
    messages: messages,
    temperature: context.temperature ?: 0.2
]

try {
    URL url = new URL(endpointUrl)
    HttpURLConnection conn = (HttpURLConnection) url.openConnection()
    conn.setRequestMethod("POST")
    conn.setRequestProperty("Content-Type", "application/json")
    if (apiKey && apiKey != "ollama") {
        conn.setRequestProperty("Authorization", "Bearer ${apiKey}")
    }
    conn.setConnectTimeout(30000)
    conn.setReadTimeout(90000)
    conn.setDoOutput(true)

    conn.outputStream.withWriter("UTF-8") { writer ->
        writer.write(JsonOutput.toJson(requestPayload))
    }

    int responseCode = conn.getResponseCode()
    String rawResponseBody = (responseCode == 200 ? conn.inputStream : conn.errorStream)?.text ?: ""

    if (responseCode != 200) {
        ec.logger.error("❌ [query#ByAi] LLM API Call Failed (${responseCode}): ${rawResponseBody}")
        context.status = "ERROR"
        context.response = "API error ${responseCode}: ${rawResponseBody}"
        return
    }

    Map apiResponse = new JsonSlurper().parseText(rawResponseBody) as Map
    String content = apiResponse?.choices?[0]?.message?.content ?: ""

    // Strip markdown JSON codeblocks if returned by the LLM
    String cleanContent = content.replaceAll(/(?s)^```(json)?\s*/, "").replaceAll(/(?s)\s*```$/, "").trim()

    context.response = cleanContent
    context.status = "SUCCESS"

    if ("json".equalsIgnoreCase(responseFormat) || "entity-list".equalsIgnoreCase(responseFormat)) {
        try {
            context.structuredResult = new JsonSlurper().parseText(cleanContent)
        } catch (Exception parseEx) {
            ec.logger.warn("⚠️ [query#ByAi] Could not parse LLM output as JSON: ${cleanContent}")
            context.structuredResult = cleanContent
        }
    } else {
        context.structuredResult = cleanContent
    }

} catch (Exception e) {
    ec.logger.error("❌ [query#ByAi] Execution exception: " + e.getMessage(), e)
    context.status = "ERROR"
    context.response = e.getMessage()
}