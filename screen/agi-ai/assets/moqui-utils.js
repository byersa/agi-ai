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
        var search = typeof urlInfo === 'object' && urlInfo.search ? urlInfo.search : "";

        if (search.length > 0) url += (url.indexOf('?') > 0 ? '&' : '?') + search;

        // 🎯 FIX: Defensively pull the path translator straight from the running root instance context
        var finalUrl = url;
        var rootApp = window.moqui?.webrootVue;

        if (rootApp && typeof rootApp.getLinkPath === 'function') {
            // Run getLinkPath explicitly bound to rootApp so 'this' hooks resolve perfectly
            finalUrl = rootApp.getLinkPath(url);
        }

        return $.ajax({
            type: "GET",
            url: finalUrl,
            dataType: "text",
            headers: {
                'Accept': 'text/plain',
                'moquiSessionToken': window.AGI_SERVER_CSRF_TOKEN || ""
            },
            error: function (jqXHR, textStatus, errorThrown) {
                moqui.handleAjaxError(jqXHR, textStatus, errorThrown);
            },
            success: function (componentText) {
                try {
                    var componentOptions = {
                        template: componentText
                    };
                    callback(Vue.defineComponent(componentOptions));
                } catch (err) {
                    console.error("Failed to parse component macro text stream:", err);
                }
            }
        });
    };

    console.info("⚙️ [MoquiUtils] Core stateless functions attached to window.moqui namespace.");
})();