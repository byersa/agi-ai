package org.moqui.ai

import groovy.xml.XmlParser
import groovy.util.Node
import org.moqui.context.ExecutionContext
import org.moqui.resource.ResourceReference
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.LinkedBlockingQueue
import javax.cache.Cache

@CompileStatic
class ScreenEditorServices {
    private static final Logger logger = LoggerFactory.getLogger(ScreenEditorServices.class)

    /**
     * Statically compiled method that routes screen extraction directly through Moqui's
     * live runtime engine using the DeterministicVueRenderer.
     */
    static Map<String, Object> getScreenCanvasTree(String screenLocation, ExecutionContext ec) {
        if (!screenLocation) {
            throw new IllegalArgumentException("Screen location is required")
        }
        if (ec == null) {
            throw new IllegalArgumentException("ExecutionContext is required")
        }

        logger.info("🎬 [ScreenEditorServices] Live-extracting screen tree from location: ${screenLocation}")

        List<Map<String, Object>> blueprintChildren = new ArrayList<Map<String, Object>>()
        ec.context.put("blueprintChildren", blueprintChildren)

        StringWriter writer = new StringWriter()
        try {
            ec.screen.makeRender()
                .screenPath(screenLocation)
                .renderMode("qjson")
                .render(writer)
        } catch (Exception e) {
            logger.error("❌ Failed to live-render screen tree for location: ${screenLocation}", e)
            throw new RuntimeException("Failed to live-render screen canvas tree: " + e.getMessage(), e)
        }

        String jsonStr = writer.toString()
        if (!jsonStr) {
            throw new IllegalStateException("DeterministicVueRenderer produced empty output for: " + screenLocation)
        }

        try {
            Map<String, Object> blueprintMap = (Map<String, Object>) new groovy.json.JsonSlurper().parseText(jsonStr)
            List<Map<String, Object>> children = (List<Map<String, Object>>) blueprintMap.get("children") ?: []
            
            // To match the legacy canvas tree root map structure:
            return [
                "id": "root_layout_container",
                "type": "Container",
                "quasarComponent": "div",
                "layoutArgs": [ "class": "widgets-root-container" ],
                "attributes": [:],
                "children": children
            ]
        } catch (Exception e) {
            logger.error("❌ Failed to parse canvas tree JSON: ${jsonStr}", e)
            throw new RuntimeException("Failed to parse live canvas tree JSON: " + e.getMessage(), e)
        }
    }

    // =========================================================================
    // PHASE 4D: THE SERIALIZATION PHASE & REST ENDPOINT BACKENDS
    // =========================================================================

