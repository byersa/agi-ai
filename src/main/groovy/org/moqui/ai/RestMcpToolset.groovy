package org.moqui.ai

import com.google.adk.tools.BaseToolset
import com.google.adk.tools.BaseTool
import com.google.adk.agents.ReadonlyContext
import com.google.adk.tools.ToolContext
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.net.URI
import java.io.IOException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class RestMcpToolset implements BaseToolset {
    private final static Logger logger = LoggerFactory.getLogger(RestMcpToolset.class)
    private final String url
    private final Map<String, String> headers
    private final HttpClient httpClient

    RestMcpToolset(String url, Map<String, String> headers) {
        this.url = url
        this.headers = headers
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()
    }

    @Override
    Flowable<BaseTool> getTools(ReadonlyContext context) {
        try {
            var requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header('Accept', 'application/json')
                .GET()
            
            headers.each { k, v -> requestBuilder.header(k, v) }
            
            var request = requestBuilder.build()
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() != 200) {
                throw new IOException("Failed to list tools from REST endpoint: ${response.statusCode()} - ${response.body()}")
            }
            
            def json = new JsonSlurper().parseText(response.body())
            def toolsList = json.tools ?: json.result?.tools ?: []
            
            List<BaseTool> baseTools = []
            for (def t in toolsList) {
                String toolName = t.name
                String toolDesc = t.description
                Map inputSchema = t.inputSchema ?: [type: "object", properties: [:]]
                
                baseTools << new RestMcpTool(toolName, toolDesc, url, headers, httpClient, inputSchema)
            }
            
            return Flowable.fromIterable(baseTools)
        } catch (Exception e) {
            logger.error("Error loading tools from REST endpoint", e)
            return Flowable.error(e)
        }
    }

    @Override
    void close() {
        // Stateless, nothing to close
    }
}

class RestMcpTool extends BaseTool {
    private final String url
    private final Map<String, String> headers
    private final HttpClient httpClient
    private final Map inputSchema

    RestMcpTool(String name, String description, String url, Map<String, String> headers, HttpClient httpClient, Map inputSchema) {
        super(name, description)
        this.url = url
        this.headers = headers
        this.httpClient = httpClient
        this.inputSchema = inputSchema
    }

private static Map capitalizeSchemaTypes(Map schema) {
        if (!schema) return schema
        def newSchema = new LinkedHashMap(schema)
        if (newSchema.containsKey('type') && newSchema.get('type') instanceof String) {
            newSchema.put('type', ((String) newSchema.get('type')).toUpperCase())
        }
        if (newSchema.containsKey('properties') && newSchema.get('properties') instanceof Map) {
            def newProps = [:]
            ((Map) newSchema.get('properties')).each { k, v ->
                if (v instanceof Map) {
                    newProps.put(k, capitalizeSchemaTypes((Map) v))
                } else {
                    newProps.put(k, v)
                }
            }
            // 🎯 DIRECT JAVA METHOD CALL: Bypasses Groovy's read-only bean property parser check entirely
            newSchema.put('properties', newProps)
        }
        if (newSchema.containsKey('items') && newSchema.get('items') instanceof Map) {
            newSchema.put('items', capitalizeSchemaTypes((Map) newSchema.get('items')))
        }
        return newSchema
    }

    @Override
    Optional<com.google.genai.types.FunctionDeclaration> declaration() {
        Map capitalizedSchema = capitalizeSchemaTypes(inputSchema)
        def builder = com.google.genai.types.FunctionDeclaration.builder()
            .name(name())
            .description(description())
            .parametersJsonSchema(capitalizedSchema)
        return Optional.of(builder.build())
    }

    @Override
    Single<Map<String, Object>> runAsync(Map<String, Object> arguments, ToolContext context) {
        return Single.fromCallable({
            def requestBody = [
                name: name(),
                arguments: arguments
            ]
            def jsonPayload = JsonOutput.toJson(requestBody)
            
            var requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header('Content-Type', 'application/json')
                .header('Accept', 'application/json')
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                
            headers.each { k, v -> requestBuilder.header(k, v) }
            
            var request = requestBuilder.build()
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() != 200) {
                throw new IOException("Failed to execute tool ${name()} via REST endpoint: ${response.statusCode()} - ${response.body()}")
            }
            
            def json = new JsonSlurper().parseText(response.body())
            Map resultMap = json.result ?: json ?: [:]
            return resultMap
        })
    }
}
