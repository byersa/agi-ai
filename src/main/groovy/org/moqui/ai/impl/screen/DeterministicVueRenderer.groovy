package org.moqui.ai.impl.screen

import org.moqui.impl.screen.ScreenRenderImpl
import org.moqui.impl.screen.ScreenWidgetRender
import org.moqui.impl.screen.ScreenWidgets
import org.moqui.impl.screen.ScreenForm
import org.moqui.impl.screen.ScreenForm.FormInstance
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.impl.entity.EntityDefinition
import org.moqui.util.MNode
import org.moqui.util.ContextStack
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import groovy.json.JsonBuilder
import groovy.transform.CompileStatic

@CompileStatic
class DeterministicVueRenderer implements ScreenWidgetRender {
    protected final static Logger logger = LoggerFactory.getLogger(DeterministicVueRenderer.class)

    @Override
    void render(ScreenWidgets widgets, ScreenRenderImpl sri) {
        // Use a consistent key and don't reset if already present (inherited from parent screen)
        List currentChildren = (List) sri.ec.context.get("blueprintChildren")
        boolean isRoot = false
        if (currentChildren == null) {
            isRoot = true
            currentChildren = []
            sri.ec.context.put("blueprintChildren", currentChildren)
        }

        boolean alreadyDisabled = sri.ec.artifactExecution.disableAuthz()
        try {
            // AMB 2026-03-10: Support discrete rendering depth
            // Default to 0 (target level only) for JSON blueprints to avoid recursive redundancy
            if (isRoot) {
                String depthStr = sri.ec.web.requestParameters.renderDepth
                int renderDepth = (depthStr ?: "0").toInteger()
                sri.ec.context.put("blueprintRenderDepth", renderDepth)
                sri.ec.context.put("blueprintExtraDepth", 0)
                sri.ec.context.put("blueprintPathIndex", 0)
                if (logger.isInfoEnabled()) logger.info("DeterministicVueRenderer: Starting root render with renderDepth: ${renderDepth}")
            }

            // AMB 2026-03-09: Surgery for partial rendering (dialogs)
            // Use getRequestParameters() for safe access
            boolean targetOnly = "true".equals(sri.ec.web.requestParameters.renderTargetOnly)
            sri.ec.context.put("renderTargetOnly", targetOnly)

            if (logger.isInfoEnabled()) logger.info("DeterministicVueRenderer.render() for ${widgets.getLocation()}, isRoot: ${isRoot}, baseChildrenCount: ${currentChildren.size()}, targetOnly: ${targetOnly}, hasNext: ${sri.getActiveScreenHasNext()}")
            
            if (targetOnly && sri.getActiveScreenHasNext()) {
                if (logger.isInfoEnabled()) logger.info("DeterministicVueRenderer.render() - Skipping parent screen ${widgets.getLocation()} because renderTargetOnly=true")
                sri.renderSubscreen()
                return
            }
            
            MNode widgetsNode = widgets.getWidgetsNode()
            if (widgetsNode == null) {
                if (logger.isInfoEnabled()) logger.info("DeterministicVueRenderer.render() - No widgets node for ${widgets.getLocation()}")
                return
            }

            walkWidgets(widgetsNode, currentChildren, sri, 0)

            // Force SubscreensActive node if there are more screens in the path list
            String renderedKey = "SubscreensActiveRendered_${sri.getActiveScreenDef().getLocation()}"
            if (sri.getActiveScreenHasNext() && sri.ec.context.get(renderedKey) != true) {
                handleSubscreensActive(null, currentChildren, sri, 0)
            }
        } catch (Throwable t) {
            logger.error("❌ Blueprint Extraction Failed", t)
            throw t
        } finally {
            String renderedKey = "SubscreensActiveRendered_${sri.getActiveScreenDef().getLocation()}"
            sri.ec.context.remove(renderedKey)
            if (!alreadyDisabled) sri.ec.artifactExecution.enableAuthz()
            
            if (isRoot) {
                Map<String, Object> blueprint = [
                    "@context": "https://moqui.ai/contexts/ui",
                    "@type": "ScreenBlueprint",
                    "location": widgets.getLocation(),
                    "children": currentChildren
                ]
                if (logger.isInfoEnabled()) logger.info("DeterministicVueRenderer produced blueprint with ${currentChildren.size()} root nodes")
                sri.getWriter().write(new JsonBuilder(blueprint).toPrettyString())
                sri.ec.context.remove("blueprintChildren")
            }
        }
    }

