<#--
    =========================================================================
    DefaultScreenMacros.qmeta.ftl
    =========================================================================
    Unified Meta-JSON Layout Compiler with Semantic _moquiTag Preservation
    Derived Natively from moqui-mcp-2 Core Semantic Architecture.
    =========================================================================
-->

<#-- 1. EXTEND MOQUI-MCP-2 CAPABILITIES THROUGH EXPLICIT RESOURCE PASSING -->
<#include "component://moqui-mcp-2/screen/macro/DefaultScreenMacros.mcp.ftl"/>

<#-- INITIALIZE A BULLETPROOF GLOBAL RUNTIME ELEMENT COUNTER -->
<#global qmetaElementCounter = 0>

<#function getCleanPath>
    <#local loc = sri.getActiveScreenDef().getLocation()>
    <#return loc?replace("^component://[^/]+/screen/", "", "r")?replace(".xml$", "", "r")>
</#function>

<#-- HELPER TOOLKIT: Capture native recursion stream and inject commas cleanly -->
<#macro renderChildren parentNode>
    <#local rawBuffer><#recurse></#local>
    <#local cleanBuffer = rawBuffer?trim>
    <#local formattedJson = cleanBuffer?replace("}\\s*\\{", "}, {", "r")>
    ${formattedJson}
</#macro>

<#-- 2. CORE SCREEN INTERCEPTORS AND PAYLOAD ENVELOPE STRUCTURING -->
<#macro screen>
<#local screenName = getCleanPath()>
<#if sri.getScreenUrlInfo().lastStandalone == 0>
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>AGI Agentic Workspace IDE</title>
    <link rel="stylesheet" href="https://fonts.googleapis.com/css?family=Roboto:100,300,400,500,700,900|Material+Icons" type="text/css">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/quasar@2.17.1/dist/quasar.css" type="text/css">
    <link rel="stylesheet" href="/agi-ide-include/agi-ide.css" type="text/css">
