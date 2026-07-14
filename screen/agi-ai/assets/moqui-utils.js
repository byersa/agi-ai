/**
 * moqui-utils.js
 * Stateless Core Engine Utilities
 */
(function () {
    window.moqui = window.moqui || {};

    moqui.isArray = Array.isArray || function (obj) { return Object.prototype.toString.call(obj) === '[object Array]'; };

    moqui.parseHref = function (href) {
        var questionIdx = href.indexOf("?");
        if (questionIdx > 0) {
            return { path: href.substring(0, questionIdx), search: href.substring(questionIdx + 1) };
        }
        return { path: href, search: "" };
    };

    moqui.objToSearch = function (obj) {
        if (!obj) return "";
        var parts = [];
        for (var key in obj) {
            if (obj.hasOwnProperty(key) && obj[key] !== null && obj[key] !== undefined) {
                parts.push(encodeURIComponent(key) + "=" + encodeURIComponent(obj[key]));
            }
        }
        return parts.join("&");
    };

    moqui.handleAjaxError = function (jqXHR, textStatus, errorThrown) {
        console.error("Core Network Error:", textStatus, errorThrown);
    };

    // 🎯 THE MISSING LINK: The primary asynchronous asset downloading factory
    moqui.loadComponent = function (urlInfo, callback) {
        // 1. Build the target URL path
        var url = urlInfo.path;
        if (urlInfo.extraPath) {
            if (!url.endsWith('/') && !urlInfo.extraPath.startsWith('/')) url += '/';
            url += urlInfo.extraPath;
        }

        // 2. Append query search parameters
        var search = urlInfo.search || "";
        if (search) {
            if (search.charAt(0) !== '?') search = '?' + search;
            url += search;
        }

        // 3. Append lastStandalone marker if provided (crucial for Moqui layout compilation)
        if (urlInfo.lastStandalone) {
            var separator = url.indexOf('?') !== -1 ? '&' : '?';
            url += separator + "lastStandalone=" + urlInfo.lastStandalone;
        }

        var reqData = urlInfo.bodyParameters || null;
        var httpMethod = reqData ? "POST" : "GET";

        // 4. Fire standard AJAX request to retrieve the target subscreen
        $.ajax({
            type: httpMethod,
            url: url,
            data: reqData,
            // Accept HTML/JSON and inject CSRF security token into headers
            headers: {
                "Accept": "text/html, application/json",
                "X-CSRF-Token": window.AGI_SERVER_CSRF_TOKEN || ""
            },
            error: function (jqXHR, textStatus, errorThrown) {
                console.error("❌ [Moqui] Failed to load subscreen component from: " + url, errorThrown);
                callback(Vue.markRaw(moqui.EmptyComponent));
            },
            success: function (responseText) {
                handleResponse(responseText);
            }
        });

        // 5. Callback handler that dynamically routes JSON layouts vs standard HTML templates
        const handleResponse = function (responseText) {
            if (!responseText) {
                callback(Vue.markRaw(moqui.EmptyComponent));
                return;
            }

            // A. Check if the response is JSON (or wrapped in optional whitespace)
            const trimmed = responseText.trim();
            if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
                try {
                    const parsedMetaJson = JSON.parse(trimmed);

                    // Wrap the parsed JSON layout directly inside our Blueprint Compiler hook
                    const metaBlueprintComponent = {
                        name: 'DynamicMetaSubscreen',
                        data() {
                            return {
                                subscreenTree: parsedMetaJson.widgets || parsedMetaJson
                            };
                        },
                        // We render a pure virtual node calling our blueprint system, skipping HTML string compiling!
                        render() {
                            const blueprintComp = window.AgiComponents['m-blueprint-node'];
                            if (!blueprintComp) {
                                console.error("❌ Blueprint Compiler ('m-blueprint-node') is not initialized.");
                                return null;
                            }
                            return Vue.h(blueprintComp, {
                                node: this.subscreenTree,
                                context: this.$parent?.context || {}
                            });
                        }
                    };

                    // Hand a clean, functional layout component back to the caller
                    callback(Vue.markRaw(metaBlueprintComponent));
                    return;

                } catch (jsonErr) {
                    console.error("⚠️ Failed parsing response as Meta-JSON layout. Falling back to native loader.", jsonErr);
                }
            }

            // B. LEGACY FALLBACK: Treat it as standard Moqui Vue HTML/JS template component text
            try {
                // Parse out embedded script elements cleanly from legacy HTML/Vue responses
                var scriptText = "";
                var tempDiv = document.createElement('div');
                tempDiv.innerHTML = responseText;

                var scripts = tempDiv.getElementsByTagName('script');
                for (var i = 0; i < scripts.length; i++) {
                    scriptText += scripts[i].innerHTML + "\n";
                }

                // Strip scripts from raw template content to avoid duplicate executions in the DOM
                for (var j = scripts.length - 1; j >= 0; j--) {
                    scripts[j].parentNode.removeChild(scripts[j]);
                }

                var cleanHtml = tempDiv.innerHTML;
                var compOpts = {};

                // Evaluate and merge the legacy component's setup script block
                if (scriptText.trim().length > 0) {
                    try {
                        var scriptFunc = new Function(scriptText);
                        compOpts = scriptFunc() || {};
                    } catch (scriptErr) {
                        console.error("❌ Error compiling script block in legacy component: " + url, scriptErr);
                    }
                }

                compOpts.template = cleanHtml;
                callback(Vue.markRaw(compOpts));

            } catch (err) {
                console.error("❌ Failed compiling legacy template fallback in loadComponent:", err);

                // Last ditch effort: assign raw template string
                const fallbackComponent = {
                    template: responseText
                };
                callback(Vue.markRaw(fallbackComponent));
            }
        };
    };

    // =========================================================================
    // TYPE CHECKING PRIMITIVES (Extracted from PopRestStore utilities.js)
    // =========================================================================

    // 1. The primary type-checker required by getLinkPath
    moqui.isPlainObject = function (obj) {
        return Object.prototype.toString.call(obj) === '[object Object]';
    };

    // 2. Rugged value equivalence checking (handles string/number/null mismatches)
    moqui.equalsOrPlaceholder = function (val1, val2) {
        if (val1 === val2) return true;
        if ((val1 === null || val1 === undefined || val1 === '') &&
            (val2 === null || val2 === undefined || val2 === '')) return true;
        return String(val1) === String(val2);
    };

    // 3. Structural array comparison (used heavily by your m-form field changed trackers)
    moqui.arraysEqual = function (a, b, enforceOrder) {
        if (a === b) return true;
        if (a == null || b == null) return false;
        if (a.length !== b.length) return false;

        var arr1 = enforceOrder ? a : a.slice().sort();
        var arr2 = enforceOrder ? b : b.slice().sort();

        for (var i = 0; i < arr1.length; ++i) {
            if (arr1[i] !== arr2[i]) return false;
        }
        return true;
    };

    // 4. Object literal deep copying (prevents input fields from mutating original source data records)
    moqui.deepCopy = function (obj) {
        if (obj === null || typeof obj !== 'object') return obj;
        if (Array.isArray(obj)) {
            var copyArr = [];
            for (var i = 0; i < obj.length; i++) copyArr[i] = moqui.deepCopy(obj[i]);
            return copyArr;
        }
        var copyObj = {};
        for (var key in obj) {
            if (obj.hasOwnProperty(key)) copyObj[key] = moqui.deepCopy(obj[key]);
        }
        return copyObj;
    };

    console.info("⚙️ [MoquiUtils] Core stateless functions attached to window.moqui namespace.");
})();