    protected void walkWidgets(MNode parentNode, List children, ScreenRenderImpl sri, int depth = 0, MNode fieldNode = null) {
        for (MNode child in parentNode.getChildren()) {
            if (logger.isTraceEnabled()) logger.info("${'  ' * depth}walkWidgets [${depth}]: handling node ${child.getName()}")
            handleNode(child, children, sri, depth + 1, fieldNode)
        }
    }

    protected void handleNode(MNode node, List children, ScreenRenderImpl sri, int depth = 0, MNode fieldNode = null) {
        String nodeCond = node.attribute("condition")
        if (nodeCond?.trim()) {
            boolean condPassed = false
            try {
                condPassed = sri.ec.resource.condition(nodeCond, "")
            } catch (Exception e) {
                logger.warn("Condition error: '${nodeCond}' for node ${node.getName()}: ${e.message}")
            }
            if (!condPassed) {
                if (logger.isTraceEnabled()) logger.trace("${'  ' * depth}Skipped node ${node.getName()} due to condition: ${nodeCond}")
                return
            }
        }
        String name = node.getName()
        switch(name) {
            case "screen":
            case "widgets":
            case "fail-widgets":
                walkWidgets(node, children, sri, depth)
                break
            case "form-single":
                handleFormSingle(node, children, sri, depth)
                break
            case "form-list":
                handleFormList(node, children, sri, depth)
                break
            case "section":
                handleSection(node, children, sri, depth)
                break
            case "form-query":
                Map<String, Object> fqMap = [
                    "@type": "m-form-query",
                    "attributes": evaluateAttributes(node, sri),
                    "children": []
                ]
                walkWidgets(node, (List) fqMap.children, sri, depth)
                children.add(fqMap)
                break
            case "form-query-field":
                Map<String, Object> fqfMap = [
                    "@type": "m-form-query-field",
                    "attributes": (Map<String, Object>) evaluateAttributes(node, sri)
                ]
                
                String enumTypeId = node.attribute("enum-type-id")
                String statusTypeId = node.attribute("status-type-id")
                String optionsUrl = node.attribute("options-url")
                String optionsParameters = node.attribute("options-parameters")

                Map<String, Object> fqfAttrs = (Map<String, Object>) fqfMap.attributes
                if (optionsUrl) {
                    fqfAttrs.type = "drop-down"
                    fqfAttrs["options-url"] = sri.makeUrlByType(optionsUrl, "transition", node, "true").getPath()
                    fqfAttrs["options-load-init"] = "true"
                    if (optionsParameters) {
                        try {
                            String expanded = sri.ec.resource.expand(optionsParameters, "")
                            fqfAttrs["options-parameters"] = new groovy.json.JsonSlurper().parseText(expanded)
                        } catch (Exception e) {
                            fqfAttrs["options-parameters"] = optionsParameters
                        }
                    }
                } else if (enumTypeId || statusTypeId) {
                    fqfAttrs.type = "drop-down"
                    // Use the correct transition based on enum-type-id vs status-type-id
                    // Use absolute path since these transitions are in the root aitree.xml screen
                    String transitionName = enumTypeId ? "getEnumerations" : "getStatusItems"
                    fqfAttrs["options-url"] = "/aitree/" + transitionName
                    fqfAttrs["options-load-init"] = "true"
                    Map<String, String> ops = [:]
                    if (enumTypeId) ops.enumTypeId = sri.ec.resource.expand(enumTypeId, "")
                    if (statusTypeId) ops.statusTypeId = sri.ec.resource.expand(statusTypeId, "")
                    fqfAttrs["options-parameters"] = ops
                } else {
                    // Check for related entity fields like orgId -> Organization
                    String fieldName = node.attribute("name")
                    if (fieldName == "orgId") {
                        fqfAttrs.type = "drop-down"
                        fqfAttrs["options-url"] = "/aitree/getOrganizations"
                        fqfAttrs["options-load-init"] = "true"
                        fqfAttrs["options-parameters"] = [:]
                    }
                }
                children.add(fqfMap)
                break
            case "dynamic-dialog":
                Map<String, Object> ddMap = [
                    "@type": "m-dynamic-dialog",
                    "attributes": evaluateAttributes(node, sri),
                    "children": []
                ]
                String trans = node.attribute("transition") ?: node.attribute("url")
                String urlType = node.attribute("url-type") ?: "transition"
                Map<String, Object> ddAttrs = (Map<String, Object>) ddMap.attributes
                if (trans) {
                    org.moqui.impl.screen.ScreenUrlInfo.UrlInstance ui = sri.makeUrlByType(trans, urlType, node, "true")
                    // AMB 2026-03-11: Manually force parameter resolution to ensure they are in the URL
                    Map<String, String> params = [:]
                    node.children("parameter").each { MNode param ->
                        String pName = param.attribute("name")
                        String value = param.attribute("value")
                        String from = param.attribute("from")
                        Object val = value ? sri.ec.resource.expand(value, "") : (from ? sri.ec.context.get(from) : null)
                        if (val != null) params.put(pName, val.toString())
                    }
                    // Use getPath() for a relative URL and manually append params to be safe with moqui.loadComponent
                    String url = ui.getPath()
                    if (params) {
                        url += "?" + params.collect { String k, String v -> "${k}=${URLEncoder.encode(v, 'UTF-8')}" }.join("&")
                    }
                    ddAttrs.url = url
                }
                // AMB 2026-03-19: Skip child parameter nodes since we build them into the URL
                walkWidgets(node, (List) ddMap.children, sri, depth, fieldNode)
                ((List) ddMap.children).removeAll { Object it -> ((Map) it)["@type"] == "parameter" }
                children.add(ddMap)
                break
            case "parameter":
                Map<String, Object> paramMap = [
                    "@type": "parameter",
                    "attributes": evaluateAttributes(node, sri)
                ]
                children.add(paramMap)
                break
            case "link":
                Map<String, Object> linkMap = [
                    "@type": "m-link",
                    "attributes": evaluateAttributes(node, sri),
                    "children": []
                ]
                String url = node.attribute("url")
                String urlType = node.attribute("url-type") ?: "transition"
                String href = ""
                if (url && urlType != "plain") {
                    href = sri.makeUrlByType(url, urlType, node, "true").getPath()
                } else if (url) {
                    href = url
                }

                Map<String, Object> linkAttrs = (Map<String, Object>) linkMap.attributes
                if (href) {
                    Map<String, String> params = [:]
                    // 1. Support parameter-map attribute (Groovy expression returning a Map)
                    String paramMapStr = node.attribute("parameter-map")
                    if (paramMapStr) {
                        try {
                            Object mapVal = sri.ec.resource.expression(paramMapStr, "link.parameter-map")
                            if (mapVal instanceof Map) {
                                ((Map) mapVal).each { Object k, Object v -> 
                                    if (k && v != null) params.put(k.toString(), v.toString()) 
                                }
                            }
                        } catch (Exception e) {
                            if (logger.isDebugEnabled()) logger.debug("Error evaluating parameter-map '${paramMapStr}': ${e.message}")
                        }
                    }
                    // 2. Support child parameter elements
                    node.children("parameter").each { MNode param ->
                        String pName = param.attribute("name")
                        String pValue = param.attribute("value")
                        String pFrom = param.attribute("from")
                        Object val = pValue != null ? sri.ec.resource.expand(pValue, "") : (pFrom ? sri.ec.context.get(pFrom) : null)
                        if (val != null) params.put(pName, val.toString())
                    }
                    if (params) {
                        href += (href.contains("?") ? "&" : "?") + params.collect { String k, String v -> "${k}=${URLEncoder.encode(v, 'UTF-8')}" }.join("&")
                    }
                    linkAttrs.href = href
                }
                
                walkWidgets(node, (List) linkMap.children, sri, depth)
                // AMB 2026-03-19: Skip child parameter nodes since we build them into the URL
                ((List) linkMap.children).removeAll { Object it -> ((Map) it)["@type"] == "parameter" }
                children.add(linkMap)
                break
            case "container-row":
                Map<String, Object> rowMap = [
                    "@type": "m-container-row",
                    "attributes": evaluateAttributes(node, sri),
                    "children": []
                ]
                walkWidgets(node, (List) rowMap.children, sri, depth)
                children.add(rowMap)
                break
            case "row-col":
                Map<String, Object> colMap = [
                    "@type": "m-row-col",
                    "attributes": evaluateAttributes(node, sri),
                    "children": []
                ]
                walkWidgets(node, (List) colMap.children, sri, depth)
                children.add(colMap)
                break
            case "banner":
                Map<String, Object> bannerMap = [
                    "@type": "m-banner",
                    "attributes": evaluateAttributes(node, sri),
                    "children": []
                ]
                walkWidgets(node, (List) bannerMap.children, sri, depth)
                children.add(bannerMap)
                break
            case "container":
                handleContainer(node, children, sri, depth)
                break
            case "label":
                handleLabel(node, children, sri, depth)
                break
            case "subscreens-active":
                handleSubscreensActive(node, children, sri, depth)
                break
            case "subscreens-menu":
                handleSubscreensMenu(node, children, sri, depth)
                break
            case "render-mode":
                handleRenderMode(node, children, sri, depth)
                break
            case "text":
                handleText(node, children, sri, depth)
                break
            case "display":
            case "display-entity":
                Map<String, Object> widgetMap = [
                    "@type": name,
                    "attributes": evaluateAttributes(node, sri)
                ]
                Map<String, Object> wAttrs = (Map<String, Object>) widgetMap.attributes
                if (fieldNode) {
                    try {
                        String textValue = name == "display" ? sri.getFieldValueString(node) : sri.getFieldEntityValue(node)
                        if (textValue != null) wAttrs.text = textValue
                    } catch (Exception e) {
                        if (logger.isTraceEnabled()) logger.trace("Error evaluating ${name} value: ${e.message}")
                    }
                }
                children.add(widgetMap)
                break
            case "text-line":
            case "text-area":
            case "date-time":
            case "check":
            case "radio":
            case "password":
            case "hidden":
            case "submit":
                Map<String, Object> wMap = [
                    "@type": name,
                    "attributes": (Map<String, Object>) evaluateAttributes(node, sri)
                ]
                Map<String, Object> wMapAttrs = (Map<String, Object>) wMap.attributes
                if (fieldNode && !wMapAttrs.value) {
                    try {
                        String textValue = sri.getFieldValueString(node)
                        if (textValue != null) wMapAttrs.value = textValue
                    } catch (Exception e) {
                        if (logger.isTraceEnabled()) logger.trace("Error evaluating ${name} value: ${e.message}")
                    }
                }
                children.add(wMap)
                break
            case "drop-down":
                Map<String, Object> ddM = [
                    "@type": "m-drop-down",
                    "attributes": (Map<String, Object>) evaluateAttributes(node, sri)
                ]
                Map<String, Object> ddMAttrs = (Map<String, Object>) ddM.attributes
                // Handle value
                if (fieldNode && !ddMAttrs.value) {
                    try {
                        String textValue = sri.getFieldValueString(node)
                        if (textValue != null) ddMAttrs.value = textValue
                    } catch (Exception e) {}
                }

                // Collect Options
                List<Map<String, Object>> options = []
                // 1. Hardcoded <option>
                for (MNode opt in node.children("option")) {
                    options.add([value: opt.attribute("key") ?: opt.attribute("value"), label: sri.ec.resource.expand(opt.attribute("text"), "")])
                }
                // 2. <entity-options>
                MNode entityOptions = node.first("entity-options")
                if (entityOptions) {
                    try {
                        String entityName = entityOptions.attribute("entity-name")
                        String keyField = entityOptions.attribute("key-field-name")
                        String textField = entityOptions.attribute("text")
                        MNode entityFindNode = entityOptions.first("entity-find")
                        org.moqui.entity.EntityFind find = sri.ec.entity.find(entityFindNode?.attribute("entity-name") ?: entityName)
                        if (entityFindNode) {
                            for (MNode cond in entityFindNode.children("econdition")) {
                                find.condition(cond.attribute("field-name"), cond.attribute("operator") ?: "equals", sri.ec.resource.expand(cond.attribute("value"), ""))
                            }
                            for (MNode ord in entityFindNode.children("order-by")) {
                                find.orderBy(ord.attribute("field-name"))
                            }
                        }
                        find.list().each { org.moqui.entity.EntityValue it ->
                            options.add([value: it.get(keyField), label: sri.ec.resource.expand(textField, "", it)])
                        }
                    } catch (Exception e) {
                        logger.error("Error fetching entity-options for ${node.attribute('name')}: ${e.toString()}")
                    }
                }
                // 3. <list-options>
                MNode listOptions = node.first("list-options")
                if (listOptions) {
                    String listName = listOptions.attribute("list")
                    String keyField = listOptions.attribute("key-field-name")
                    String textField = listOptions.attribute("text")
                    Object list = sri.ec.resource.expression(listName, "list-options")
                    if (list instanceof Iterable) {
                        ((Iterable) list).each { Object it ->
                            if (it instanceof Map) {
                                options.add([value: ((Map) it).get(keyField), label: sri.ec.resource.expand(textField, "", it)])
                            }
                        }
                    }
                }

                if (options) ddMAttrs.options = options
                children.add(ddM)
                break
            case "menu-item":
                Map<String, Object> miAttributes = evaluateAttributes(node, sri)
                String miName = node.attribute("name")
                String miTransition = node.attribute("transition") ?: node.attribute("url") ?: miName
                String miUrlType = node.attribute("url-type") ?: "transition"
                if (miTransition) {
                    miAttributes.href = sri.makeUrlByType(miTransition, miUrlType, node, "true").getPath()
                }
                if (miName) {
                    org.moqui.impl.screen.ScreenDefinition.SubscreensItem subItem = sri.getActiveScreenDef().getSubscreensItem(miName)
                    if (subItem != null) {
                        if (!miAttributes.text && !miAttributes.label) miAttributes.label = sri.ec.resource.expand(subItem.getMenuTitle() ?: subItem.getName(), "")
                    }
                }
                children.add(["@type": "m-menu-item", "attributes": miAttributes])
                break
            case "menu-dropdown":
                Map<String, Object> mdAttributes = evaluateAttributes(node, sri)
                String mdName = node.attribute("name")
                String mdTrans = node.attribute("transition")
                String mdTargetUrlAttr = node.attribute("target-url") ?: mdName
                if (mdTargetUrlAttr) {
                    mdAttributes["target-url"] = sri.makeUrlByType(mdTargetUrlAttr, "transition", node, "true").getPath()
                }
                if (mdName) {
                    org.moqui.impl.screen.ScreenDefinition.SubscreensItem subItem = sri.getActiveScreenDef().getSubscreensItem(mdName)
                    if (subItem != null) {
                        if (!mdAttributes.text && !mdAttributes.label) mdAttributes.label = sri.ec.resource.expand(subItem.getMenuTitle() ?: subItem.getName(), "")
                    }
                }
                if (mdTrans) {
                    mdAttributes["transition-url"] = sri.makeUrlByType(mdTrans, "transition", node, "true").getPath()
                }
                children.add(["@type": "m-menu-dropdown", "attributes": mdAttributes])
                break
            case "bp-tabbar":
            case "bp-tab":
            case "screen-layout":
            case "screen-split":
            case "screen-header":
            case "screen-toolbar":
            case "screen-drawer":
            case "screen-content":
                Map<String, Object> bpMap = [
                    "@type": "m-" + name,
                    "attributes": evaluateAttributes(node, sri),
                    "children": []
                ]
                walkWidgets(node, (List) bpMap.children, sri, depth)
                children.add(bpMap)
                break
            default:
                if (logger.isDebugEnabled()) logger.debug("${'  ' * depth}Handling unknown node: ${name} at ${sri.getActiveScreenDef().getLocation()}")
                
                // Map standard HTML tags to Blueprint types or keep as-is
                String nodeType = name
                if (name == "div" || name == "m-div") nodeType = "Container"
                else if (name == "template" || name == "m-template") nodeType = "BlueprintTemplate"
                else if (name in ["container-panel", "panel-header", "panel-left", "panel-center", "panel-right"]) nodeType = "Container"

                Map<String, Object> mapNode = [
                    "@type": nodeType,
                    "attributes": evaluateAttributes(node, sri)
                ]
                if (!node.getChildren().isEmpty()) {
                    mapNode.children = []
                    walkWidgets(node, (List) mapNode.children, sri, depth)
                }
                children.add(mapNode)
                break
        }
    }