</head>
<body class="bg-grey-1">

    <div id="q-app" class="window-height full-width">
        <m-blueprint-node v-if="blueprintTree" :node="blueprintTree" :context="{}"></m-blueprint-node>
    </div>

    <script src="https://code.jquery.com/jquery-3.6.0.min.js" type="text/javascript"></script>
    <script src="https://cdn.jsdelivr.net/npm/vue@3/dist/vue.global.js" type="text/javascript"></script>
    <script src="https://cdn.jsdelivr.net/npm/quasar@2.17.1/dist/quasar.umd.js" type="text/javascript"></script>
    <script src="https://unpkg.com/vue-demi" type="text/javascript"></script>
    <script src="https://unpkg.com/pinia@2.3.1" type="text/javascript"></script>
    <script src="https://cdn.jsdelivr.net/npm/axios@1.18.1/dist/axios.min.js" type="text/javascript"></script>

    <script src="http://localhost:8080/agi-ai-assets/MoquiAiVueFunctions.js" type="text/javascript"></script>
    <script src="http://localhost:8080/agi-ai-assets/moqui-utils.js" type="text/javascript"></script>
    <script src="http://localhost:8080/agi-ai-assets/moqui-xml-host.qvt.js" type="text/javascript"></script>
    <script src="http://localhost:8080/agi-ai-assets/MoquiAiVue.qvt.js" type="text/javascript"></script>
    <script src="http://localhost:8080/agi-ai-assets/BlueprintClient.qvt.js" type="text/javascript"></script>
    <script src="http://localhost:8080/agi-ide-assets/AgiEditorShare.qvt.js" type="text/javascript"></script>
    <script src="http://localhost:8080/agi-ide-assets/agi-ide-store.qvt.js" type="text/javascript"></script>
    <script src="http://localhost:8080/agi-ide-assets/AgiMcpOrchestrator.js" type="text/javascript"></script>
    <script src="http://localhost:8080/agi-ide-assets/AgiSubWorkspace.qvt.js" type="text/javascript"></script>
    <script src="http://localhost:8080/agi-ide-assets/AgiWorkspace.qvt.js" type="text/javascript"></script>

    <script type="text/javascript">
    (function() {
        window.AGI_SERVER_CSRF_TOKEN = "${ec.web.sessionToken!}";
        window.AGI_SERVER_USER_ID = "${ec.user.userId!}";
        console.info("🔒 Server-injected security token initialized into global window scope.");

        window.AGI_RAW_META_TREE = [<@renderChildren parentNode=.node/>];

        console.log("[AGI_RAW_META_TREE]: " + JSON.stringify(window.AGI_RAW_META_TREE) );

        window.AGI_APP_MOUNTING = false;

        function bootQmetaApplication() {
            if (typeof Vue === 'undefined' || !window.AgiComponents || !window.AGI_RAW_META_TREE) {
                return false; 
            }
            if (window.AGI_APP_MOUNTING) return true;

            try {
                window.AGI_APP_MOUNTING = true;
                console.info("📡 Component maps localized. Bootstrapping application shell...");
                
                const appOptions = {
                    name: 'AgiIdeQmetaApp',
                    data() {
                        const currentUrlPath = window.location.pathname;
                        const segments = currentUrlPath.split('/').filter(p => p.length > 0);
                        const computedBase = segments.length > 0 ? '/' + segments[0] : '';
                        const childPathList = segments.slice(1);
                        
                        return {
                            blueprintTree: window.AGI_RAW_META_TREE,
                            moquiSessionToken: window.AGI_SERVER_CSRF_TOKEN,
                            basePath: computedBase,
                            basePathSize: 1,
                            appRootPath: computedBase,
                            linkBasePath: computedBase,
                            activeSubscreens: [],
                            currentPathList: childPathList,
                            navMenuList: [],
                            navHistoryList: [],
                            activeContainers: {},
                            urlListeners: [],
                            notifyHistoryList: [],
                            loading: 0,
                            currentPath: currentUrlPath,
                            currentLinkUrl: window.location.pathname + window.location.search,
                            reLoginShow: false
                        };
                    },
                    created() {
                        window.moqui = window.moqui || {};
                        window.moqui.webrootVue = this;
                        window.moqui.rootSetup = () => ({ methods: this });

                        if (window.moqui && typeof window.moqui.addSubscreen === 'function') {
                            const nativeAddSubscreen = window.moqui.addSubscreen;
                            window.moqui.addSubscreen = function(saComp) {
                                const currentUrlPath = window.location.pathname;
                                const segments = currentUrlPath.split('/').filter(p => p.length > 0);
                                const cleanPathList = segments.slice(1);
                                
                                if (!this.currentPathList || this.currentPathList.length === 0) {
                                    this.currentPathList = cleanPathList;
                                }
                                if (saComp.$root && (!saComp.$root.currentPathList || saComp.$root.currentPathList.length === 0)) {
                                    saComp.$root.currentPathList = cleanPathList;
                                }
                                return nativeAddSubscreen.call(this, saComp);
                            };
                        }
                    }
                };

                if (window.AgiVueAppFunctionMap) {
                    appOptions.methods = appOptions.methods || {};
                    Object.keys(window.AgiVueAppFunctionMap).forEach(fn => {
                        appOptions.methods[fn] = window.AgiVueAppFunctionMap[fn];
                    });
                }

                const app = Vue.createApp(appOptions);

                window.moqui = window.moqui || {};
                window.moqui.webrootVueApp = app;
                
                function mountAppWithComponents() {
                    if (!window.AgiComponents || !window.AgiComponents['m-blueprint-node']) {
                        console.warn("⏳ AgiComponents map not initialized yet. Retrying in 50ms...");
                        window.AGI_APP_MOUNTING = false;
                        setTimeout(mountAppWithComponents, 50);
                        return;
                    }

                    Object.keys(window.AgiComponents).forEach(tag => {
                        if (!app.component(tag)) app.component(tag, window.AgiComponents[tag]);
                        if (window.AgiComponents[tag].name) {
                            const altName = window.AgiComponents[tag].name;
                            if (!app.component(altName)) app.component(altName, window.AgiComponents[tag]);
                        }
                    });
                    
                    app.use(Quasar);
                    app.use(Pinia.createPinia());
                    app.mount('#q-app');
                    console.log("🚀 [AGI QMETA] Application context mounted successfully after asset sync.");
                }
                
                mountAppWithComponents();
                return true;
            } catch (err) {
                console.error("❌ App boot exception:", err);
                window.AGI_APP_MOUNTING = false;
                return true;
            }
        }
        const networkPoll = setInterval(() => { if (bootQmetaApplication()) clearInterval(networkPoll); }, 20);
        setTimeout(() => clearInterval(networkPoll), 5000);
    })();
    </script>
