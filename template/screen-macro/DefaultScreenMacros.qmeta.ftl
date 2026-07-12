    =========================================================================
    DefaultScreenMacros.qmeta.ftl
    =========================================================================
    Unified Meta-JSON Layout Compiler
    Derived Natively from moqui-mcp-2 Core Semantic Architecture.
    =========================================================================
-->

<#-- 1. EXTEND MOQUI-MCP-2 CAPABILITIES THROUGH EXPLICIT RESOURCE PASSING -->
<#include "component://moqui-mcp-2/screen/macro/DefaultScreenMacros.mcp.ftl"/>

<#-- INITIALIZE A BULLETPROOF GLOBAL RUNTIME ELEMENT COUNTER -->
<#global qmetaElementCounter = 0>

<#function getCleanPath>
    <#local loc = sri.getActiveScreenDef().getLocation()>
    <#-- e.g., transforms "component://agi-ide/screen/agi-ide.xml" into "agi-ide" -->
    <#return loc?replace("^component://[^/]+/screen/", "", "r")?replace(".xml$", "", "r")>
</#function>

<#-- HELPER TOOLKIT: Capture native recursion stream and inject commas cleanly -->
<#macro renderChildren parentNode>
    <#-- 1. Capture the raw string output of all child macro executions -->
    <#local rawBuffer><#recurse></#local>
    
    <#-- 2. Clean up structural whitespace and formatting glitches -->
    <#local cleanBuffer = rawBuffer?trim>
    
    <#-- 3. Regex fix: Look for adjacent JSON blocks } { and bridge them with a comma -->
    <#local formattedJson = cleanBuffer?replace("}\\s*\\{", "}, {", "r")>
    
    <#-- 4. Flush the valid JSON format to the window tree stream -->
    ${formattedJson}