    protected void handleFormSingle(MNode node, List children, ScreenRenderImpl sri, int depth = 0) {
        String formName = node.attribute("name")
        FormInstance formInstance = sri.getFormInstance(formName)
        if (logger.isInfoEnabled()) logger.info("${'  ' * depth}handleFormSingle: ${formName}")
        MNode formNode = formInstance.getFormNode()
        sri.pushSingleFormMapContext(formNode.attribute("map") ?: "fieldValues")
        String entityName = formNode.attribute("entity-name")
        if (entityName) sri.ec.context.put("currentEntityName", entityName)

        String actionUrl = ""
        String transitionName = formNode.attribute("transition")
        try {
            actionUrl = sri.makeUrlByType(transitionName, "transition", formNode, "true").getPath()
        } catch (Exception e) {
            logger.warn("⚠️ Failed to resolve form single URL for transition '${transitionName}' on screen ${sri.getActiveScreenDef().getLocation()}: ${e.message}")
            actionUrl = transitionName
        }

        try {
            Map<String, Object> formMap = [
                "@type": "FormSingle",
                "name": formName,
                "transition": transitionName,
                "action": actionUrl,
                "attributes": (Map<String, Object>) evaluateAttributes(formNode, sri),
                "fieldsInitial": sri.getFormFieldValues(formNode),
                "children": []
            ]
            
            // Handle field layout or fields
            MNode fieldLayout = formNode.first("field-layout")
            if (fieldLayout != null) {
                walkWidgets(fieldLayout, (List) formMap.children, sri, depth)
            } else {
                for (MNode fieldNode in formNode.children("field")) {
                    handleField(fieldNode, (List) formMap.children, sri)
                }
            }
            children.add(formMap)
        } finally {
            sri.ec.context.remove("currentEntityName")
            sri.popContext()
        }
    }

