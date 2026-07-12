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
    moqui.loadComponent = function (urlInfo, callback, containerId) {
        var url = typeof urlInfo === 'string' ? urlInfo : urlInfo.path;

        var queryParams = {};
        if (typeof urlInfo === 'object') {
            if (urlInfo.lastStandalone !== undefined) queryParams.lastStandalone = urlInfo.lastStandalone;
            if (urlInfo.extraPath) url += '/' + urlInfo.extraPath;

            // 🎯 TARGETED DISPATCH: This flag trips your MoquiConf.xml interceptor instantly!
            queryParams.renderMode = 'qmeta';

            if (urlInfo.search) {
                var searchParts = urlInfo.search.split('&');
                searchParts.forEach(function (p) {
                    var pair = p.split('=');
                    if (pair[0]) queryParams[pair[0]] = pair[1] || '';
                });
            }
        }

        var queryString = moqui.objToSearch(queryParams);
        if (queryString.length > 0) {
            url += (url.indexOf('?') > 0 ? '&' : '?') + queryString;
        }

        var finalUrl = window.moqui?.webrootVue?.getLinkPath?.(url) || url;

        return $.ajax({
            type: "GET",
            url: finalUrl,
            dataType: "text",
            headers: {
                'Accept': 'application/json', // Signal programmatic consumption
                'moquiSessionToken': window.AGI_SERVER_CSRF_TOKEN || ""
            },
            error: function (jqXHR, textStatus, errorThrown) {
                moqui.handleAjaxError(jqXHR, textStatus, errorThrown);
            },
            success: function (componentText) {
                // Vue parses and compiles your clean layout nodes here
                try {
                    var componentOptions = { template: componentText };
                    callback(Vue.defineComponent(componentOptions));
                } catch (err) {
                    console.error("Layout compile exception:", err);
                }
            }
        });
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