</#macro>
<#-- 2. CORE SCREEN INTERCEPTORS AND PAYLOAD ENVELOPE STRUCTURING -->
<#-- 1. THE UNIFIED ROOT ENTRY INTERCEPTOR -->
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

    <#-- Double slashes preserved securely for your local auth proxy routing rules -->
    <script src="http://localhost:8080/agi-ai-assets/MoquiAiVueFunctions.js" type="text/javascript"></script>
    <script src="http://localhost:8080/agi-ai-assets/moqui-utils.js" type="text/javascript"></script>
    <script src="http://localhost:8080/agi-ai-assets/MoquiAiVue.qvt.js" type="text/javascript"></script>
    <script src="http://localhost:8080/agi-ai-assets/BlueprintClient.qvt.js" type="text/javascript"></script>

    <script type="text/javascript">
    (function() {
        window.AGI_SERVER_CSRF_TOKEN = "${ec.web.sessionToken!}";
        window.AGI_SERVER_USER_ID = "${ec.user.userId!}";
        console.info("🔒 Server-injected security token initialized into global window scope.");

        // Deep execution pass down into children to assemble the meta-json object natively
        window.AGI_RAW_META_TREE = [<@renderChildren parentNode=.node/>];

// 🎯 Add a global mounting guard at the very top of your script block

// 🎯 Global mounting guard to prevent overlapping interval initialization passes
window.AGI_APP_MOUNTING = false;

function bootQmetaApplication() {
    if (typeof Vue === 'undefined' || !window.AgiComponents || !window.AGI_RAW_META_TREE) {
        return false; 
    }
    
    // Prevent interval overlaps from attempting to build duplicate apps simultaneously
    if (window.AGI_APP_MOUNTING) return true;

    try {
        window.AGI_APP_MOUNTING = true; // Engage execution lock immediately
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
                        // Dynamically extract the true path segments from the window URL
                        const currentUrlPath = window.location.pathname;
                        const segments = currentUrlPath.split('/').filter(p => p.length > 0);
                        const cleanPathList = segments.slice(1); // ['AgiWorkspace']
                        
                        // Force-inject the true path arrays onto BOTH contexts to guarantee a pass
                        if (!this.currentPathList || this.currentPathList.length === 0) {
                            this.currentPathList = cleanPathList;
                        }
                        if (saComp.$root && (!saComp.$root.currentPathList || saComp.$root.currentPathList.length === 0)) {
                            saComp.$root.currentPathList = cleanPathList;
                        }
                        
                        // Execute the native method cleanly with the healed contexts
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
        
        function mountAppWithComponents() {
            if (!window.AgiComponents || !window.AgiComponents['m-blueprint-node']) {
                console.warn("⏳ AgiComponents map not initialized yet. Retrying in 50ms...");
                window.AGI_APP_MOUNTING = false; // Release lock to allow interval tick retry
                setTimeout(mountAppWithComponents, 50);
                return;
            }

            // Register global elements securely with explicit uniqueness checks
            Object.keys(window.AgiComponents).forEach(tag => {
                if (!app.component(tag)) {
                    app.component(tag, window.AgiComponents[tag]);
                }
                if (window.AgiComponents[tag].name) {
                    const altName = window.AgiComponents[tag].name;
                    if (!app.component(altName)) {
                        app.component(altName, window.AgiComponents[tag]);
                    }
                }
            });
            
            // Finalize initialization pass
            app.use(Quasar);
            app.mount('#q-app');
            console.log("🚀 [AGI QMETA] Application context mounted successfully after asset sync.");
        }
        
        mountAppWithComponents();
        return true;
    } catch (err) {
        console.error("❌ App boot exception:", err);
        window.AGI_APP_MOUNTING = false; // Clear lock on hard failure to allow a fresh run pass
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
<#-- 2. NESTED CHILD SUBSCREENS: Print pure data blocks to build the tree properties recursively -->
{
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
{
  "@type": "Container",
  "id": "${.node['@id']!}",
  "style": "${.node['@style']!}",
  <#-- CLEAN: Outputs "agi-ide#agi-ide-header" -->
  "mariaId": "${getCleanPath()}#${.node['@id']!'container-' + qmetaElementCounter}",
  "children": [<@renderChildren parentNode=.node/>]
}
</#macro>

<#macro "container-box">
{
  "@type": "ContainerBox",
  "id": "${.node['@id']!}",
  "title": "${.node['box-header'][0]['@title']!}",
  "mariaId": "${sri.getActiveScreenDef().getLocation()}#${.node['@id']!''}",
  "children": [<@renderChildren parentNode=.node/>]
}
</#macro>

<#-- ================ 4. HIGH-FIDELITY FORM CORE MAPPINGS ================ -->
<#macro "form-single">
{
  "@type": "FormSingle",
  "name": "${.node['@name']!}",
  "transition": "${.node['@transition']!}",
  "action": "${sri.buildUrl(.node['@transition']!).getTarget()!}",
  "mariaId": "${sri.getActiveScreenDef().getLocation()}#${.node['@name']!}",
  "children": [<@renderChildren parentNode=.node/>]
}
</#macro>

<#macro "field">
{
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
  "@type": "m-text-line", 
  "attributes": { 
    "placeholder": "${.node['@placeholder']!}",
    "disabled": "${.node['@disabled']!'false'}"
  } 
}
</#macro>

<#macro "drop-down">
{ 
  "@type": "m-drop-down", 
  "attributes": { 
    "allow-empty": "${.node['@allow-empty']!'true'}",
    "value": ""
  } 
}
</#macro>

<#macro "submit">
{
  "@type": "submit",
  "attributes": {
    "text": "${.node['@text']!'Submit'}"
  }
}
</#macro>

<#-- ================ 6. FALLBACK BEHAVIORS FOR COMPLEX CORE TAGS ================ -->
<#macro "link">
<#global qmetaElementCounter = qmetaElementCounter + 1>
{
  "@type": "Link",
  "text": "${.node['@text']!}",
  "url": "${sri.buildUrl(.node['@url']!'.').getUrl()!}",
  <#-- CLEAN: Outputs "agi-ide#link-2" -->
  "mariaId": "${getCleanPath()}#link-${qmetaElementCounter}"
}
</#macro>

<#macro "label">
<#global qmetaElementCounter = qmetaElementCounter + 1>
{
  "@type": "Label",
  "text": "${.node['@text']!}",
  "style": "${.node['@style']!}",
  <#-- CLEAN: Outputs "agi-ide#label-1" -->
  "mariaId": "${getCleanPath()}#label-${qmetaElementCounter}"
}
</#macro>

<#-- ================ 7. CUSTOM WORKSPACE SHELL PRIMITIVES ================ -->
<#macro "screen-layout">
{
  "@type": "m-screen-layout",
  "attributes": {
    "view": "${.node['@view']!'hHh lpR fFf'}"
  },
  "children": [<@renderChildren parentNode=.node/>]
}
</#macro>

<#macro "screen-header">
{
  "@type": "m-screen-header",
  "attributes": {
    "elevated": ${.node['@elevated']!'true'}
  },
  "children": [<@renderChildren parentNode=.node/>]
}
</#macro>

<#macro "screen-toolbar">
{
  "@type": "m-screen-toolbar",
  "children": [<@renderChildren parentNode=.node/>]
}
</#macro>

<#macro "screen-content">
{
  "@type": "m-screen-content",
  "children": [<@renderChildren parentNode=.node/>]
}
</#macro>

<#-- ================ 8. NAVIGATION & MENU PRIMITIVES ================ -->
<#macro "subscreens-menu">
{
  "@type": "m-subscreens-menu",
  "attributes": {
    "type": "${.node['@type']!'drawer'}",
    "pathIndex": "${.node['@pathIndex']!''}"
  }
}
</#macro>

<#macro "menu-item">
<#global qmetaElementCounter = qmetaElementCounter + 1>
{
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
  "@type": "m-subscreens-active",
  "attributes": {
    "pathIndex": "${.node['@pathIndex']!'-1'}"
  }
}
</#macro>