    protected void handleFormList(MNode node, List children, ScreenRenderImpl sri, int depth = 0) {
        String formName = node.attribute("name")
        FormInstance formInstance = sri.getFormInstance(formName)
        if (logger.isInfoEnabled()) logger.info("${'  ' * depth}handleFormList: ${formName}")
        MNode formNode = formInstance.getFormNode()
        
        // This is a simplified version, real one needs data paging etc.
        List list = formInstance.makeFormListRenderInfo().getListObject(true)
        
        Map<String, Object> formMap = [
            "@type": "FormList",
            "name": formName,
            "header": [],
            "rows": []
        ]
        String entityName = formNode.attribute("entity-name")
        if (entityName) sri.ec.context.put("currentEntityName", entityName)
        
        // Capture Header
        for (MNode field in formNode.children("field")) {
            String fieldName = field.attribute("name")
            MNode headerField = field.first("header-field")
            MNode defaultField = field.first("default-field")
            
            // Determine title - use standard Moqui title evaluation if possible
            String title = field.attribute("title")
            if (!title && headerField != null) title = headerField.attribute("title")
            if (!title && defaultField != null) title = defaultField.attribute("title")
            if (!title) title = org.moqui.util.StringUtilities.camelCaseToPretty(fieldName)

            Map<String, Object> hField = [
                "name": fieldName,
                "title": sri.ec.resource.expand(title, ""),
                "children": []
            ]
            if (headerField != null) walkWidgets(headerField, (List) hField.children, sri, depth, field)
            ((List) formMap.header).add(hField)
        }
        
        // Capture Data Rows
        for (Object item in list) {
            Map<String, Object> row = ["_data": item instanceof org.moqui.entity.EntityValue ? ((org.moqui.entity.EntityValue) item).getMap() : item]
            row.fields = []
            sri.ec.contextStack.push((Map<String, Object>) item)
            try {
                for (MNode field in formNode.children("field")) {
                    handleField(field, (List) row.fields, sri)
                }
            } finally {
                sri.ec.contextStack.pop()
            }
            ((List) formMap.rows).add(row)
        }
        
        if (logger.isInfoEnabled()) logger.info("${'  ' * depth}FormList ${formName} produced ${((List) formMap.rows).size()} rows")
        sri.ec.context.remove("currentEntityName")
        children.add(formMap)
    }