    /**
     * Statically compiled recursive method that translates dynamic JSON intermediate
     * nodes back to standard, clean declarative Moqui XML tags.
     */
    static String canvasTreeToXml(Map<String, Object> node, int indentLevel) {
        if (node == null) return ""
        
        String indent = "    " * indentLevel
        String type = node.get("type")?.toString()
        String quasarComponent = node.get("quasarComponent")?.toString()
        Map<String, Object> attributes = (Map<String, Object>) node.get("attributes") ?: [:]
        List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children") ?: []

        String tagName = ""
        Map<String, String> reverseTagNameMap = [
            'Container': 'container',
            'Form': 'form-single',
            'FormField': 'field',
            'TextInput': 'text-line',
            'SelectInput': 'drop-down',
            'Button': 'submit'
        ]

        if (type == "CustomWidget" && quasarComponent) {
            tagName = quasarComponent.startsWith("agi-") ? quasarComponent.substring(4) : quasarComponent
        } else if (type != null) {
            tagName = reverseTagNameMap.get(type) ?: "container"
        } else {
            tagName = "container"
        }

        if (node.get("id")?.toString() == "root_layout_container" || tagName == "widgets") {
            StringBuilder sb = new StringBuilder()
            children.each { child ->
                sb.append(canvasTreeToXml(child, indentLevel))
            }
            return sb.toString()
        }

        StringBuilder attrSb = new StringBuilder()
        
        String idVal = node.get("id")?.toString()
        if (idVal && !idVal.startsWith(tagName + "_")) {
            attrSb.append(" id=\"${idVal}\"")
        }

        switch (tagName) {
            case 'container':
                Map<String, Object> layoutArgs = (Map<String, Object>) node.get("layoutArgs") ?: [:]
                if (layoutArgs.containsKey("class")) {
                    attrSb.append(" style=\"${layoutArgs.get('class')}\"")
                }
                attributes.each { k, v ->
                    attrSb.append(" ${k}=\"${v}\"")
                }
                break

            case 'form-single':
                Map<String, Object> layoutArgs = (Map<String, Object>) node.get("layoutArgs") ?: [:]
                if (layoutArgs.containsKey("class")) {
                    attrSb.append(" style=\"${layoutArgs.get('class')}\"")
                }
                attributes.each { k, v ->
                    attrSb.append(" ${k}=\"${v}\"")
                }
                break

            case 'field':
                if (attributes.containsKey("label")) {
                    attrSb.append(" title=\"${attributes.get('label')}\"")
                }
                if (attributes.containsKey("name")) {
                    attrSb.append(" name=\"${attributes.get('name')}\"")
                }
                attributes.each { k, v ->
                    if (k != "label" && k != "name") {
                        attrSb.append(" ${k}=\"${v}\"")
                    }
                }
                break

            case 'text-line':
                attributes.each { k, v ->
                    if (k != "dense" && k != "outlined") {
                        attrSb.append(" ${k}=\"${v}\"")
                    }
                }
                break

            case 'drop-down':
                attributes.each { k, v ->
                    if (k != "dense" && k != "outlined" && k != "options" && k != "entityOptions") {
                        attrSb.append(" ${k}=\"${v}\"")
                    }
                }
                break

            case 'submit':
                if (attributes.containsKey("label")) {
                    attrSb.append(" text=\"${attributes.get('label')}\"")
                }
                attributes.each { k, v ->
                    if (k != "label" && k != "color" && k != "type" && k != "icon") {
                        attrSb.append(" ${k}=\"${v}\"")
                    }
                }
                break

            default:
                attributes.each { k, v ->
                    attrSb.append(" ${k}=\"${v}\"")
                }
                break
        }

        StringBuilder sb = new StringBuilder()
        sb.append("${indent}<${tagName}${attrSb.toString()}")

        boolean hasChildren = !children.isEmpty()
        boolean isDropdown = tagName == "drop-down"
        boolean hasDropdownOptions = isDropdown && (attributes.containsKey("options") || attributes.containsKey("entityOptions"))

        if (!hasChildren && !hasDropdownOptions) {
            sb.append("/>\n")
        } else {
            sb.append(">\n")
            if (isDropdown && hasDropdownOptions) {
                if (attributes.containsKey("options")) {
                    List<Object> opts = (List<Object>) attributes.get("options")
                    opts.each { opt ->
                        sb.append("${indent}    <option text=\"${opt}\"/>\n")
                    }
                }
                if (attributes.containsKey("entityOptions")) {
                    Map<String, Object> eo = (Map<String, Object>) attributes.get("entityOptions")
                    sb.append("${indent}    <entity-options entity-name=\"${eo.get('entity')}\" key-field-name=\"${eo.get('key')}\" text-template=\"${eo.get('text')}\"/>\n")
                }
            }
            children.each { child ->
                sb.append(canvasTreeToXml(child, indentLevel + 1))
            }
            sb.append("${indent}</${tagName}>\n")
        }
        return sb.toString()
    }

