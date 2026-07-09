<#--
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

<#-- 2. CORE SCREEN INTERCEPTORS AND PAYLOAD ENVELOPE STRUCTURING -->
<#-- 1. THE UNIFIED ROOT ENTRY INTERCEPTOR -->
<#macro screen>
<#local screenName = getCleanPath()>
<#if screenName == "webroot">
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>AGI Agentic Workspace IDE</title>
    <link rel="stylesheet" href="https://fonts.googleapis.com/css?family=Roboto:100,300,400,500,700,900|Material+Icons" type="text/css">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/quasar@2.17.1/dist/quasar.css" type="text/css">
    <link rel="stylesheet" href="/agi-ide-assets/agi-ide.css" type="text/css">
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

        // Deep execution pass down into children to assemble the meta-json object natively
        window.AGI_RAW_META_TREE = <#recurse>;

        function bootQmetaApplication() {
            if (typeof Vue === 'undefined' || !window.AgiComponents || !window.AGI_RAW_META_TREE) {
                return false; 
            }

            try {
                console.info("📡 Component maps localized. Bootstrapping application shell...");
                    const appOptions = {
                        name: 'AgiIdeQmetaApp',
                        data() {
                            // Determine screen path sizing defensively to prevent Java exception leakage
                            <#assign preSubscreenList = (sri.getScreenUrlInfo().getPreSubscreenPathNameList())![]>
                            <#assign pathSize = preSubscreenList?size!0>
                            
                            return {
                                blueprintTree: window.AGI_RAW_META_TREE,
                                moquiSessionToken: window.AGI_SERVER_CSRF_TOKEN,
                                
                                // 🎯 DEFENSIVE ALIGNMENT:
                                basePath: "${sri.getLinkViewKey()!''}", 
                                basePathSize: ${pathSize},
                                appRootPath: "${sri.getLinkViewKey()!''}",
                                linkBasePath: "${sri.getLinkViewKey()!''}",
            
                                activeSubscreens: [],
                                currentPathList: [],
                                navMenuList: [],
                                navHistoryList: [],
                                activeContainers: {},
                                urlListeners: [],
                                notifyHistoryList: [],
                                
                                loading: 0,
                                currentPath: "",
                                currentLinkUrl: window.location.pathname + window.location.search,
                                reLoginShow: false
                            }
                        },
                    created() {
                        // Ensure helper functions can resolve path lists natively
                        if (this.currentLinkUrl) {
                            var questionIdx = this.currentLinkUrl.indexOf("?");
                            var purePath = questionIdx > 0 ? this.currentLinkUrl.substring(0, questionIdx) : this.currentLinkUrl;
                            this.currentPathList = purePath.split('/').filter(p => p.length > 0);
                        }

                        window.moqui = window.moqui || {};
                        window.moqui.webrootVue = this;
                        // Expose a bridge selector anchor for mixed external elements
                        window.moqui.rootSetup = () => ({ methods: this });
                    }
                };

                if (window.AgiVueAppFunctionMap) {
                    appOptions.methods = appOptions.methods || {};
                    Object.keys(window.AgiVueAppFunctionMap).forEach(fn => {
                        appOptions.methods[fn] = window.AgiVueAppFunctionMap[fn];
                    });
                }

                const app = Vue.createApp(appOptions);
                if (typeof Quasar !== 'undefined') app.use(Quasar);
                if (typeof Pinia !== 'undefined') app.use(Pinia.createPinia());
                //if (window.BlueprintClient) app.use(window.BlueprintClient);

                Object.keys(window.AgiComponents).forEach(tag => {
                    app.component(tag, window.AgiComponents[tag]);
                });

                window.moquiApp = app.mount('#q-app');
                console.info("🚀 [AGI QMETA] Core context cleanly mounted.");
                return true;
            } catch (err) {
                console.error("❌ App boot exception:", err);
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
  "screen": "${screenName}",
  "location": "${sri.getActiveScreenDef().getLocation()}",
  "mariaId": "${screenName}#root",
  "widgets": <#recurse>
}
</#if>
</#macro>

<#macro widgets>[<#recurse>]</#macro>
<#macro "fail-widgets">[<#recurse>]</#macro>

<#-- ================ 3. LAYOUT & CONTAINER PRIMITIVES ================ -->
<#macro "container">
{
  "@type": "Container",
  "id": "${.node['@id']!}",
  "style": "${.node['@style']!}",
  <#-- CLEAN: Outputs "agi-ide#agi-ide-header" -->
  "mariaId": "${getCleanPath()}#${.node['@id']!'container-' + qmetaElementCounter}",
  "children": [
    <#list .node?children as childNode>
      <#recurse childNode><#if childNode?has_next>,</#if>
    </#list>
  ]
}
</#macro>

<#macro "container-box">
{
  "@type": "ContainerBox",
  "id": "${.node['@id']!}",
  "title": "${.node['box-header'][0]['@title']!}",
  "mariaId": "${sri.getActiveScreenDef().getLocation()}#${.node['@id']!''}",
  "children": [
    <#list .node?children as childNode>
      <#recurse childNode><#if childNode?has_next>,</#if>
    </#list>
  ]
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
  "children": [
    <#list .node?children as childNode>
      <#recurse childNode><#if childNode?has_next>,</#if>
    </#list>
  ]
}
</#macro>

<#macro "field">
{
  "@type": "FormField",
  "name": "${.node['@name']!}",
  "title": "${.node['@title']!((.node['@name']?replace('^[a-z]', '', 'r'))?cap_first)}",
  "mariaId": "${sri.getActiveScreenDef().getLocation()}#${.node['@name']!}",
  "children": [
    <#list .node?children as childNode>
      <#recurse childNode><#if childNode?has_next>,</#if>
    </#list>
  ]
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
  "children": [<#recurse>]
}
</#macro>

<#macro "screen-header">
{
  "@type": "m-screen-header",
  "attributes": {
    "elevated": ${.node['@elevated']!'true'}
  },
  "children": [<#recurse>]
}
</#macro>

<#macro "screen-toolbar">
{
  "@type": "m-screen-toolbar",
  "children": [<#recurse>]
}
</#macro>

<#macro "screen-content">
{
  "@type": "m-screen-content",
  "children": [<#recurse>]
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

<#-- ================ 9. CORE SUBSCREENS PLACEHOLDER HOOK ================ -->
<#macro "subscreens-active">
{
  "@type": "m-subscreens-active",
  "attributes": {
    "pathIndex": "${.node['@pathIndex']!'-1'}"
  }
}
</#macro>