    protected void handleSubscreensActive(MNode node, List children, ScreenRenderImpl sri, int depth = 0) {
        int pathIndex = (sri.ec.context.get("blueprintPathIndex") ?: 0) as int
        sri.ec.context.put("blueprintPathIndex", pathIndex + 1)
        
        Map<String, Object> subMap = [
            "@type": "SubscreensActive",
            "attributes": ["path-index": pathIndex],
            "children": []
        ]
        
        // AMB 2026-03-10: Discrete subscreen loading logic
        boolean hasNext = sri.getActiveScreenHasNext()
        int maxExtraDepth = (sri.ec.context.get("blueprintRenderDepth") ?: 0) as int
        int currentExtraDepth = (sri.ec.context.get("blueprintExtraDepth") ?: 0) as int

        if (hasNext || currentExtraDepth < maxExtraDepth) {
            if (logger.isInfoEnabled()) logger.info("${'  ' * depth}handleSubscreensActive: recursing (hasNext: ${hasNext}, currentExtraDepth: ${currentExtraDepth}, maxExtraDepth: ${maxExtraDepth})")
            
            List parentChildren = (List) sri.ec.context.get("blueprintChildren")
            sri.ec.context.put("blueprintChildren", subMap.children)
            if (!hasNext) sri.ec.context.put("blueprintExtraDepth", currentExtraDepth + 1)
            
            try {
                sri.renderSubscreen()
            } finally {
                if (!hasNext) sri.ec.context.put("blueprintExtraDepth", currentExtraDepth)
                if (parentChildren != null) sri.ec.context.put("blueprintChildren", parentChildren)
                else sri.ec.context.remove("blueprintChildren")
            }
        } else {
            if (logger.isInfoEnabled()) logger.info("${'  ' * depth}handleSubscreensActive: skipping recursion (reached target screen and depth limit)")
        }
        
        String renderedKey = "SubscreensActiveRendered_${sri.getActiveScreenDef().getLocation()}"
        sri.ec.context.put(renderedKey, true)
        children.add(subMap)
        if (logger.isInfoEnabled()) logger.info("${'  ' * depth}handleSubscreensActive: finished with ${((List) subMap.children).size()} children")
    }