    /**
     * getBlueprint service mapping for REST API. Natively invokes the Moqui screen rendering engine
     * to compile the layout dynamic blueprint directly from live runtime metadata.
     */
    static Map<String, Object> getBlueprint(String componentName, String screenPath, ExecutionContext ec) {
        if (!componentName || !screenPath) {
            throw new IllegalArgumentException("componentName and screenPath are required")
        }
        if (ec == null) {
            throw new IllegalArgumentException("ExecutionContext is required")
        }

        String screenLoc = "component://${componentName}/screen/${screenPath}"
        if (!screenLoc.endsWith(".xml")) screenLoc += ".xml"

        logger.info("🎬 [ScreenEditorServices] Dynamically generating live blueprint for location: ${screenLoc}")

        // Retrieve optional parameters from active service context/stack
        String renderDepthVal = ec.context.get("renderDepth")?.toString()
        String renderTargetOnlyVal = ec.context.get("renderTargetOnly")?.toString()

        // Push variables into the active ExecutionContext so that DeterministicVueRenderer has them
        List<Map<String, Object>> blueprintChildren = new ArrayList<Map<String, Object>>()
        ec.context.put("blueprintChildren", blueprintChildren)

        if (ec.web != null) {
            if (renderDepthVal != null) {
                ec.web.requestParameters.put("renderDepth", renderDepthVal)
            }
            if (renderTargetOnlyVal != null) {
                ec.web.requestParameters.put("renderTargetOnly", renderTargetOnlyVal)
            }
        }

        StringWriter writer = new StringWriter()
        try {
            // Programmatically execute the Moqui screen renderer facade in qjson mode
            ec.screen.makeRender()
                .rootScreen(screenLoc)
                .renderMode("qjson")
                .render(writer)
        } catch (Exception e) {
            logger.error("❌ Failed to dynamically render screen blueprint for: ${screenLoc}", e)
            throw new RuntimeException("Failed to render screen blueprint: " + e.getMessage(), e)
        }

        // The DeterministicVueRenderer writes a complete, structured JSON blueprint string to the writer
        String jsonStr = writer.toString()
        if (!jsonStr) {
            throw new IllegalStateException("DeterministicVueRenderer produced empty output for: " + screenLoc)
        }

        try {
            Map<String, Object> blueprintMap = (Map<String, Object>) new groovy.json.JsonSlurper().parseText(jsonStr)
            return [
                blueprint: blueprintMap
            ]
        } catch (Exception e) {
            logger.error("❌ Failed to parse blueprint JSON output: ${jsonStr}", e)
            throw new RuntimeException("Failed to parse blueprint JSON: " + e.getMessage(), e)
        }
    }