</body>
</html>
<#else>
<#-- 2. NESTED CHILD SUBSCREENS -->
{
  "_moquiTag": "screen",
  "screen": "${getCleanPath()}",
  "location": "${sri.getActiveScreenDef().getLocation()}",
  "widgets": [<#recurse>]
}
</#if>
</#macro>

<#macro widgets><@renderChildren parentNode=.node/></#macro>
<#macro "fail-widgets"><@renderChildren parentNode=.node/></#macro>

<#-- ================ 3. LAYOUT & CONTAINER PRIMITIVES ================ -->
<#macro "container">
<#local containerClass = .node['@class']!''>
<#local containerType = .node['@type']!'div'>
<#local resolvedType = containerType>
<#if containerClass?starts_with("agi-") || containerClass?starts_with("m-")>
  <#local resolvedType = containerClass>
</#if>
{
  "_moquiTag": "container",
  "@type": "${resolvedType}",
  "id": "${.node['@id']!}",
  "style": "${.node['@style']!?json_string}",
  "mariaId": "${getCleanPath()}#${.node['@id']!'container-' + qmetaElementCounter}",
  "children": [<@renderChildren parentNode=.node/>]
}
</#macro>

<#macro "container-box">
{
  "_moquiTag": "container-box",
  "@type": "m-container-box",
  "id": "${.node['@id']!}",
  "title": "${.node['@title']!(.node['box-header'][0]['@title']!'')}",
  "mariaId": "${sri.getActiveScreenDef().getLocation()}#${.node['@id']!''}",
  "children": [<@renderChildren parentNode=.node/>]
}
</#macro>

<#macro "container-row">
{
  "_moquiTag": "container-row",
  "@type": "container-row",
  "id": "${.node['@id']!}",
  "class": "${.node['@class']!}",
  "style": "${.node['@style']!}",
  "children": [<@renderChildren parentNode=.node/>]
}
</#macro>

<#macro "row-col">
{
  "_moquiTag": "row-col",
  "@type": "row-col",
  "class": "${.node['@class']!}",
  "style": "${.node['@style']!}",
  "attributes": {
    "cols": "${.node['@cols']!}",
    "xs": "${.node['@xs']!}",
    "sm": "${.node['@sm']!}",
    "md": "${.node['@md']!}",
    "lg": "${.node['@lg']!}",
    "xl": "${.node['@xl']!}"
  },
  "children": [<@renderChildren parentNode=.node/>]
}
</#macro>

<#macro "m-tree-top">
{
  "_moquiTag": "m-tree-top",
  "@type": "m-tree-top",
  "id": "${.node['@id']!}",
  "attributes": {
    "id": "${.node['@id']!}",
    "items": "${.node['@items']!}",
    "openPath": "${.node['@openPath']!}"
  }
}
</#macro>

<#macro "slot">
<@renderChildren parentNode=.node/>
</#macro>

<#-- ================ 4. HIGH-FIDELITY FORM CORE MAPPINGS ================ -->
<#macro "form-single">
<#local transitionName = .node['@transition']!''>
<#local actionUrl = "#">
<#if transitionName?has_content>
  <#local actionUrl = (sri.buildUrl(transitionName).getTarget())!'#'>
</#if>
{
  "_moquiTag": "form-single",
  "@type": "m-form",
  "name": "${.node['@name']!}",
  "transition": "${transitionName}",
  "action": "${actionUrl}",
  "mariaId": "${sri.getActiveScreenDef().getLocation()}#${.node['@name']!}",
  "children": [<@renderChildren parentNode=.node/>]
}
</#macro>

<#macro "default-field"><@renderChildren parentNode=.node/></#macro>
<#macro "conditional-field"><@renderChildren parentNode=.node/></#macro>

<#macro "field">
{
  "_moquiTag": "field",
  "@type": "FormField",
  "name": "${.node['@name']!}",
  "title": "${.node['@title']!((.node['@name']?replace('^[a-z]', '', 'r'))?cap_first)}",
  "mariaId": "${sri.getActiveScreenDef().getLocation()}#${.node['@name']!}",
  "children": [<@renderChildren parentNode=.node/>]
}
</#macro>

<#-- ================ 5. FIELD LEVEL INPUT WIDGETS PRIMITIVES ================ -->
<#macro "text-line">
{ 
  "_moquiTag": "text-line",
  "@type": "m-text-line", 
  "attributes": { 
    "placeholder": "${.node['@placeholder']!}",
    "disabled": "${.node['@disabled']!'false'}"
  } 
}
</#macro>

<#macro "drop-down">
<#local allowEmptyVal = .node['@allow-empty']!'true'>
{ 
  "_moquiTag": "drop-down",
  "@type": "m-drop-down", 
  "attributes": { 
    "allow-empty": ${(allowEmptyVal == "true")?string("true", "false")},
    "value": ""
  } 
}
</#macro>

<#macro "submit">
{
  "_moquiTag": "submit",
  "@type": "q-btn",
  "attributes": {
    "label": "${.node['@text']!'Submit'}",
    "type": "submit",
    "color": "primary"
  }
}
</#macro>

<#macro "text-area">
{
  "_moquiTag": "text-area",
  "@type": "m-text-line",
  "attributes": {
    "type": "textarea",
    "placeholder": "${.node['@placeholder']!}"
  }
}
</#macro>

<#macro "discussion-tree">
{
  "_moquiTag": "discussion-tree",
  "@type": "discussion-tree",
  "attributes": {
    "workEffortId": "${.node['@work-effort-id']!''}"
  }
}
</#macro>

<#-- ================ 6. FALLBACK BEHAVIORS FOR COMPLEX CORE TAGS ================ -->
<#macro "link">
<#global qmetaElementCounter = qmetaElementCounter + 1>
{
  "_moquiTag": "link",
  "@type": "m-link",
  "text": "${.node['@text']!}",
  "url": "${sri.buildUrl(.node['@url']!'.').getUrl()!}",
  "mariaId": "${getCleanPath()}#link-${qmetaElementCounter}"
}
</#macro>

<#macro "label">
<#global qmetaElementCounter = qmetaElementCounter + 1>
{
  "_moquiTag": "label",
  "@type": "span",
  "style": "${.node['@style']!}",
  "mariaId": "${getCleanPath()}#label-${qmetaElementCounter}",
  "children": ["${.node['@text']!}"]
}
</#macro>

<#-- ================ 7. CUSTOM WORKSPACE SHELL PRIMITIVES ================ -->
<#macro "screen-layout">
{
  "_moquiTag": "screen-layout",
  "@type": "m-screen-layout",
  "attributes": {
    "view": "${.node['@view']!'hHh lpR fFf'}"
  },
  "children": [<@renderChildren parentNode=.node/>]
}
</#macro>

<#macro "screen-header">
{
  "_moquiTag": "screen-header",
  "@type": "m-screen-header",
  "attributes": {
    "elevated": ${.node['@elevated']!'true'}
  },
  "children": [<@renderChildren parentNode=.node/>]
}
</#macro>

<#macro "screen-toolbar">
{
  "_moquiTag": "screen-toolbar",
  "@type": "m-screen-toolbar",
  "children": [<@renderChildren parentNode=.node/>]
}
</#macro>

<#macro "screen-content">
{
  "_moquiTag": "screen-content",
  "@type": "m-screen-content",
  "children": [<@renderChildren parentNode=.node/>]
}
</#macro>

<#-- ================ 8. NAVIGATION, SUBSCREENS & RENDER-MODE ================ -->
<#macro "subscreens-menu">
{
  "_moquiTag": "subscreens-menu",
  "@type": "m-subscreens-menu",
  "attributes": {
    "type": "${.node['@type']!'drawer'}",
    "pathIndex": "${.node['@pathIndex']!''}"
  }
}
</#macro>

<#macro "subscreens-tabs">
{
  "_moquiTag": "subscreens-tabs",
  "@type": "m-subscreens-tabs",
  "id": "${.node['@id']!'subscreens-tabs'}"
}
</#macro>

<#macro "menu-item">
<#global qmetaElementCounter = qmetaElementCounter + 1>
{
  "_moquiTag": "menu-item",
  "@type": "m-menu-item",
  "attributes": {
    "name": "${.node['@name']!}",
    "href": "${.node['@href']!}",
    "text": "${.node['@text']!}",
    "icon": "${.node['@icon']!}",
    "buttonClass": "${.node['@buttonClass']!}"
  }
}
</#macro>

<#macro "menu-dropdown">
{
  "_moquiTag": "menu-dropdown",
  "@type": "m-menu-dropdown",
  "attributes": {
    "text": "${.node['@text']!}",
    "icon": "${.node['@icon']!}",
    "transitionUrl": "${.node['@transitionUrl']!}",
    "targetUrl": "${.node['@targetUrl']!}"
  }
}
</#macro>

<#macro "subscreens-active">
{
  "_moquiTag": "subscreens-active",
  "@type": "m-subscreens-active",
  "attributes": {
    "pathIndex": "${.node['@pathIndex']!'-1'}"
  }
}
</#macro>

<#macro "subscreens-all">
{
  "_moquiTag": "subscreens-all",
  "@type": "m-subscreens-all",
  "id": "${.node['@id']!'subscreens-all'}",
  "subscreens": [
    <#list sri.getActiveScreenDef().getSubscreensByName().values() as subscreenItem>
      {
        "_moquiTag": "subscreens-item",
        "name": "${subscreenItem.getName()}",
        "componentName": "agi-${subscreenItem.getName()?lower_case?replace('_', '-')}",
        "url": "${sri.getCurrentScreenUrl()}/${subscreenItem.getName()}"
      }<#if subscreenItem_has_next>,</#if>
    </#list>
  ]
}
</#macro>

<#-- 🎯 ADDED: Native <render-mode> and <text> macro interceptors -->
<#macro "render-mode">
{
  "_moquiTag": "render-mode",
  "@type": "m-render-mode",
  "children": [<@renderChildren parentNode=.node/>]
}
</#macro>

<#macro "text">
{
  "_moquiTag": "text",
  "@type": "m-text",
  "attributes": {
    "type": "${.node['@type']!}",
    "location": "${.node['@location']!}"
  },
  "value": "${.node?text?json_string}"
}
</#macro>

<#-- ================ 9. AGI WORKSPACE EDITORS & TERMINAL PALETTE ================ -->

<#macro "agi-canvas-editor">
{
  "_moquiTag": "agi-canvas-editor",
  "@type": "agi-canvas-editor",
  "attributes": {
    "screenPath": "${.node['@screen-path']!}",
    "layoutTree": "${.node['@layout-tree']!}"
  }
}
</#macro>

<#macro "agi-screen-editor">
{
  "_moquiTag": "agi-screen-editor",
  "@type": "agi-screen-editor",
  "attributes": {
    "screenPath": "${.node['@screen-path']!}",
    "layoutTree": "${.node['@layout-tree']!}"
  }
}
</#macro>

<#macro "agi-component-editor">
{
  "_moquiTag": "agi-component-editor",
  "@type": "agi-component-editor",
  "attributes": {
    "screenPath": "${.node['@screen-path']!}",
    "layoutTree": "${.node['@layout-tree']!}"
  }
}
</#macro>

<#macro "agi-command-palette">
{
  "_moquiTag": "agi-command-palette",
  "@type": "agi-command-palette"
}
</#macro>

<#macro "agi-container">
{
  "_moquiTag": "container",
  "@type": "div",
  "id": "${.node['@id']!}",
  "class": "${.node['@class']!}",
  "attributes": {
    "ai-intent": "${.node['@ai-intent']!?json_string}",
    "v-if": "${.node['@v-if']!}",
    "v-data": "${.node['@v-data']!}",
    "v-model": "${.node['@v-model']!}",
    "v-bind": "${.node['@v-bind']!}",
    "v-on": "${.node['@v-on']!}"
  },
  "mariaId": "${getCleanPath()}#${.node['@id']!'container-' + qmetaElementCounter}",
  "children": [<@renderChildren parentNode=.node/>]
}
</#macro>

<#-- ================ ACTIONS & DATA FETCHING PRIMITIVES ================ -->
<#macro "actions">
{
  "_moquiTag": "actions",
  "@type": "m-actions",
  "children": [<@renderChildren parentNode=.node/>]
}
</#macro>

<#macro "entity-find">
{
  "_moquiTag": "entity-find",
  "@type": "m-entity-find",
  "attributes": {
    "entity-name": "${.node['@entity-name']!}",
    "list": "${.node['@list']!}",
    "use-cache": "${.node['@use-cache']!'true'}"
  },
  "children": [<@renderChildren parentNode=.node/>]
}
</#macro>

<#macro "econdition">
{
  "_moquiTag": "econdition",
  "@type": "m-econdition",
  "attributes": {
    "field-name": "${.node['@field-name']!}",
    "value": "${.node['@value']!}",
    "operator": "${.node['@operator']!'equals'}"
  }
}
</#macro>

<#macro "order-by">
{
  "_moquiTag": "order-by",
  "@type": "m-order-by",
  "attributes": {
    "field-name": "${.node['@field-name']!}"
  }
}
</#macro>