    protected void handleSubscreensMenu(MNode node, List children, ScreenRenderImpl sri, int depth = 0) {
        // Menu data can be retrieved from sri.getMenuData() or similar
        children.add([
            "@type": "SubscreensMenu",
            "id": node.attribute("id")
        ])
    }

    protected void handleSection(MNode node, List children, ScreenRenderImpl sri, int depth = 0) {
        boolean conditionPassed = true
        // Attribute condition check moved to handleNode
        MNode condNode = node.first("condition")
        if (conditionPassed && condNode != null && condNode.first() != null) {
            org.moqui.impl.actions.XmlAction conditionAction = new org.moqui.impl.actions.XmlAction(sri.ec.ecfi, condNode.first(), "section.condition")
            conditionPassed = conditionAction.checkCondition(sri.ec)
        }
        
        if (conditionPassed) {
            if (node.hasChild("widgets")) {
                walkWidgets(node.first("widgets"), children, sri, depth)
            }
        } else {
            if (node.hasChild("fail-widgets")) {
                walkWidgets(node.first("fail-widgets"), children, sri, depth)
            }
        }
    }

    protected void handleContainer(MNode node, List children, ScreenRenderImpl sri, int depth = 0) {
        Map<String, Object> containerMap = [
            "@type": "Container",
            "id": sri.ec.resource.expandNoL10n(node.attribute("id"), ""),
            "style": sri.ec.resource.expandNoL10n(node.attribute("style"), ""),
            "attributes": evaluateAttributes(node, sri),
            "children": []
        ]
        walkWidgets(node, (List) containerMap.children, sri, depth)
        children.add(containerMap)
    }

