package org.moqui.ai

import org.moqui.context.ExecutionContext
import org.moqui.util.RestClient

ExecutionContext ec = context.ec

String apiKey = getGeminiApiKey(ec)
if (!apiKey) {
    throw new IllegalArgumentException("GEMINI_API_KEY is not configured! Google Gemini API requests will fail.")
}

String modelName = getGeminiModel(ec)
String promptText = context.promptText
String componentId = context.componentId ?: "facility-alerts"

// Get base system instruction
String baseInstruction = "You are the Automation Groups International (AGI) Platform Kernel.\n" +
    "You operate via strict declarative parameters. You communicate safely and deterministically.\n"

// Load grounding instructions from SKILLS.md dynamically
String skillInstructions = ""
try {
    // 1. Check in agi-host component
    def skillRef = ec.resource.getLocationReference("component://agi-host/skills/${componentId}/SKILLS.md")
    if (skillRef && skillRef.exists()) {
        skillInstructions = skillRef.getText()
        ec.logger.info("🧠 [AGI-AI GEMINI] Grounding request with rules for component: ${componentId}")
    } else {
        // 2. Check in agi-ai component (future-proof staging area)
        skillRef = ec.resource.getLocationReference("component://agi-ai/skills/${componentId}/SKILLS.md")
        if (skillRef && skillRef.exists()) {
            skillInstructions = skillRef.getText()
            ec.logger.info("🧠 [AGI-AI GEMINI] Grounding request with rules for component: ${componentId}")
        }
    }
} catch (Exception e) {
    ec.logger.warn("Could not load SKILLS.md for component ${componentId}: ${e.getMessage()}")
}

String fullPromptText = promptText
if (skillInstructions) {
    fullPromptText = "[CRITICAL CONSTRAINTS FOR THIS REQUEST]:\n${skillInstructions}\n\n[USER INPUT]:\n${promptText}"
}

// Build the request payload matching the Gemini Developer API specifications
Map payload = [
    contents: [
        [
            role: "user",
            parts: [
                [ text: fullPromptText ]
            ]
        ]
    ],
    systemInstruction: [
        parts: [
            [ text: baseInstruction ]
        ]
    ],
    generationConfig: [
        temperature: 0.2
    ]
]

String uriStr = "https://generativelanguage.googleapis.com/v1beta/models/${modelName}:generateContent?key=${apiKey}"

ec.logger.info("📡 [AGI-AI GEMINI] Transmitting request to model: ${modelName}...")

RestClient client = new RestClient()
client.uri(uriStr)
client.method(RestClient.POST)
client.contentType("application/json")
client.jsonObject(payload)

RestClient.RestResponse response = client.call()

if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
    ec.logger.error("❌ [AGI-AI GEMINI ERROR] API call failed with status ${response.getStatusCode()}: ${response.text()}")
    throw new RuntimeException("Google Gemini API call failed with status ${response.getStatusCode()}: ${response.text()}")
}

Map responseMap = (Map) response.jsonObject()
String responseText = ""
if (responseMap && responseMap.candidates && responseMap.candidates.size() > 0) {
    def candidate = responseMap.candidates[0]
    if (candidate.content && candidate.content.parts && candidate.content.parts.size() > 0) {
        responseText = (String) candidate.content.parts[0].text
    }
}

context.responseText = responseText

// --- Static Configuration Helper Methods ---

static String getGeminiApiKey(ExecutionContext ec) {
    String key = System.getenv("GEMINI_API_KEY") ?: System.getProperty("GEMINI_API_KEY")
    if (key) return key

    // Dynamic Hierarchical Search in local .env files
    List<File> envFiles = [
        new File(ec.factory.runtimePath, ".env"),
        new File(ec.factory.runtimePath, "../.env")
    ]
    for (File envFile in envFiles) {
        if (envFile.exists()) {
            for (String line : envFile.readLines()) {
                line = line.trim()
                if (line.startsWith("GEMINI_API_KEY=")) {
                    key = line.substring("GEMINI_API_KEY=".length()).trim()
                    if (key.startsWith('"') && key.endsWith('"')) key = key.substring(1, key.length() - 1)
                    if (key.startsWith("'") && key.endsWith("'")) key = key.substring(1, key.length() - 1)
                    if (key) return key
                }
            }
        }
    }
    return null
}

static String getGeminiModel(ExecutionContext ec) {
    String model = System.getenv("GEMINI_MODEL") ?: System.getProperty("GEMINI_MODEL")
    if (model) return model
    
    // Dynamic Hierarchical Search in local .env files
    List<File> envFiles = [
        new File(ec.factory.runtimePath, ".env"),
        new File(ec.factory.runtimePath, "../.env")
    ]
    for (File envFile in envFiles) {
        if (envFile.exists()) {
            for (String line : envFile.readLines()) {
                line = line.trim()
                if (line.startsWith("GEMINI_MODEL=")) {
                    model = line.substring("GEMINI_MODEL=".length()).trim()
                    if (model.startsWith('"') && model.endsWith('"')) model = model.substring(1, model.length() - 1)
                    if (model.startsWith("'") && model.endsWith("'")) model = model.substring(1, model.length() - 1)
                    if (model) return model
                }
            }
        }
    }
    return "gemini-1.5-pro" // High-performance default
}