    /**
     * Surgical saveBlueprint implementation that performs RMW (Read-Modify-Write)
     * updates on widgets blocks, preserving all action tags and outer-block comments.
     */
    static Map<String, Object> saveBlueprint(String componentName, String screenPath, Map<String, Object> blueprint, ExecutionContext ec) {
        if (!componentName || !screenPath || !blueprint) {
            throw new IllegalArgumentException("Parameters componentName, screenPath, and blueprint are required")
        }

        logger.info("💾 [ScreenEditorServices] Surgically saving screen tree: ${componentName}, path: ${screenPath}")

        String screenLoc = "component://${componentName}/screen/${screenPath}"
        if (!screenLoc.endsWith(".xml")) screenLoc += ".xml"

        ResourceReference rr = ec.resource.getLocationReference(screenLoc)
        if (rr == null) {
            throw new IllegalArgumentException("Could not resolve screen reference: " + screenLoc)
        }

        String finalXml = ""
        String originalText = rr.exists ? rr.getText() : ""

        if (originalText) {
            // Surgical Replacement Strategy: locate <widgets> and </widgets>
            java.util.regex.Matcher startMatcher = originalText =~ /(?s)<widgets\b[^>]*>/
            java.util.regex.Matcher endMatcher = originalText =~ /(?s)<\/widgets>/

            if (startMatcher.find() && endMatcher.find()) {
                int startPos = startMatcher.end()
                int endPos = endMatcher.start()

                String beforeWidgets = originalText.substring(0, startPos)
                String afterWidgets = originalText.substring(endPos)

                StringBuilder canvasSb = new StringBuilder()
                canvasSb.append("\n")
                List<Map<String, Object>> children = (List<Map<String, Object>>) blueprint.get("children") ?: []
                children.each { child ->
                    canvasSb.append(canvasTreeToXml(child, 2))
                }
                canvasSb.append("    ")

                finalXml = beforeWidgets + canvasSb.toString() + afterWidgets
            } else {
                // Fallback if tags not found (corrupt shell)
                finalXml = originalText
            }
        }

        if (!finalXml) {
            // Full Shell generation fallback if file does not exist
            StringBuilder xmlSb = new StringBuilder()
            xmlSb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            xmlSb.append("<screen xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
            xmlSb.append("        xsi:noNamespaceSchemaLocation=\"http://moqui.org/xsd/xml-screen-4.0.xsd\"\n")
            xmlSb.append("        require-authentication=\"false\">\n")
            xmlSb.append("    <widgets>\n")

            List<Map<String, Object>> children = (List<Map<String, Object>>) blueprint.get("children") ?: []
            children.each { child ->
                xmlSb.append(canvasTreeToXml(child, 2))
            }

            xmlSb.append("    </widgets>\n")
            xmlSb.append("</screen>\n")
            finalXml = xmlSb.toString()
        }

        try {
            rr.putText(finalXml)
            logger.info("✅ [ScreenEditorServices] Surgically saved shadow XML to: ${screenLoc}")
            
            // Asynchronously notify active SSE client listeners
            ec.service.async().name("org.moqui.ai.ScreenEditorServices.push#BlueprintUpdate")
                .parameters([componentName: componentName, screenPath: screenPath])
                .call()
                
            return [success: true, message: "Blueprint saved successfully to component://${componentName}/screen/${screenPath}"]
        } catch (Exception e) {
            logger.error("❌ [ScreenEditorServices] Failed to surgically write XML: ${screenLoc}", e)
            return [success: false, message: "Error saving blueprint: " + e.getMessage()]
        }
    }

    /**
     * registerClient service backend for SSE subscriptions. Drops request thread 
     * to Servlet AsyncContext and delegates loop execution safely.
     */
    static Map<String, Object> registerClient(String componentName, String screenPath, ExecutionContext ec) {
        def response = ec.web.response
        def request = ec.web.request
        
        response.setContentType("text/event-stream")
        response.setCharacterEncoding("UTF-8")
        response.setHeader("Cache-Control", "no-cache")
        response.setHeader("Connection", "keep-alive")
        response.setHeader("Access-Control-Allow-Origin", "*")

        String sessionId = ec.web.session.getId()
        
        Cache<String, Object> sseCache = ec.cache.getCache("blueprint-sse-listeners")
        
        def queue = new java.util.concurrent.LinkedBlockingQueue<String>()
        sseCache.put(sessionId, queue)
        
        def out = response.writer
        out.write("event: connected\ndata: {\"sessionId\":\"${sessionId}\"}\n\n")
        out.flush()
        response.flushBuffer()
        
        jakarta.servlet.AsyncContext asyncContext = null
        if (request.isAsyncSupported()) {
            asyncContext = request.startAsync()
            asyncContext.setTimeout(-1)
            
            // Delegate loop to container worker pool - avoids thread exhaustion
            asyncContext.start(new Runnable() {
                @Override
                void run() {
                    try {
                        int pings = 0
                        while (!Thread.currentThread().isInterrupted() && pings < 1200) {
                            String message = queue.poll(5, java.util.concurrent.TimeUnit.SECONDS)
                            if (message) {
                                out.write("event: update\ndata: ${message}\n\n")
                                out.flush()
                                response.flushBuffer()
                            } else {
                                out.write(": keep-alive\n\n")
                                out.flush()
                                response.flushBuffer()
                            }
                            pings++
                        }
                    } catch (Exception e) {
                        // ignore safe closures
                    } finally {
                        sseCache.remove(sessionId)
                        try { asyncContext.complete() } catch (Exception ignore) {}
                    }
                }
            })
        }
        return null
    }

    /**
     * pushBlueprintUpdate service that broadcasts a layout reload event to all SSE client queues.
     */
    static Map<String, Object> pushBlueprintUpdate(String componentName, String screenPath, ExecutionContext ec) {
        Cache<String, Object> sseCache = ec.cache.getCache("blueprint-sse-listeners")
        if (sseCache != null) {
            String updateMessage = groovy.json.JsonOutput.toJson([
                event: "update",
                screen: screenPath,
                component: componentName,
                timestamp: System.currentTimeMillis()
            ])
            int count = 0
            sseCache.each { Object entry ->
                def val = ((Map.Entry) entry).value
                if (val instanceof java.util.concurrent.LinkedBlockingQueue) {
                    ((java.util.concurrent.LinkedBlockingQueue) val).offer(updateMessage)
                    count++
                }
            }
            logger.info("Blueprint Broadcast: Pushed update to ${count} active listeners.")
        }
        return [:]
    }

    /**
     * getUiMacros service backend that returns the blueprint-driven tags schema definition.
     */
    static Map<String, Object> getUiMacros(ExecutionContext ec) {
        return [
            macros: [
                "container": [
                    attributes: ["id", "class"],
                    description: "Quasar-compatible Flex Grid container"
                ],
                "form-single": [
                    attributes: ["name", "transition"],
                    description: "Standard Moqui single form"
                ],
                "text-line": [
                    attributes: ["name", "size", "placeholder"],
                    description: "Standard text input field"
                ],
                "drop-down": [
                    attributes: ["name"],
                    description: "Standard dropdown selection field"
                ],
                "submit": [
                    attributes: ["text"],
                    description: "Form submission button"
                ],
                "discussion-tree": [
                    attributes: ["entity-name", "context-id", "title", "enable-replies"],
                    description: "Custom AGI discussion tree widget"
                ]
            ]
        ]
    }
}