    protected void handleLabel(MNode node, List children, ScreenRenderImpl sri, int depth = 0) {
        Map<String, Object> labelMap = [
            "@type": "Label",
            "text": sri.ec.resource.expand(node.attribute("text") ?: node.getText(), ""),
            "style": sri.ec.resource.expandNoL10n(node.attribute("style"), ""),
            "attributes": evaluateAttributes(node, sri)
        ]
        children.add(labelMap)
    }

    protected void handleField(MNode fieldNode, List children, ScreenRenderImpl sri) {
        String fieldCond = fieldNode.attribute("condition")
        if (fieldCond?.trim()) {
            boolean condPassed = false
            try {
                condPassed = sri.ec.resource.condition(fieldCond, "")
            } catch (Exception e) {
                logger.warn("Condition error: '${fieldCond}' for field ${fieldNode.attribute('name')}: ${e.message}")
            }
            if (!condPassed) return
        }
        String fieldName = fieldNode.attribute("name")
        MNode defaultField = fieldNode.first("default-field")
        
        // Determine title - use standard Moqui logic
        String title = fieldNode.attribute("title")
        if (!title && defaultField != null) title = defaultField.attribute("title")
        
        // Option 2: Check Entity Field title attribute
        if (!title) {
            String entityName = (String) sri.ec.context.get("currentEntityName")
            if (entityName) {
                try {
                    EntityFacadeImpl efi = (EntityFacadeImpl) sri.ec.entity
                    EntityDefinition ed = efi.getEntityDefinition(entityName)
                    MNode efNode = ed?.getFieldNode(fieldName)
                    if (efNode != null) {
                        title = efNode.attribute("title")
                    }
                } catch (Exception e) {
                    if (logger.isTraceEnabled()) logger.trace("Could not find entity field for title: ${entityName}.${fieldName}")
                }
            }
        }

        if (!title) title = org.moqui.util.StringUtilities.camelCaseToPretty(fieldName)

        Map<String, Object> fieldMap = [
            "@type": "FormField",
            "name": fieldName,
            "title": sri.ec.resource.expand(title, ""),
            "children": []
        ]
        MNode widgetNode = fieldNode.getChildren().find { MNode it -> it.getName() != "header-field" && it.getName() != "condition" }
        if (widgetNode != null) {
            String widgetName = widgetNode.getName()
            if (widgetName == "default-field" || widgetName == "conditional-field") {
                walkWidgets(widgetNode, (List) fieldMap.children, sri, 0, fieldNode)
            } else {
                handleNode(widgetNode, (List) fieldMap.children, sri, 0, fieldNode)
            }
        }
        children.add(fieldMap)
    }

