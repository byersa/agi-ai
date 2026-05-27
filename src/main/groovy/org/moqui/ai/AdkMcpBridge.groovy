package org.moqui.ai

import com.google.adk.tools.Annotations.Schema
import com.google.adk.tools.FunctionTool
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class AdkMcpBridge {
    private static final Logger logger = LoggerFactory.getLogger(AdkMcpBridge.class)

    /**
     * Programmatically discovers active MCP tools from 'McpServices.list#Tools'
     * and compiles the registered tools array for the ADK LLM agent.
     */
    static List<FunctionTool> getTools() {
        logger.info("🌉 [ADK-MCP BRIDGE] Discovered tools from McpServices.list#Tools and registering FunctionTool bindings...")
        
        ExecutionContext ec = org.moqui.Moqui.getExecutionContext()
        if (ec) {
            try {
                def res = ec.service.sync().name("McpServices.list#Tools").call()
                def mcpTools = res?.result?.tools ?: []
                logger.info("📡 [ADK-MCP BRIDGE] Discovered ${mcpTools.size()} active tools from Ean's MCP catalog.")
                mcpTools.each { tool ->
                    logger.debug("   ↳ Discovered Tool: ${tool.name} - ${tool.description}")
                }
            } catch (Exception e) {
                logger.error("⚠️ Failed to execute Ean's MCP tool discovery service: ${e.message}")
            }
        } else {
            logger.warn("⚠️ No active execution context available during tool registration.")
        }

        // Statically map our annotated, type-safe adapter methods
        return [
            FunctionTool.create(AdkMcpBridge.class, "moqui_browse_screens"),
            FunctionTool.create(AdkMcpBridge.class, "moqui_search_screens"),
            FunctionTool.create(AdkMcpBridge.class, "moqui_get_screen_details"),
            FunctionTool.create(AdkMcpBridge.class, "moqui_get_help"),
            FunctionTool.create(AdkMcpBridge.class, "moqui_prompts_list"),
            FunctionTool.create(AdkMcpBridge.class, "moqui_prompts_get")
        ]
    }

    private static boolean isMutableAction(String toolName, Map arguments) {
        // Rule 1: moqui_browse_screens with action != null is mutable (e.g. form submit/transition)
        if (toolName == "moqui_browse_screens" && arguments?.containsKey("action") && arguments.action != null) {
            return true
        }
        
        // Rule 2: tool name contains mutation indicator keywords
        String lowerName = toolName.toLowerCase()
        if (lowerName.contains("create") || lowerName.contains("update") || 
            lowerName.contains("delete") || lowerName.contains("store") || 
            lowerName.contains("write")  || lowerName.contains("patch") || 
            lowerName.contains("execute")) {
            return true
        }

        return false
    }

    /**
     * Utility method to run the tools call backend execution of Ean's 'McpServices.mcp#ToolsCall'.
     */
    private static Map callMcpTool(String toolName, Map arguments) {
        ExecutionContext ec = org.moqui.Moqui.getExecutionContext()
        if (!ec) {
            logger.error("❌ No active ExecutionContext found for MCP tool call: ${toolName}")
            return [error: "No active ExecutionContext"]
        }
        
        // Check for Human-in-the-Loop Safeguard on mutable tools/services
        if (isMutableAction(toolName, arguments)) {
            Map approval = AgiWebSocketEndpoint.requestUserApproval(toolName, arguments)
            if (!approval.approved) {
                logger.warn("🛡️ [HITL SAFEGUARD] Aborting execution of mutable tool '${toolName}' due to human rejection.")
                return [
                    status: "REJECTED",
                    error: approval.error ?: "Transaction rejected by user (Human-in-the-loop protection)"
                ]
            }
        }

        logger.info("⚡ [ADK-MCP BRIDGE] Delegating tool '${toolName}' to Ean's MCP registry with arguments: ${arguments}")
        
        try {
            def result = ec.service.sync().name("McpServices.mcp#ToolsCall")
                .parameters([
                    name: toolName,
                    arguments: arguments ?: [:]
                ])
                .call()
            
            if (result?.containsKey('result')) {
                return (Map) result.result
            }
            return result ?: [:]
        } catch (Exception e) {
            logger.error("❌ Failed to execute Ean's MCP tool service wrapper for '${toolName}': ${e.message}", e)
            return [error: e.message]
        }
    }

    @Schema(description = "Browse Moqui screen hierarchy, process actions, and render screen content. Default renderMode is 'aria'.")
    static Map moqui_browse_screens(
            @Schema(name = "path", description = "Path to browse (e.g. 'PopCommerce')") String path,
            @Schema(name = "action", description = "Action to process before rendering: null (browse), 'submit' (form), 'create', 'update', or transition name") String action,
            @Schema(name = "renderMode", description = "Render mode: aria (default, accessibility tree), text, html") String renderMode,
            @Schema(name = "parameters", description = "Parameters to pass to screen during rendering or action") Map parameters) {
        
        Map args = [:]
        if (path != null) args.path = path
        if (action != null) args.action = action
        if (renderMode != null) args.renderMode = renderMode
        if (parameters != null) args.parameters = parameters
        
        Map result = callMcpTool("moqui_browse_screens", args)

        // Intercept and dispatch visual frame instantly down the active WebSocket connection
        try {
            ExecutionContext ec = org.moqui.Moqui.getExecutionContext()
            if (ec) {
                def wsSessionObj = ec.context.get("webSocketSession")
                if (wsSessionObj) {
                    jakarta.websocket.Session wsSession = (jakarta.websocket.Session) wsSessionObj
                    if (wsSession.isOpen()) {
                        Map frame = [
                            type: "visualFrame",
                            tool: "moqui_browse_screens",
                            componentId: ec.context.get("activeComponentId"),
                            data: result
                        ]
                        wsSession.getBasicRemote().sendText(new groovy.json.JsonBuilder(frame).toString())
                        logger.info("🎨 [ADK-MCP BRIDGE] Dispatched visual frame for moqui_browse_screens to WebSocket session: ${wsSession.id}")
                    }
                }
            }
        } catch (Exception ex) {
            logger.error("❌ Failed to transmit visual frame down WebSocket for moqui_browse_screens", ex)
        }

        return result
    }

    @Schema(description = "Search for screens by name to find their paths.")
    static Map moqui_search_screens(
            @Schema(name = "query", description = "Search query") String query) {
        
        return callMcpTool("moqui_search_screens", [query: query])
    }

    @Schema(description = "Get screen field details including dropdown options. Use this to understand available fields and their options before submitting forms.")
    static Map moqui_get_screen_details(
            @Schema(name = "path", description = "Screen path to analyze (e.g., 'PopCommerce/PopCommerceAdmin/Party/FindParty')") String path,
            @Schema(name = "fieldName", description = "Optional specific field name. If not provided, returns all fields.") String fieldName,
            @Schema(name = "parameters", description = "Optional parameters to set in context before rendering (for autocomplete contexts).") Map parameters) {
        
        Map args = [path: path]
        if (fieldName != null) args.fieldName = fieldName
        if (parameters != null) args.parameters = parameters
        
        return callMcpTool("moqui_get_screen_details", args)
    }

    @Schema(description = "Fetch extended documentation for a screen or service. Use URIs from 'describedby' fields in ARIA responses.")
    static Map moqui_get_help(
            @Schema(name = "uri", description = "Help URI (e.g., 'wiki:screen:EditProduct' or 'wiki:service:ProductFeature')") String uri) {
        
        return callMcpTool("moqui_get_help", [uri: uri])
    }

    @Schema(description = "List available MCP prompt templates.")
    static Map moqui_prompts_list() {
        return callMcpTool("moqui_prompts_list", [:])
    }

    @Schema(description = "Retrieve and render a specific MCP prompt template.")
    static Map moqui_prompts_get(
            @Schema(name = "name", description = "Prompt name") String name,
            @Schema(name = "arguments", description = "Arguments for prompt template") Map arguments) {
        
        Map args = [name: name]
        if (arguments != null) args.arguments = arguments
        
        return callMcpTool("moqui_prompts_get", args)
    }
}
