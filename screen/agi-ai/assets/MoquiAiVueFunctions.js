// Ensure our core global namespace layers are initialized safely
window.moqui = window.moqui || {};
window.AgiVueAppFunctionMap = Object.assign(window.AgiVueAppFunctionList || {}, {

    sendMessage(...args) {
        const m = window.moqui?.rootSetup?.()?.methods?.sendMessage;
        return m ? m(...args) : console.warn("MCE: sendMessage called before bridge ready");
    },

    saveProperty(...args) {
        const m = window.moqui?.rootSetup?.()?.methods?.saveProperty;
        return m ? m(...args) : null;
    },

    switchProject(...args) {
        const m = window.moqui?.rootSetup?.()?.methods?.switchProject;
        return m ? m(...args) : null;
    },

    async fetchAvailableApps() {
        try {
            const response = await fetch('/rest/s1/agi-ai/AvailableApps', {
                method: 'GET',
                headers: {
                    'Accept': 'application/json',
                    'X-CSRF-Token': this.moquiSessionToken || $("#confMoquiSessionToken").val()
                }
            });
            const data = await response.json();
            if (this.aiTreeStore) this.aiTreeStore.availableApps = data.apps || [];
            else if (moqui.useAiTreeStore) {
                const store = moqui.useAiTreeStore();
                store.availableApps = data.apps || [];
            }
        } catch (e) { console.warn("Failed to fetch available orchestrator apps:", e); }
    },

    toggleArchitectMode: function (val, targetPath) {
        console.log('!!! toggleArchitectMode METHOD CALLED:', val, 'for path:', targetPath);
        if (this.aiTreeStore) {
            this.aiTreeStore.isArchitectMode = val;
            if (targetPath) this.aiTreeStore.targetPath = targetPath;
        }
        this.isArchitectMode = val;

        if (val && targetPath && targetPath !== '/ScreenBuilder') {
            this.$router.push('/ScreenBuilder?targetPath=' + targetPath);
        } else if (!val && this.$router.currentRoute.value.path === '/ScreenBuilder') {
            const goBack = (this.aiTreeStore && this.aiTreeStore.targetPath) ? this.aiTreeStore.targetPath : '/Home';
            this.$router.push(goBack);
        } else {
            this.reloadSubscreens();
        }
    },

    setUrl: function (url, bodyParameters, onComplete, pushState = true) {
        url = this.getLinkPath(url);

        const normUrl = url.endsWith('/') && url.length > 1 ? url.slice(0, -1) : url;
        const normCur = this.currentLinkUrl.endsWith('/') && this.currentLinkUrl.length > 1 ? this.currentLinkUrl.slice(0, -1) : this.currentLinkUrl;

        if (this.currentLoadRequest && this.loadingUrl && this.getLinkPath(this.loadingUrl) !== url && normUrl !== normCur) {
            console.log("Aborting load for " + this.loadingUrl + " because navigating to " + url);
            this.currentLoadRequest.abort();
            this.currentLoadRequest = null;
            this.loading = 0;
        }
        this.loadingUrl = url;
        this.bodyParameters = bodyParameters;

        console.info('setting url ' + url + ', cur ' + this.currentLinkUrl);

        if (normUrl === normCur) {
            this.reloadSubscreens();
            if (onComplete) this.callOnComplete(onComplete, this.currentPath);
        } else {
            var redirectedFrom = this.currentPath;
            var urlInfo = moqui.parseHref(url);
            this.extraPathList = [];
            this.currentSearch = urlInfo.search;
            this.currentPath = urlInfo.path;
            this.committedUrl = this.currentLinkUrl;

            var srch = this.currentSearch;
            var screenUrl = this.currentPath + (srch.length > 0 ? '?' + srch : '');
            if (!screenUrl || screenUrl.length === 0) return;

            this.committedUrl = this.currentLinkUrl;
            console.info("Current URL changing to " + screenUrl);
            this.lastNavTime = Date.now();
            this.activeContainers = {};

            var vm = this;
            if (screenUrl.includes("/rest/")) {
                console.info("MCE: Skipping menu loading for REST path:", screenUrl);
                if (onComplete) vm.callOnComplete(onComplete, redirectedFrom);
                return;
            }

            var purePath = this.appRootPath && this.appRootPath.length > 0 && screenUrl.indexOf(this.appRootPath) === 0 ?
                screenUrl.slice(this.appRootPath.length).replace(/^\//, '') : screenUrl.replace(/^\//, '');
            var rootPrefix = this.appRootPath && this.appRootPath !== '/' ? this.appRootPath : '';
            var menuDataUrl = rootPrefix + "/menuDataQvt/" + purePath;

            if (this.loadingMenuUrl === menuDataUrl) return;
            this.loadingMenuUrl = menuDataUrl;
            if (this.currentMenuRequest) this.currentMenuRequest.abort();

            this.currentMenuRequest = $.ajax({
                type: "GET", url: menuDataUrl, dataType: "text", contentType: "application/json", error: function (jqXHR, textStatus, errorThrown) {
                    vm.loadingMenuUrl = null;
                    vm.currentMenuRequest = null;
                    if (textStatus === 'abort') return;
                    moqui.handleAjaxError(jqXHR, textStatus, errorThrown);
                }, success: function (outerListText) {
                    vm.loadingMenuUrl = null;
                    vm.currentMenuRequest = null;
                    var outerList = null;
                    try { outerList = JSON.parse(outerListText); } catch (e) { console.info("Error parson menu list JSON: " + e); }
                    if (outerList && moqui.isArray(outerList)) {
                        vm.navMenuList = outerList;
                        if (onComplete) vm.callOnComplete(onComplete, redirectedFrom);
                    }
                }
            });

            if (pushState) {
                if (this.$router) {
                    var routerUrl = url;
                    if (this.appRootPath && routerUrl.indexOf(this.appRootPath) === 0) {
                        routerUrl = routerUrl.substring(this.appRootPath.length);
                        if (!routerUrl.startsWith('/')) routerUrl = '/' + routerUrl;
                    }
                    var pushResult = this.$router.push(routerUrl);
                    if (pushResult && typeof pushResult.catch === 'function') {
                        pushResult.catch(e => { console.error('Router push error', e); });
                    }
                } else {
                    window.history.pushState(null, this.ScreenTitle, url);
                }
            }
            this.urlListeners.forEach(function (callback) { callback(url, this) }, this);
            document.documentElement.scrollTop = 0;
            document.body.scrollTop = 0;
        }
    },

    callOnComplete: function (onComplete, redirectedFrom) {
        if (!onComplete) return;
        var route = this.getRoute();
        if (redirectedFrom) route.redirectedFrom = redirectedFrom;
        onComplete(route);
    },

    getRoute: function () {
        return {
            name: this.currentPathList[this.currentPathList.length - 1], meta: {}, path: this.currentPath,
            hash: '', query: this.currentParameters, params: this.bodyParameters || {}, fullPath: this.currentLinkUrl, matched: []
        };
    },

    setParameters: function (parmObj) {
        if (parmObj) {
            this.$root.currentParameters = $.extend({}, this.$root.currentParameters, parmObj);
            var curUrl = this.currentLinkUrl;
            var curHistoryItem = this.navHistoryList[0];
            if (curHistoryItem) {
                curHistoryItem.pathWithParams = curUrl;
                window.history.pushState(null, curHistoryItem.title || '', curUrl);
            } else {
                window.history.pushState(null, '', curUrl);
            }
        }
        this.$root.reloadSubscreens();
    },

    addSubscreen: function (saComp) {
        let pathIdx = saComp.activePathIndex;
        if (pathIdx === -1 || pathIdx === undefined) {
            pathIdx = this.activeSubscreens.length;
            saComp.activePathIndex = pathIdx;
        }

        const existingIdx = this.activeSubscreens.findIndex(s => s.activePathIndex === pathIdx);
        if (existingIdx !== -1) {
            const existing = this.activeSubscreens[existingIdx];
            if (existing !== saComp) {
                let isParent = false;
                let p = saComp.$parent;
                while (p) { if (p === existing) { isParent = true; break; } p = p.$parent; }

                if (isParent) {
                    console.warn(`addSubscreen: Index collision! Child at index ${pathIdx} tried to replace parent. Adjusting child index.`);
                    saComp.activePathIndex++;
                    this.addSubscreen(saComp);
                    return;
                }

                console.info(`addSubscreen: Replacing stale component at index ${pathIdx}`);
                this.activeSubscreens.splice(existingIdx, 1, saComp);
            }
        } else {
            this.activeSubscreens.push(saComp);
        }

        if (this.currentPathList && this.currentPathList.length > pathIdx && this.currentPathList[pathIdx]) {
            console.log(`addSubscreen triggering loadActive for index ${pathIdx} path: ${this.currentPathList[pathIdx]}`);
            saComp.loadActive();
        }
        return;
    },

    removeSubscreen: function (saComp) {
        var idx = this.activeSubscreens.indexOf(saComp);
        if (idx >= 0) this.activeSubscreens.splice(idx, 1);
    },

    reloadSubscreens: function () {
        var fullPathList = this.currentPathList;
        var activeSubscreens = this.activeSubscreens;
        console.info("reloadSubscreens currentPathList " + JSON.stringify(this.currentPathList));
        if (fullPathList.length === 0 && activeSubscreens.length > 0) {
            activeSubscreens.splice(1);
            activeSubscreens[0].loadActive();
            return;
        }
        for (var i = 0; i < activeSubscreens.length; i++) {
            if (i >= fullPathList.length) break;
            var loaded = activeSubscreens[i].loadActive();
            if (loaded) activeSubscreens.splice(i + 1);
        }
    },

    goPreviousScreen: function () {
        var currentPath = this.currentPath;
        var navHistoryList = this.navHistoryList;
        var prevHist;
        for (var hi = 0; hi < navHistoryList.length; hi++) {
            if (navHistoryList[hi].pathWithParams.indexOf(currentPath) < 0) { prevHist = navHistoryList[hi]; break; }
        }
        if (prevHist && prevHist.pathWithParams && prevHist.pathWithParams.length) this.setUrl(prevHist.pathWithParams)
    },

    addContainer: function (contId, comp) { this.activeContainers[contId] = comp; },

    reloadContainer: function (contId) {
        var contComp = this.activeContainers[contId];
        if (contComp) { contComp.reload(); } else { console.error("Container with ID " + contId + " not found, not reloading"); }
    },

    loadContainer: function (contId, url) {
        var contComp = this.activeContainers[contId];
        if (contComp) { contComp.load(url); } else { console.error("Container with ID " + contId + " not found, not loading url " + url); }
    },

    hideContainer: function (contId) {
        var contComp = this.activeContainers[contId];
        if (contComp) { contComp.hide(); } else { console.error("Container with ID " + contId + " not found, not hidding"); }
    },

    addNavPlugin: function (url) { var vm = this; moqui.loadComponent(this.appRootPath + url, function (comp) { vm.navPlugins.push(comp); }) },

    addNavPluginsWait: function (urlList, urlIndex) {
        if (urlList && urlList.length > urlIndex) {
            this.addNavPlugin(urlList[urlIndex]);
            var vm = this;
            if (urlList.length > (urlIndex + 1)) { setTimeout(function () { vm.addNavPluginsWait(urlList, urlIndex + 1); }, 500); }
        }
    },

    addAccountPlugin: function (url) { var vm = this; moqui.loadComponent(this.appRootPath + url, function (comp) { vm.accountPlugins.push(comp); }) },

    addAccountPluginsWait: function (urlList, urlIndex) {
        if (urlList && urlList.length > urlIndex) {
            this.addAccountPlugin(urlList[urlIndex]);
            var vm = this;
            if (urlList.length > (urlIndex + 1)) { setTimeout(function () { vm.addAccountPluginsWait(urlList, urlIndex + 1); }, 500); }
        }
    },

    addUrlListener: function (urlListenerFunction) {
        if (this.urlListeners.indexOf(urlListenerFunction) >= 0) return;
        this.urlListeners.push(urlListenerFunction);
    },

    addNotify: function (message, type, link, icon) {
        var qType = this.getQuasarColor ? this.getQuasarColor(type) : type;
        if (!qType || !qType.length) qType = 'info';

        var histList = this.notifyHistoryList.slice(0);
        var nowDate = new Date();
        var nh = String(nowDate.getHours()).padStart(2, '0');
        var nm = String(nowDate.getMinutes()).padStart(2, '0');

        histList.unshift({ message: message, type: qType, time: (nh + ':' + nm), link: link, icon: icon });
        while (histList.length > 25) { histList.pop(); }
        this.notifyHistoryList = histList;
    },

    switchDarkLight: function () {
        this.$q.dark.toggle();
        $.ajax({
            type: 'POST', url: (this.appRootPath + '/apps/setPreference'), error: moqui.handleAjaxError,
            data: { moquiSessionToken: this.moquiSessionToken, preferenceKey: 'QUASAR_DARK', preferenceValue: (this.$q.dark.isActive ? 'true' : 'false') }
        });
    },

    toggleLeftOpen: function () {
        this.leftOpen = !this.leftOpen;
        $.ajax({
            type: 'POST', url: (this.appRootPath + '/apps/setPreference'), error: moqui.handleAjaxError,
            data: { moquiSessionToken: this.moquiSessionToken, preferenceKey: 'QUASAR_LEFT_OPEN', preferenceValue: (this.leftOpen ? 'true' : 'false') }
        });
    },

    stopProp: function (e) { e.stopPropagation(); },

    getNavHref: function (navIndex) {
        if (!navIndex) navIndex = this.navMenuList.length - 1;
        var navMenu = this.navMenuList[navIndex];
        if (navMenu.extraPathList && navMenu.extraPathList.length) {
            var href = navMenu.path + '/' + navMenu.extraPathList.join('/');
            var questionIdx = navMenu.pathWithParams.indexOf("?");
            if (questionIdx > 0) { href += navMenu.pathWithParams.slice(questionIdx); }
            return href;
        } else {
            return navMenu.pathWithParams || navMenu.path;
        }
    },

    getLinkPath: function (path) {
        if (moqui.isPlainObject(path)) path = moqui.makeHref(path);
        if (!path || path.length === 0) return path;

        if (path.indexOf("http") === 0) {
            try {
                const urlObj = new URL(path);
                path = urlObj.pathname + urlObj.search + urlObj.hash;
            } catch (e) { console.warn("Invalid URL in getLinkPath:", path); }
        }

        if (!path.startsWith("/")) path = "/" + path;
        if (this.linkBasePath === this.appRootPath) return path;

        if (this.appRootPath && this.appRootPath !== '/' && this.appRootPath !== this.linkBasePath) {
            if (path.indexOf(this.appRootPath) === 0) {
                var relPath = path.substring(this.appRootPath.length);
                if (!relPath.startsWith("/")) relPath = "/" + relPath;
                path = this.linkBasePath + relPath;
            } else if (path.indexOf(this.linkBasePath) !== 0) {
                path = this.linkBasePath + (path.startsWith('/') ? '' : '/') + path;
            }
        }
        return path;
    },

    getQuasarColor: function (bootstrapColor) { return moqui.getQuasarColor(bootstrapColor); },

    getCsrfToken: function (jqXHR) {
        var sessionToken = jqXHR.getResponseHeader("X-CSRF-Token");
        if (sessionToken && sessionToken.length && sessionToken !== this.moquiSessionToken) {
            this.moquiSessionToken = sessionToken;
            this.sessionTokenBc.postMessage(sessionToken);
        }
    },

    receiveBcCsrfToken: function (event) {
        var sessionToken = event.data;
        if (sessionToken && sessionToken.length && this.moquiSessionToken !== sessionToken) {
            this.moquiSessionToken = sessionToken;
        }
    },

    reLoginCheckShow: function () {
        this.reLoginShowDialog();
    },

    reLoginShowDialog: function () {
        this.reLoginMfaData = null;
        this.reLoginOtp = null;
        this.reLoginShow = true;
    },

    reLoginPostLogin: function () {
        this.reLoginShow = false;
        this.reLoginPassword = null;
        this.reLoginOtp = null;
        this.reLoginMfaData = null;
        var msg = 'Background login successful';
        this.$q.notify({ timeout: 12000, type: 'positive', message: msg });
        this.addNotify(msg, 'positive');
    },

    reLoginSubmit: function () {
        $.ajax({
            type: 'POST', url: (this.appRootPath + '/rest/login'), error: moqui.handleAjaxError, success: this.reLoginHandleResponse,
            dataType: 'json', headers: { Accept: 'application/json' }, xhrFields: { withCredentials: true },
            data: { username: this.username, password: this.reLoginPassword }
        });
    },

    reLoginHandleResponse: function (resp, status, jqXHR) {
        this.getCsrfToken(jqXHR);
        if (resp.secondFactorRequired) {
            this.reLoginMfaData = resp;
        } else if (resp.loggedIn) {
            this.reLoginPostLogin();
        }
    },

    reLoginReload: function () {
        if (confirm("Reload page? All changes will be lost."))
            window.location.href = this.currentLinkUrl;
    },

    reLoginSendOtp: function (factorId) {
        $.ajax({
            type: 'POST', url: (this.appRootPath + '/rest/sendOtp'), error: moqui.handleAjaxError, success: this.reLoginSendOtpResponse,
            dataType: 'json', headers: { Accept: 'application/json' }, xhrFields: { withCredentials: true },
            data: { moquiSessionToken: this.moquiSessionToken, factorId: factorId }
        });
    },

    reLoginSendOtpResponse: function (resp, status, jqXHR) {
        if (resp) moqui.notifyMessages(resp.messages, resp.errors, resp.validationErrors);
    },

    reLoginVerifyOtp: function () {
        $.ajax({
            type: 'POST', url: (this.appRootPath + '/rest/verifyOtp'), error: moqui.handleAjaxError, success: this.reLoginVerifyOtpResponse,
            dataType: 'json', headers: { Accept: 'application/json' }, xhrFields: { withCredentials: true },
            data: { moquiSessionToken: this.moquiSessionToken, code: this.reLoginOtp }
        });
    },

    reLoginVerifyOtpResponse: function (resp, status, jqXHR) {
        this.getCsrfToken(jqXHR);
        if (resp.loggedIn) {
            this.reLoginPostLogin();
        }
    },

    qLayoutMinHeight: function (offset) {
        return { minHeight: offset ? `calc(100vh - ${offset}px)` : '100vh' }
    },

    async handleArtifactSelection(component, artifactPath) {
        if (!component || !artifactPath) return;
        console.info(`📁 [AGI IDE] Hydrating metadata tree for: [${component}] -> ${artifactPath}`);
        this.loading++;
        try {
            const url = `/apps/agiide/IdeWorkspace/loadArtifactJson?targetComponent=${encodeURIComponent(component)}&artifactPath=${encodeURIComponent(artifactPath)}`;
            const response = await fetch(url, {
                method: 'GET',
                headers: {
                    'Accept': 'application/json',
                    'X-CSRF-Token': this.moquiSessionToken
                }
            });
            const schemaJson = await response.json();
            if (this.aiTreeStore) {
                this.aiTreeStore.activeBlueprintJson = schemaJson;
            }
            console.log("🎯 [AGI IDE] Visual stage context fully hydrated.");
        } catch (err) {
            console.error("❌ [AGI IDE ERROR] Failed to fetch artifact mapping from Moqui server:", err);
        } finally {
            this.loading--;
        }
    },

    sendWorkspaceMessage() {
        const text = this.aiTreeStore?.chatInput;
        const activeProject = this.fields?.targetComponent;
        const currentArtifact = this.fields?.selectedArtifact;

        if (!text || !text.trim()) return;
        console.log(`✉️ [AGI IDE SEND] Piping instruction to host plane: "${text}"`);

        const streamContainer = document.getElementById('ide-chat-stream');
        if (streamContainer) {
            streamContainer.innerHTML += `<div class="q-mb-sm text-right"><span class="bg-indigo-1 q-pa-sm rounded-borders inline-block text-body2 text-indigo-10">${text}</span></div>`;
        }

        if (window.webmcp && window.webmcp.readyState === WebSocket.OPEN) {
            window.webmcp.send(JSON.stringify({
                type: 'userMessage',
                componentId: 'agi-ide',
                channel: window.location.pathname,
                text: text,
                targetComponent: activeProject,
                artifactPath: currentArtifact
            }));
        }
        if (this.aiTreeStore) this.aiTreeStore.chatInput = '';
    }
});