    protected void handleRenderMode(MNode node, List children, ScreenRenderImpl sri, int depth = 0) {
        String type = node.attribute("type")
        if (!type || type == sri.getRenderMode() || type == "qjson" || type.contains("html") || type.contains("qvt")) {
            walkWidgets(node, children, sri, depth)
        }
    }

    protected void handleText(MNode node, List children, ScreenRenderImpl sri, int depth = 0) {
        String text = node.getText()
        if (text != null && text.trim() == '${sri.renderSubscreen()}') {
            handleSubscreensActive(node, children, sri, depth)
            return
        }
        
        // Handle literal text or template
        String type = node.attribute("type")
        if (!type || type == sri.getRenderMode() || type == "qjson" || type.contains("html") || type.contains("qvt")) {
            String location = node.attribute("location")
            if (location && (location.endsWith(".ftl") || location.endsWith(".html") || location.endsWith(".qvt")) && sri.getRenderMode() == "qjson") {
                if (logger.isInfoEnabled()) logger.info("${'  ' * depth}Skipping template in JSON mode: ${location}")
                return
            }
            if (!location && text?.contains("<#") && sri.getRenderMode() == "qjson") {
                if (logger.isInfoEnabled()) logger.info("${'  ' * depth}Skipping raw FTL code in JSON mode")
                return
            }

            String expandedText = ""
            if (location) {
                expandedText = sri.ec.resource.getLocationText(location, true) ?: ""
                if (node.attribute("template") == "true") expandedText = sri.ec.resource.expand(expandedText, "")
            } else {
                expandedText = node.attribute("template") == "true" ? sri.ec.resource.expand(text, "") : text
            }

            if (expandedText != null && !expandedText.trim().isEmpty()) {
                children.add([
                    "@type": "Text",
                    "text": expandedText,
                    "location": location
                ])
            }
        }
    }

    protected Map<String, Object> evaluateAttributes(MNode node, ScreenRenderImpl sri) {
        Map<String, Object> attrs = [:]
        for (entry in node.getAttributes()) {
            String value = entry.getValue()
            if (value?.contains('${')) {
                attrs[entry.getKey()] = sri.ec.resource.expand(value, "")
            } else {
                attrs[entry.getKey()] = value
            }
        }
        return attrs
    }
}
