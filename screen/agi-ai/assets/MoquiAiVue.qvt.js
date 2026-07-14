window.AgiComponents = window.AgiComponents || {};
window.moqui = Object.assign(window.moqui || {}, {
    urlExtensions: { js: 'qjs', vue: 'qvue', vuet: 'qvt', qvt: 'qvt' }
});

/* ==========================================================================
   1. CUSTOM LAYOUT COMPONENTS
   ========================================================================== */

window.AgiComponents['m-screen-layout'] = {
    props: { view: { type: String, default: 'hHh lpR fFf' } },
    inject: { parentLayout: { default: null }, inSubscreensActive: { default: false } },
    provide() { return { parentLayout: this }; },
    template: `
        <div v-if="parentLayout || inSubscreensActive" class="column full-height overflow-hidden blueprint-nested-layout" v-bind="$attrs">
            <slot></slot>
        </div>
        <q-layout v-else :view="view" v-bind="$attrs">
            <slot></slot>
        </q-layout>
    `
};

window.AgiComponents['m-screen-header'] = {
    props: { elevated: { type: Boolean, default: true } },
    inject: { parentLayout: { default: null }, inSubscreensActive: { default: false } },
    template: `
        <div v-if="parentLayout || inSubscreensActive" class="blueprint-nested-header" :class="{'sticky-top shadow-2': elevated}" v-bind="$attrs">
            <slot></slot>
        </div>
        <q-header v-else :elevated="elevated" class="bg-primary text-white" style="z-index: 2000;">
            <slot></slot>
        </q-header>
    `
};
window.AgiComponents['screen-header'] = window.AgiComponents['m-screen-header'];

window.AgiComponents['m-screen-drawer'] = {
    props: { side: { type: String, default: 'left' }, modelValue: { type: Boolean, default: false }, behavior: { type: String, default: 'default' } },
    emits: ['update:modelValue'],
    template: '<q-drawer :side="side" :behavior="behavior" :model-value="modelValue" @update:model-value="$emit(\'update:modelValue\', $event)"><slot></slot></q-drawer>'
};

window.AgiComponents['m-screen-toolbar'] = {
    template: '<q-toolbar><slot></slot></q-toolbar>'
};
window.AgiComponents['screen-toolbar'] = window.AgiComponents['m-screen-toolbar'];

window.AgiComponents['m-screen-content'] = {
    inject: { parentLayout: { default: null }, inSubscreensActive: { default: false } },
    template: `
        <div v-if="parentLayout || inSubscreensActive" class="col-grow overflow-auto blueprint-nested-content" v-bind="$attrs">
            <slot></slot>
        </div>
        <q-page-container v-else v-bind="$attrs">
            <q-page class="q-pa-md">
                <slot></slot>
            </q-page>
        </q-page-container>
    `
};

/* ==========================================================================
   2. INTERACTIVE CANVAS & NAV COMPONENTS
   ========================================================================== */

window.AgiComponents['m-architect-view-port'] = {
    name: "mArchitectViewPort",
    props: { screenData: [Object, String], specPath: String },
    template: `
        <div class="architect-view-port">
            <transition enter-active-class="animated fadeIn" leave-active-class="animated fadeOut" mode="out-in">
                <div>
                    <blueprint-renderer 
                        :screen-data="parsedScreenData" 
                        :spec-path="specPath" 
                        @select-component="selectComponent" />
                </div>
            </transition>
        </div>
    `,
    methods: {
        selectComponent(comp) {
            if (this.$root && typeof this.$root.selectComponent === 'function') {
                this.$root.selectComponent(comp);
            }
        }
    },
    computed: {
        parsedScreenData() {
            let data = this.screenData;
            if (typeof data === 'string' && data.length > 0) {
                try { data = JSON.parse(data); } catch (e) {
                    console.error("Failed to parse screenData string in m-architect-view-port", e);
                    return null;
                }
            }
            return data;
        }
    }
};

window.AgiComponents['m-menu-item'] = {
    props: { name: String, href: String, text: String, label: String, icon: String, buttonClass: String },
    computed: {
        resolvedHref() {
            if (this.href) return this.href;
            if (this.name && this.$root?.navMenuList) {
                const rootSub = this.$root.navMenuList[0]?.subscreens;
                const sub = rootSub?.find(s => s.name === this.name);
                return sub?.pathWithParams || sub?.path;
            }
            return null;
        }
    },
    template: '<m-link :href="resolvedHref"><q-btn flat stretch no-caps :label="text || label" :icon="icon" :class="buttonClass" color="white"></q-btn></m-link>'
};
window.AgiComponents['menu-item'] = window.AgiComponents['m-menu-item'];

window.AgiComponents['m-subscreens-menu'] = {
    props: { type: { type: String, default: 'drawer' }, pathIndex: { type: [Number, String], default: null } },
    computed: {
        menuList() {
            if (!this.$root?.navMenuList) return [];
            const navList = this.$root.navMenuList;
            if (this.pathIndex !== null && this.pathIndex !== undefined) {
                const idx = parseInt(this.pathIndex);
                if (navList && navList.length > idx) {
                    const item = navList[idx];
                    if (item && item.subscreens) return item.subscreens;
                }
                return [];
            }
            return navList;
        }
    },
    template: `
        <div v-if="type === 'toolbar'" class="row no-wrap items-center">
          <template v-for="(item, index) in menuList" :key="index">
            <q-btn v-if="item.subscreens && item.subscreens.length" flat stretch :label="item.title" :icon="item.image">
              <q-menu>
                <q-list>
                  <q-item clickable v-close-popup v-for="(sub, subIndex) in item.subscreens" :key="subIndex" :to="sub.path" :active="sub.active" :class="sub.active ? 'text-primary bg-blue-1' : 'text-grey-9'">
                    <q-item-section avatar v-if="sub.image"><q-icon :name="sub.image" /></q-item-section>
                    <q-item-section>{{ sub.title }}</q-item-section>
                  </q-item>
                </q-list>
              </q-menu>
            </q-btn>
            <q-btn v-else flat stretch :label="item.title" :icon="item.image" :to="item.path" :class="item.active ? 'bg-white text-primary' : ''"/>
          </template>
        </div>
        <q-list v-else class="text-grey-9">
          <template v-for="(item, index) in menuList" :key="index">
            <q-expansion-item v-if="item.subscreens && item.subscreens.length" :label="item.title" :icon="item.image" default-opened header-class="text-primary">
              <q-list class="q-pl-md">
                <q-item clickable v-ripple v-for="(sub, subIndex) in item.subscreens" :key="subIndex" :to="sub.path" :active="sub.active" :class="sub.active ? 'text-primary bg-blue-1' : 'text-grey-8'">
                   <q-item-section avatar v-if="sub.image"><q-icon :name="sub.image" /></q-item-section>
                   <q-item-section>{{ sub.title }}</q-item-section>
                </q-item>
              </q-list>
            </q-expansion-item>
            <q-item v-else clickable v-ripple :to="item.path" :active="item.active" :class="item.active ? 'text-primary bg-blue-1' : 'text-grey-8'">
              <q-item-section avatar v-if="item.image"><q-icon :name="item.image" /></q-item-section>
              <q-item-section>{{ item.title }}</q-item-section>
            </q-item>
          </template>
        </q-list>
    `
};

window.AgiComponents['m-menu-dropdown'] = {
    props: {
        text: String, label: String, icon: String, transitionUrl: String, piniaStore: String, piniaList: String, targetUrl: String,
        labelField: { type: String, default: 'label' }, keyField: { type: String, default: 'id' }, urlParameter: { type: String, default: 'id' }
    },
    data() { return { fetchedOptions: [], loading: false, loaded: false } },
    computed: {
        options() {
            if (this.piniaStore && this.piniaList && window[this.piniaStore]) {
                var store = window[this.piniaStore]();
                return store[this.piniaList] || [];
            }
            return this.fetchedOptions;
        }
    },
    methods: {
        fetchOptions() {
            if ((this.piniaStore && this.piniaList) || this.loaded || this.loading || !this.transitionUrl) return;
            this.loading = true;
            var vm = this;
            $.ajax({
                type: 'GET', url: this.transitionUrl, dataType: 'json',
                headers: { 'moquiSessionToken': window.AGI_SERVER_CSRF_TOKEN || "" },
                success: function (data) {
                    vm.fetchedOptions = data || [];
                    vm.loaded = true;
                    vm.loading = false;
                },
                error: function (jqXHR, textStatus, errorThrown) {
                    console.error("Error fetching menu dropdown options", errorThrown);
                    vm.loading = false;
                }
            });
        },
        navigate(opt) {
            var target = opt.target || this.targetUrl;
            var paramName = opt.param || this.urlParameter;
            var value = opt.value !== undefined ? opt.value : opt[this.keyField];

            if (target) {
                if (!target.startsWith('/') && this.$root?.appRootPath) {
                    target = this.$root.appRootPath + '/' + target;
                }
                var separator = target.indexOf('?') !== -1 ? '&' : '?';
                var finalUrl = target + separator + paramName + '=' + encodeURIComponent(value);
                if (this.$root && typeof this.$root.setUrl === 'function') {
                    this.$root.setUrl(finalUrl);
                }
            }
        }
    },
    template: `
    <q-btn-dropdown flat stretch no-caps :label="text || label || 'MEETINGS'" :icon="icon || 'groups'" color="white" @show="fetchOptions">
        <q-list style="min-width: 200px">
            <q-item v-if="loading"><q-item-section class="flex flex-center"><q-spinner color="primary" /></q-item-section></q-item>
            <q-item v-else-if="options.length === 0"><q-item-section class="text-grey text-center">No options available</q-item-section></q-item>
            <template v-for="(opt, idx) in options" :key="idx">
                <q-item v-if="opt.children" clickable>
                    <q-item-section>{{ opt[labelField] || opt.label }}</q-item-section>
                    <q-item-section side><q-icon name="chevron_right" /></q-item-section>
                    <q-menu anchor="top end" self="top start">
                        <q-list>
                            <q-item v-for="(child, cIdx) in opt.children" :key="cIdx" clickable v-close-popup @click="navigate(child)">
                                <q-item-section>{{ child[labelField] || child.label }}</q-item-section>
                            </q-item>
                        </q-list>
                    </q-menu>
                </q-item>
                <q-item v-else clickable v-close-popup @click="navigate(opt)">
                    <q-item-section>{{ opt[labelField] || opt.label }}</q-item-section>
                </q-item>
            </template>
        </q-list>
    </q-btn-dropdown>
    `
};
window.AgiComponents['menu-dropdown'] = window.AgiComponents['m-menu-dropdown'];

window.AgiComponents['bp-tabbar'] = {
    props: { list: String, align: { type: String, default: 'left' }, noCaps: { type: Boolean, default: true } },
    computed: {
        resolvedList() {
            if (!this.list) return null;
            try {
                let val = eval(this.list);
                return Array.isArray(val) ? val : null;
            } catch (e) { console.error("Error resolving bp-tabbar list: " + this.list, e); return []; }
        }
    },
    template: `
        <div v-if="!list || (resolvedList && resolvedList.length > 0)">
            <q-tabs :align="align" :no-caps="noCaps" active-color="primary" indicator-color="primary">
                <template v-if="resolvedList">
                    <bp-tab-provider v-for="(item, index) in resolvedList" :key="index" :item="item">
                        <slot></slot>
                    </bp-tab-provider>
                </template>
                <slot v-else></slot>
            </q-tabs>
        </div>
    `
};

window.AgiComponents['bp-tab-provider'] = {
    props: ['item'],
    provide() { return { bpItem: this.item }; },
    template: '<slot></slot>'
};

window.AgiComponents['bp-tab'] = {
    inject: { bpItem: { default: null } },
    props: { name: String, label: String, icon: String, url: String, text: String },
    computed: {
        displayLabel() {
            let val = this.label || this.text;
            if (this.bpItem && val && this.bpItem[val] !== undefined) return this.bpItem[val];
            return val;
        },
        displayUrl() {
            let url = this.url;
            if (this.bpItem && this.url) {
                if (this.url.includes('item.')) {
                    try { const item = this.bpItem; url = eval(this.url); } catch (e) { url = this.url; }
                } else if (this.bpItem[this.url] !== undefined) {
                    url = this.bpItem[this.url];
                }
            }
            if (url && this.$root?.appRootPath && url.startsWith(this.$root.appRootPath)) {
                url = url.substring(this.$root.appRootPath.length);
                if (!url.startsWith('/')) url = '/' + url;
            }
            return url;
        }
    },
    methods: {
        navigate(e) {
            const targetUrl = this.displayUrl;
            if (targetUrl && this.$root && typeof this.$root.setUrl === 'function') {
                this.$root.setUrl(targetUrl);
            }
        }
    },
    template: '<q-route-tab :name="name" :label="displayLabel" :icon="icon" :to="displayUrl" @click="navigate"></q-route-tab>'
};

window.AgiComponents['bp-parameter'] = {
    props: { name: String, value: [String, Number], piniaStore: String, piniaField: String },
    mounted() { this.sync(); },
    watch: { value() { this.sync(); } },
    methods: {
        sync() {
            if (this.piniaStore && this.piniaField && window[this.piniaStore]) {
                const store = window[this.piniaStore]();
                store[this.piniaField] = this.value;
            }
        }
    },
    template: '<template></template>'
};

window.AgiComponents['m-banner'] = { template: '<q-banner><slot></slot></q-banner>' };

/* ==========================================================================
   3. CLINICAL CONTEXT & PATIENT MANAGEMENT EXTENSIONS
   ========================================================================== */

window.AgiComponents['discussion-tree'] = {
    props: {
        workEffortId: { type: String, required: true }, readonly: { type: Boolean, default: false },
        encryptNotes: { type: Boolean, default: true }, showPatientContext: { type: Boolean, default: false }
    },
    data() { return { topics: [], loading: false, error: null } },
    mounted() { this.fetchTopics(); },
    methods: {
        fetchTopics() {
            this.loading = true; this.error = null; var vm = this;
            $.ajax({
                type: 'POST', url: '/rest/s1/huddle/HuddleDiscussionTree', data: { workEffortId: this.workEffortId }, dataType: 'json',
                headers: { 'moquiSessionToken': window.AGI_SERVER_CSRF_TOKEN || "" },
                error: function (jqXHR, textStatus, errorThrown) {
                    vm.error = "Error loading topics: " + textStatus + " " + errorThrown;
                    vm.loading = false;
                },
                success: function (data) { vm.loading = false; }
            });
        },
        addChild(node) {
            var vm = this;
            this.$q.dialog({
                title: 'Add Sub-topic', message: 'Enter topic name for: ' + node.workEffortName,
                prompt: { model: '', type: 'text' }, cancel: true, persistent: true
            }).onOk(function (data) {
                if (!data) return;
                vm.loading = true;
                $.ajax({
                    type: 'POST', url: '/rest/s1/huddle/HuddleTopic', data: { parentWorkEffortId: node.workEffortId, workEffortName: data }, dataType: 'json',
                    headers: { 'moquiSessionToken': window.AGI_SERVER_CSRF_TOKEN || "" },
                    error: function (jqXHR, textStatus, errorThrown) {
                        vm.$q.notify({ type: 'negative', message: 'Error adding topic: ' + errorThrown });
                        vm.loading = false;
                    },
                    success: function () {
                        vm.$q.notify({ type: 'positive', message: 'Topic added successfully' });
                        vm.fetchTopics();
                    }
                });
            });
        },
        injectTopic(node) {
            var vm = this; vm.loading = true;
            $.ajax({
                type: 'GET', url: '/rest/s1/huddle/AvailableCorporateTopics', dataType: 'json',
                headers: { 'moquiSessionToken': window.AGI_SERVER_CSRF_TOKEN || "" },
                error: function (jqXHR, textStatus, errorThrown) {
                    vm.$q.notify({ type: 'negative', message: 'Error fetching corporate topics: ' + errorThrown });
                    vm.loading = false;
                },
                success: function (data) {
                    vm.loading = false;
                    if (!data || !data.topicList || data.topicList.length === 0) {
                        vm.$q.notify({ type: 'warning', message: 'No corporate topics available to inject.' });
                        return;
                    }
                    vm.$q.dialog({
                        title: 'Inject Corporate Topic', message: 'Select a topic to inject into: ' + node.workEffortName,
                        options: { type: 'radio', model: '', items: data.topicList.map(t => ({ label: t.workEffortName, value: t.workEffortId })) },
                        cancel: true, persistent: true
                    }).onOk(function (topicId) {
                        if (!topicId) return;
                        vm.loading = true;
                        $.ajax({
                            type: 'POST', url: '/rest/s1/huddle/HuddleTopic/inject', data: { huddleWorkEffortId: node.workEffortId, topicWorkEffortId: topicId }, dataType: 'json',
                            headers: { 'moquiSessionToken': window.AGI_SERVER_CSRF_TOKEN || "" },
                            error: function (jqXHR, textStatus, errorThrown) {
                                vm.$q.notify({ type: 'negative', message: 'Error injecting topic: ' + errorThrown });
                                vm.loading = false;
                            },
                            success: function () {
                                vm.$q.notify({ type: 'positive', message: 'Corporate topic injected successfully' });
                                vm.fetchTopics();
                            }
                        });
                    });
                }
            });
        }
    },
    template: `
    <div class="q-pa-md">
        <div v-if="loading" class="row justify-center"><q-spinner color="primary" size="3em" /></div>
        <div v-else-if="error" class="text-negative">{{ error }}</div>
        <div v-else>
            <q-tree :nodes="topics" node-key="workEffortId" label-key="workEffortName" default-expand-all>
                <template v-slot:default-header="prop">
                    <slot name="node-header" v-bind:node="prop.node">
                        <div class="row items-center">
                            <div class="text-weight-bold">{{ prop.node.workEffortName }}</div>
                            <q-chip v-if="prop.node.statusDescription" size="sm" color="primary" text-color="white" class="q-ml-sm">{{ prop.node.statusDescription }}</q-chip>
                        </div>
                    </slot>
                </template>
                <template v-slot:default-body="prop">
                    <slot name="node-body" v-bind:node="prop.node"><div v-if="prop.node.description" class="q-pa-sm text-grey-8">{{ prop.node.description }}</div></slot>
                    <div class="row q-gutter-sm q-mt-xs" v-if="!readonly">
                         <slot name="node-actions" v-bind:node="prop.node">
                             <q-btn size="sm" flat round color="primary" icon="add_comment" @click.stop="addChild(prop.node)"><q-tooltip>Add Sub-topic</q-tooltip></q-btn>
                             <q-btn size="sm" flat round color="secondary" icon="post_add" @click.stop="injectTopic(prop.node)"><q-tooltip>Inject Corporate Topic</q-tooltip></q-btn>
                         </slot>
                    </div>
                </template>
            </q-tree>
        </div>
    </div>
    `
};

/* ==========================================================================
   4. DOM SCRIPTS, LINKS AND INLINE MACROS
   ========================================================================== */

window.AgiComponents['m-link'] = {
    props: { href: { type: String, required: true }, loadId: String, confirmation: String },
    computed: { linkHref() { return this.$root ? this.$root.getLinkPath(this.href) : this.href; } },
    methods: {
        go(event) {
            if (event.button !== 0) return;
            if (this.linkHref && this.linkHref.startsWith('javascript:')) { eval(this.linkHref.substring(11)); return; }
            if (this.confirmation && this.confirmation.length && !window.confirm(this.confirmation)) return;
            if (this.loadId && this.loadId.length > 0 && this.$root) {
                this.$root.loadContainer(this.loadId, this.linkHref);
            } else if (this.$root) {
                if (event.ctrlKey || event.metaKey) window.open(this.linkHref, "_blank");
                else this.$root.setUrl(this.linkHref);
            }
        }
    },
    template: '<a :href="linkHref" @click.prevent="go" class="q-link" v-bind="$attrs" style="color: inherit; text-decoration: none;"><slot></slot></a>'
};

window.AgiComponents['m-script'] = {
    props: { src: String, type: { type: String, 'default': 'text/javascript' } },
    created() { if (this.src && this.src.length > 0 && typeof moqui.loadScript === 'function') moqui.loadScript(this.src); },
    mounted() {
        var innerText = this.$el.innerText;
        if (innerText && innerText.trim().length > 0 && typeof moqui.retryInlineScript === 'function') {
            moqui.retryInlineScript(innerText, 1);
        }
    },
    template: '<div :type="type" style="display:none;"><slot></slot></div>'
};

window.AgiComponents['m-stylesheet'] = {
    name: "mStylesheet",
    props: { href: { type: String, required: true }, rel: { type: String, 'default': 'stylesheet' }, type: { type: String, 'default': 'text/css' } },
    created() { if (typeof moqui.loadStylesheet === 'function') moqui.loadStylesheet(this.href, this.rel, this.type); },
    template: '<div :type="type" style="display:none;"></div>'
};

/* ==========================================================================
   5. STANDARD ROW LAYOUT BOXES AND MODALS
   ========================================================================== */

window.AgiComponents['m-container-row'] = { name: "mContainerRow", template: '<div class="row" v-bind="$attrs"><slot></slot></div>' };
window.AgiComponents['container-row'] = { name: "mContainerRow", template: '<div class="row" v-bind="$attrs"><slot></slot></div>' };

var rowColComp = {
    name: "mRowCol",
    props: { cols: String, xs: String, sm: String, md: String, lg: String, xl: String },
    computed: {
        colClass() {
            var cls = "";
            if (this.cols) cls += " col-" + this.cols;
            if (this.xs) cls += " col-xs-" + this.xs;
            if (this.sm) cls += " col-sm-" + this.sm;
            if (this.md) cls += " col-md-" + this.md;
            if (this.lg) cls += " col-lg-" + this.lg;
            if (this.xl) cls += " col-xl-" + this.xl;
            return (cls || "col") + " " + (this.$attrs.class || "");
        }
    },
    template: '<div :class="colClass" :style="$attrs.style" v-bind="$attrs"><slot></slot></div>'
};
window.AgiComponents['m-row-col'] = rowColComp;
window.AgiComponents['row-col'] = rowColComp;

window.AgiComponents['m-container-box'] = {
    name: "mContainerBox",
    props: { type: { type: String, 'default': 'default' }, title: String, initialOpen: { type: Boolean, 'default': true } },
    data() { return { isBodyOpen: this.initialOpen } },
    methods: { toggleBody() { this.isBodyOpen = !this.isBodyOpen; } },
    template: `
        <q-card flat bordered class="q-ma-sm m-container-box">
            <q-card-actions @click.self="toggleBody">
                <h5 v-if="title && title.length" @click="toggleBody" :class="'text-' + type">{{title}}</h5>
                <slot name="header"></slot>
                <q-space></q-space>
                <slot name="toolbar"></slot>
                <q-btn color="grey" round flat dense :icon="isBodyOpen ? 'keyboard_arrow_up' : 'keyboard_arrow_down'" @click="toggleBody"></q-btn>
            </q-card-actions>
            <div v-show="isBodyOpen">
                <q-card-section :class="{in:isBodyOpen}"><slot></slot></q-card-section>
            </div>
        </q-card>
    `
};

window.AgiComponents['m-box-body'] = {
    name: "mBoxBody",
    props: { height: String },
    data() { return this.height ? { dialogStyle: { 'max-height': this.height + 'px', 'overflow-y': 'auto' } } : { dialogStyle: {} } },
    template: '<div class="q-pa-xs" :style="dialogStyle"><slot></slot></div>'
};

window.AgiComponents['m-dialog'] = {
    name: "mDialog",
    props: { draggable: { type: Boolean, 'default': true }, modelValue: { type: Boolean, 'default': false }, id: String, color: String, width: String, title: String },
    emits: ['update:modelValue', 'onShow', 'onHide'],
    computed: {
        internalValue: {
            get() { return this.modelValue; },
            set(val) { this.$emit('update:modelValue', val); }
        }
    },
    methods: {
        onShow() {
            if (this.draggable) this.$refs.dialogHeader.$el.addEventListener("mousedown", this.onGrab);
            this.focusFirst(); this.$emit("onShow");
        },
        onHide() {
            if (this.draggable) {
                document.removeEventListener("mousemove", this.onDrag);
                document.removeEventListener("mouseup", this.onLetGo);
                if (this.$refs.dialogHeader) this.$refs.dialogHeader.$el.removeEventListener("mousedown", this.onGrab);
            }
            this.$emit("onHide");
        },
        onDrag(e) {
            var targetEl = this.$refs.dialogCard.$el;
            var originalStyles = window.getComputedStyle(targetEl);
            var newLeft = parseInt(originalStyles.left) + e.movementX;
            var newTop = parseInt(originalStyles.top) + e.movementY;
            targetEl.style.left = newLeft + "px"; targetEl.style.top = newTop + "px";
        },
        onLetGo() {
            document.removeEventListener("mousemove", this.onDrag);
            document.removeEventListener("mouseup", this.onLetGo);
        },
        onGrab() {
            document.addEventListener("mousemove", this.onDrag);
            document.addEventListener("mouseup", this.onLetGo);
        },
        focusFirst() {
            var jqEl = $(this.$refs.dialogBody.$el);
            var defFocus = jqEl.find(".default-focus");
            if (defFocus.length) defFocus.focus(); else jqEl.find("form :input:visible:not([type='submit']):first").focus();
        }
    },
    template: `
        <q-dialog v-model="internalValue" :id="id" @show="onShow" @hide="onHide" :maximized="$q.platform.is.mobile">
            <q-card ref="dialogCard" flat bordered :style="{width:((width||760)+'px'),'max-width':($q.platform.is.mobile?'100vw':'90vw')}">
                <q-card-actions ref="dialogHeader" :style="{cursor:(draggable?'move':'default')}">
                    <h5 class="q-pl-sm non-selectable">{{title}}</h5><q-space></q-space>
                    <q-btn icon="close" flat round dense v-close-popup></q-btn>
                </q-card-actions><q-separator></q-separator>
                <q-card-section ref="dialogBody"><slot></slot></q-card-section>
            </q-card>
        </q-dialog>
    `
};

window.AgiComponents['m-container-dialog'] = {
    name: "mContainerDialog",
    props: { id: String, color: String, buttonText: String, buttonClass: String, title: String, width: String, openDialog: { type: Boolean, default: false }, buttonIcon: { type: String, default: 'open_in_new' } },
    data() { return { isShown: false } },
    mounted() { if (this.openDialog) this.isShown = true; },
    methods: { show() { this.isShown = true; }, hide() { this.isShown = false; } },
    template: `
        <span>
            <span @click="show()"><slot name="button"><q-btn dense outline no-caps :icon="buttonIcon" :label="buttonText" :color="color" :class="buttonClass"></q-btn></slot></span>
            <m-dialog v-model="isShown" :id="id" :title="title" :color="color" :width="width"><slot></slot></m-dialog>
        </span>
    `
};

/* ==========================================================================
   6. CONTAINER INJECTION INTERFACING (DYNAMIC LOADING BOARDS)
   ========================================================================== */

if (!window.define) {
    window.define = function (name, deps, callback) {
        if (typeof name !== 'string') { callback = deps; deps = name; name = null; }
        if (!Array.isArray(deps)) { callback = deps; deps = null; }
        return typeof callback === 'function' ? callback() : callback;
    };
}

moqui.NotFound = Vue.defineComponent({ template: '<div id="current-page-root"><h4>Screen not found at {{this.$root?.currentPath}}</h4></div>' });
moqui.EmptyComponent = Vue.defineComponent({ template: '<div id="current-page-root"><div class="spinner"><div>&nbsp;</div></div></div>' });

window.AgiComponents['m-dynamic-container'] = {
    name: "mDynamicContainer",
    props: { id: { type: String, required: true }, url: { type: String } },
    data() { return { curComponent: moqui.EmptyComponent, curUrl: "" } },
    mounted() {
        if (this.$root && typeof this.$root.addContainer === 'function') this.$root.addContainer(this.id, this);
        this.curUrl = this.url;
    },
    methods: {
        reload() { var saveUrl = this.curUrl; this.curUrl = ""; var vm = this; setTimeout(function () { vm.curUrl = saveUrl; }, 20); },
        load(url) { if (this.curUrl === url) { this.reload(); } else { this.curUrl = url; } }
    },
    watch: {
        curUrl(newUrl) {
            if (!newUrl || newUrl.length === 0) { this.curComponent = moqui.EmptyComponent; return; }
            var vm = this; moqui.loadComponent(newUrl, function (comp) { vm.curComponent = comp; }, this.id);
        }
    },
    template: '<component :is="curComponent" v-bind="$attrs"></component>'
};

var dynamicDialogComp = {
    name: "mDynamicDialog",
    props: {
        id: String, url: String, color: String, buttonText: String, buttonClass: String, icon: String, title: String, width: String,
        openDialog: { type: Boolean, default: false }, dynamicParams: Object
    },
    data() { return { isShown: false, curUrl: "", curComponent: Vue.markRaw(moqui.EmptyComponent) } },
    mounted() {
        if (this.$root && typeof this.$root.addContainer === 'function') this.$root.addContainer(this.id, this);
        if (this.openDialog) this.isShown = true;
    },
    methods: {
        handleOpen() { this.isShown = true; },
        reload() { if (this.isShown) { this.isShown = false; this.isShown = true; } },
        load(url) { this.curUrl = url; },
        hide() { this.isShown = false; }
    },
    watch: {
        curUrl(newUrl) {
            if (!newUrl || newUrl.length === 0) { this.curComponent = moqui.EmptyComponent; return; }
            var vm = this;
            if (this.dynamicParams) {
                var dpStr = '';
                $.each(this.dynamicParams, function (key, value) {
                    var dynVal = $("#" + value).val();
                    if (dynVal && dynVal.length) dpStr = dpStr + (dpStr.length > 0 ? '&' : '') + key + '=' + dynVal;
                });
                if (dpStr.length) newUrl = newUrl + (newUrl.indexOf("?") > 0 ? '&' : '?') + dpStr;
            }
            moqui.loadComponent(newUrl, function (comp) {
                comp.mounted = function () { this.$nextTick(function () { if (vm.$refs.dialog) vm.$refs.dialog.focusFirst(); }); };
                vm.curComponent = comp;
            }, this.id);
        },
        isShown(newShown) { this.curUrl = newShown ? this.url : ""; }
    },
    template: `
        <span>
            <q-btn unelevated :icon="icon || 'add'" :label="buttonText || 'Start Meeting'" :color="color || 'primary'" :class="buttonClass" @click="handleOpen"></q-btn>
            <m-dialog ref="dialog" v-model="isShown" :id="id" :title="title" :color="color || 'primary'" :width="width"><component :is="curComponent" v-if="curUrl"></component></m-dialog>
        </span>
    `
};
window.AgiComponents['m-dynamic-dialog'] = dynamicDialogComp;
window.AgiComponents['dynamic-dialog'] = dynamicDialogComp;

window.AgiComponents['m-tree-top'] = {
    name: "mTreeTop",
    props: { id: { type: String, required: true }, items: { type: [String, Array], required: true }, openPath: String, parameters: Object },
    data() { return { urlItems: null, currentPath: null, top: this } },
    computed: { itemList() { if (this.urlItems) return this.urlItems; return Array.isArray(this.items) ? this.items : []; } },
    mounted() {
        if (typeof this.items === 'string' && this.$root) {
            this.currentPath = this.openPath;
            var allParms = $.extend({ moquiSessionToken: this.$root.moquiSessionToken, treeNodeId: '#', treeOpenPath: this.openPath }, this.parameters);
            var vm = this; $.ajax({
                type: 'POST', dataType: 'json', url: this.items, headers: { Accept: 'application/json' }, data: allParms,
                error: moqui.handleAjaxError, success: function (resp) { vm.urlItems = resp; }
            });
        }
    },
    template: '<ul :id="id" class="tree-list"><m-tree-item v-for="model in itemList" :key="model.id" :model="model" :top="top"></m-tree-item></ul>'
};

window.AgiComponents['m-tree-item'] = {
    name: "mTreeItem",
    props: { model: Object, top: Object },
    data() { return { open: false } },
    computed: {
        isFolder() { var children = this.model.children; return children ? (Array.isArray(children) ? children.length > 0 : true) : false; },
        hasChildren() { var children = this.model.children; return Array.isArray(children) && children.length > 0; },
        selected() { return this.top.currentPath === this.model.id; }
    },
    watch: {
        open(newVal) {
            if (newVal && this.model.children && typeof this.model.children === 'boolean' && typeof this.top.items === 'string' && this.$root) {
                var li_attr = this.model.li_attr;
                var allParms = $.extend({
                    moquiSessionToken: this.$root.moquiSessionToken, treeNodeId: this.model.id,
                    treeNodeName: (li_attr && li_attr.treeNodeName ? li_attr.treeNodeName : ''), treeOpenPath: this.top.currentPath
                }, this.top.parameters);
                var vm = this; $.ajax({
                    type: 'POST', dataType: 'json', url: this.top.items, headers: { Accept: 'application/json' }, data: allParms,
                    error: moqui.handleAjaxError, success: function (resp) { vm.model.children = resp; }
                });
            }
        }
    },
    methods: {
        toggle() { if (this.isFolder) this.open = !this.open; },
        setSelected() { this.top.currentPath = this.model.id; this.open = true; }
    },
    mounted() { if (this.model.state && this.model.state.opened) this.open = true; },
    template: `
        <li :id="model.id">
            <i v-if="isFolder" @click="toggle" class="fa" :class="{'fa-chevron-right':!open, 'fa-chevron-down':open}"></i>
            <i v-else class="fa fa-square-o"></i>
            <span @click="setSelected">
                <m-link v-if="model.a_attr" :href="model.a_attr.urlText" :load-id="model.a_attr.loadId" :class="{'text-success':selected}">{{model.text}}</m-link>
                <span v-if="!model.a_attr" :class="{'text-success':selected}">{{model.text}}</span>
            </span>
            <ul v-show="open" v-if="hasChildren"><m-tree-item v-for="sub in model.children" :key="sub.id" :model="sub" :top="top"></m-tree-item></ul>
        </li>
    `
};

/* ==========================================================================
   7. FORM AND INPUT FIELD WIDGET IMPLEMENTATIONS
   ========================================================================== */

window.AgiComponents['m-editable'] = {
    name: "mEditable",
    props: {
        id: { type: String, required: true }, labelType: { type: String, default: 'span' }, labelValue: { type: String, required: true },
        url: { type: String, required: true }, urlParameters: Object, parameterName: { type: String, default: 'value' }, widgetType: { type: String, default: 'textarea' },
        loadUrl: String, loadParameters: Object, indicator: { type: String, default: 'Saving' }, tooltip: { type: String, default: 'Click to edit' },
        cancel: { type: String, default: 'Cancel' }, submit: { type: String, default: 'Save' }
    },
    mounted() {
        if (!this.$root) return;
        var reqData = $.extend({ moquiSessionToken: this.$root.moquiSessionToken, parameterName: this.parameterName }, this.urlParameters);
        var edConfig = {
            indicator: this.indicator, tooltip: this.tooltip, cancel: this.cancel, submit: this.submit,
            name: this.parameterName, type: this.widgetType, cssclass: 'editable-form', submitdata: reqData
        };
        if (this.loadUrl && this.loadUrl.length > 0) {
            var vm = this; edConfig.loadurl = this.loadUrl; edConfig.loadtype = "POST";
            edConfig.loaddata = function (value) { return $.extend({ currentValue: value, moquiSessionToken: vm.$root.moquiSessionToken }, vm.loadParameters); };
        }
    },
    render(createEl) { return Vue.h(this.labelType, { id: this.id, class: 'editable-label', innerHTML: this.labelValue }); }
};

moqui.checkboxSetMixin = {
    props: { checkboxCount: { type: Number, default: 100 }, checkboxParameter: String, checkboxListMode: Boolean, checkboxValues: Array },
    data() {
        var states = []; for (var i = 0; i < this.checkboxCount; i++) states[i] = false;
        return { checkboxAllState: false, checkboxStates: states }
    },
    methods: {
        setCheckboxAllState(newState) {
            this.checkboxAllState = newState;
            for (var i = 0; i < this.checkboxStates.length; i++) this.checkboxStates[i] = newState;
        },
        getCheckboxValueArray() {
            if (!this.checkboxValues) return [];
            var valueArray = [];
            for (var i = 0; i < this.checkboxStates.length; i++) if (this.checkboxStates[i] && this.checkboxValues[i]) valueArray.push(this.checkboxValues[i]);
            return valueArray;
        },
        addCheckboxParameters(formData, parameter, listMode) {
            var parmName = parameter || this.checkboxParameter;
            var useList = (listMode !== null && listMode !== undefined) ? listMode : this.checkboxListMode;
            var valueArray = this.getCheckboxValueArray(); if (!valueArray.length) return false;
            if (useList) { formData.set(parmName, valueArray.join(',')); }
            else {
                for (var i = 0; i < valueArray.length; i++) formData.set(parmName + '_' + i, valueArray[i]);
                formData.set('_isMulti', 'true');
            }
            return true;
        }
    },
    watch: {
        checkboxStates: {
            deep: true, handler(newArray) {
                var allTrue = true;
                for (var i = 0; i < newArray.length; i++) { if (!newArray[i]) { allTrue = false; break; } }
                this.checkboxAllState = allTrue;
            }
        }
    }
};

window.AgiComponents['m-checkbox-set'] = {
    name: "mCheckboxSet",
    mixins: [moqui.checkboxSetMixin],
    template: '<span class="checkbox-set"><slot :checkboxAllState="checkboxAllState" :setCheckboxAllState="setCheckboxAllState" :checkboxStates="checkboxStates" :addCheckboxParameters="addCheckboxParameters"></slot></span>'
};

window.AgiComponents['m-form'] = {
    name: "mForm",
    mixins: [moqui.checkboxSetMixin],
    props: { fieldsInitial: Object, action: { type: String, required: true }, method: { type: String, default: 'POST' }, submitMessage: String, submitReloadId: String, submitHideId: String, focusField: String, noValidate: Boolean, excludeEmptyFields: Boolean, parentCheckboxSet: Object },
    data() { return { fields: Object.assign({}, this.fieldsInitial), fieldsOriginal: Object.assign({}, this.fieldsInitial), buttonClicked: null } },
    methods: {
        submitForm() { if (this.noValidate) { this.submitGo(); } else { var vm = this; this.$refs.qForm.validate().then(function (success) { if (success) vm.submitGo(); }); } },
        resetForm() { this.fields = Object.assign({}, this.fieldsOriginal); },
        blurSubmitForm(event) { if (this.hasFieldsChanged) this.submitForm(); return true; },
        submitGo() {
            if (!this.$root) return;
            var vm = this; var jqEl = $(this.$el); var btnName = null, btnValue = null;
            var $btn = $(this.buttonClicked || document.activeElement);
            if ($btn.length && jqEl.has($btn) && $btn.is('button[type="submit"], input[type="submit"]')) {
                if ($btn.is('[name]')) { btnName = $btn.attr('name'); btnValue = $btn.val(); }
                $btn.prop('disabled', true); setTimeout(function () { $btn.prop('disabled', false); }, 3000);
            }
            var formData = Object.keys(this.fields).length ? new FormData() : new FormData(this.$refs.qForm.$el);
            $.each(this.fields, function (key, value) {
                if (Array.isArray(value)) { value.forEach(function (v) { formData.append(key, v); }); } else { formData.set(key, value || ""); }
            });
            var fieldsToRemove = []; var formDataIterator = formData.entries()[Symbol.iterator]();
            while (true) {
                var iterEntry = formDataIterator.next(); if (iterEntry.done) break;
                var pair = iterEntry.value;
                if (typeof pair[1] === 'string' && pair[1].startsWith("__")) formData.set(pair[0], "");
                if (this.excludeEmptyFields && (!pair[1] || !pair[1].length)) fieldsToRemove.push(pair[0]);
            }
            for (var i = 0; i < fieldsToRemove.length; i++) formData.delete(fieldsToRemove[i]);
            formData.set('moquiSessionToken', this.$root.moquiSessionToken);
            if (btnName) formData.set(btnName, btnValue);
            if (this.parentCheckboxSet?.addCheckboxParameters) this.parentCheckboxSet.addCheckboxParameters(formData);

            this.$root.loading++;
            var xhr = new XMLHttpRequest();
            xhr.open(this.method, (this.$root.appRootPath + this.action), true); xhr.responseType = 'blob'; xhr.withCredentials = true;
            xhr.onload = function () {
                if (this.status === 200) {
                    vm.$root.loading--;
                    var disposition = xhr.getResponseHeader('Content-Disposition');
                    if (disposition && (disposition.indexOf('attachment') !== -1 || disposition.indexOf('inline') !== -1)) {
                        var blob = this.response; var filename = "";
                        var matches = /filename[^;=\n]*=((['"]).*?\2|[^;\n]*)/.exec(disposition);
                        if (matches != null && matches[1]) filename = matches[1].replace(/['"]/g, '');
                        if (typeof window.navigator.msSaveBlob !== 'undefined') { window.navigator.msSaveBlob(blob, filename); }
                        else {
                            var downloadUrl = (window.URL || window.webkitURL).createObjectURL(blob);
                            var a = document.createElement("a"); a.href = downloadUrl; if (filename) a.download = filename;
                            document.body.appendChild(a); a.click(); setTimeout(function () { (window.URL || window.webkitURL).revokeObjectURL(downloadUrl); }, 100);
                        }
                    } else {
                        var reader = new FileReader();
                        reader.onload = function (evt) { try { vm.handleResponse(JSON.parse(evt.target.result)); } catch (e) { vm.handleResponse(evt.target.result); } };
                        reader.readAsText(this.response);
                    }
                } else { typeof moqui.handleLoadError === 'function' ? moqui.handleLoadError(this, this.statusText, "") : vm.$root.loading--; }
            };
            xhr.setRequestHeader('Accept', 'application/json'); xhr.send(formData);
        },
        handleResponse(resp) {
            var notified = false;
            if (resp && typeof resp === 'object') {
                notified = typeof moqui.notifyMessages === 'function' ? moqui.notifyMessages(resp.messageInfos, resp.errors) : false;
                if (resp.screenUrl && resp.screenUrl.length > 0 && this.$root) this.$root.setUrl(resp.screenUrl);
            }
            if (this.submitHideId && this.$root) this.$root.hideContainer(this.submitHideId);
            if (this.submitReloadId && this.$root) this.$root.reloadContainer(this.submitReloadId);
            if (this.submitMessage && this.submitMessage.length) {
                var message = eval('"' + this.submitMessage + '"');
                if (window.Quasar?.Notify) Quasar.Notify.create({ timeout: 1500, type: 'positive', message: message });
            }
        },
        fieldChanged(name) {
            var cur = this.fields[name]; var orig = this.fieldsOriginal[name];
            return Array.isArray(cur) ? !moqui.arraysEqual(cur, orig, true) : !moqui.equalsOrPlaceholder(cur, orig);
        }
    },
    computed: { hasFieldsChanged() { return typeof moqui.fieldValuesDiff === 'function' ? moqui.fieldValuesDiff(this.fields, this.fieldsOriginal) : false; } },
    mounted() {
        var vm = this; var jqEl = $(this.$el);
        if (this.focusField && this.focusField.length) jqEl.find('[name^="' + this.focusField + '"]').addClass('default-focus').focus();
        jqEl.find('button[type="submit"], input[type="submit"]').on('click', function () { vm.buttonClicked = this; });
    },
    template: `
        <q-form ref="qForm" @submit.prevent="submitForm" @reset.prevent="resetForm" autocapitalize="off" autocomplete="off">
            <slot :fields="fields" :checkboxAllState="checkboxAllState" :setCheckboxAllState="setCheckboxAllState" :checkboxStates="checkboxStates" :addCheckboxParameters="addCheckboxParameters" :blurSubmitForm="blurSubmitForm" :hasFieldsChanged="hasFieldsChanged" :fieldChanged="fieldChanged"></slot>
        </q-form>
    `
};

window.AgiComponents['m-form-link'] = {
    name: "mFormLink",
    props: { fieldsInitial: Object, action: { type: String, required: true }, focusField: String, noValidate: Boolean, bodyParameterNames: Array },
    data() { return { fields: Object.assign({}, this.fieldsInitial), fieldsOriginal: Object.assign({}, this.fieldsInitial) } },
    methods: {
        submitForm() { if (this.noValidate) { this.submitGo(); } else { var vm = this; this.$refs.qForm.validate().then(function (success) { if (success) vm.submitGo(); }); } },
        submitGo() {
            if (!this.$root) return;
            var $btn = $(document.activeElement);
            if ($btn.length && $btn.is('button[type="submit"], input[type="submit"]')) {
                $btn.prop('disabled', true); setTimeout(function () { $btn.prop('disabled', false); }, 3000);
            }
            var formData = Object.keys(this.fields).length ? new FormData() : new FormData(this.$refs.qForm.$el);
            $.each(this.fields, function (key, value) { if (value) { if (typeof value === 'string' && value.startsWith("__")) return; if (Array.isArray(value)) { value.forEach(function (v) { formData.append(key, v); }); } else { formData.set(key, value); } } });
            var extraList = [], plainKeyList = [], parmStr = "", bodyParameters = null;
            var formDataIterator = formData.entries()[Symbol.iterator]();
            while (true) {
                var iterEntry = formDataIterator.next(); if (iterEntry.done) break;
                var pair = iterEntry.value; var key = pair[0]; var value = pair[1];
                if (value.trim().length === 0 || key === "moquiSessionToken" || key === "moquiFormName" || key.indexOf('[]') > 0) continue;
                if (key.indexOf("_op") > 0 || key.indexOf("_not") > 0 || key.indexOf("_ic") > 0) { extraList.push({ name: key, value: value }); }
                else {
                    plainKeyList.push(key);
                    if (this.bodyParameterNames && this.bodyParameterNames.indexOf(key) >= 0) { if (!bodyParameters) bodyParameters = {}; bodyParameters[key] = value; }
                    else { if (parmStr.length > 0) parmStr += '&'; parmStr += (encodeURIComponent(key) + '=' + encodeURIComponent(value)); }
                }
            }
            for (var i = 0; i < extraList.length; i++) {
                var eparm = extraList[i]; var keyName = eparm.name.substring(0, eparm.name.indexOf('_'));
                if (plainKeyList.indexOf(keyName) >= 0) { if (parmStr.length > 0) parmStr += '&'; parmStr += (encodeURIComponent(eparm.name) + '=' + encodeURIComponent(eparm.value)); }
            }
            var url = this.action; if (url.indexOf('?') > 0) { url = url + '&' + parmStr; } else { url = url + '?' + parmStr; }
            this.$root.setUrl(url, bodyParameters);
        },
        resetForm() { this.fields = Object.assign({}, this.fieldsInitial); },
        clearForm() { this.fields = {}; },
        fieldChanged(name) {
            var cur = this.fields[name]; var orig = this.fieldsOriginal[name];
            return Array.isArray(cur) ? !moqui.arraysEqual(cur, orig, true) : !moqui.equalsOrPlaceholder(cur, orig);
        }
    },
    computed: { hasFieldsChanged() { return typeof moqui.fieldValuesDiff === 'function' ? moqui.fieldValuesDiff(this.fields, this.fieldsOriginal) : false; } },
    mounted() { if (this.focusField && this.focusField.length > 0) $(this.$el).find('[name=' + this.focusField + ']').addClass('default-focus').focus(); },
    template: `
        <q-form ref="qForm" @submit.prevent="submitForm" @reset.prevent="resetForm" autocapitalize="off" autocomplete="off">
            <slot :clearForm="clearForm" :fields="fields" :hasFieldsChanged="hasFieldsChanged" :fieldChanged="fieldChanged"></slot>
        </q-form>
    `
};

window.AgiComponents['m-form-paginate'] = {
    name: "mFormPaginate",
    props: { paginate: Object, formList: Object },
    computed: {
        prevArray() {
            var pag = this.paginate; var arr = []; if (!pag || pag.pageIndex < 1) return arr;
            var pageIndex = pag.pageIndex; var indexMin = pageIndex - 3; if (indexMin < 0) { indexMin = 0; } var indexMax = pageIndex - 1;
            while (indexMin <= indexMax) { arr.push(indexMin++); } return arr;
        },
        nextArray() {
            var pag = this.paginate; var arr = []; if (!pag || pag.pageIndex >= pag.pageMaxIndex) return arr;
            var pageIndex = pag.pageIndex; var pageMaxIndex = pag.pageMaxIndex; var indexMin = pageIndex + 1; var indexMax = pageIndex + 3; if (indexMax > pageMaxIndex) { indexMax = pageMaxIndex; }
            while (indexMin <= indexMax) { arr.push(indexMin++); } return arr;
        }
    },
    methods: { setIndex(idx) { if (this.formList) { this.formList.setPageIndex(idx); } else if (this.$root) { this.$root.setParameters({ pageIndex: idx }); } } },
    template: `
        <div v-if="paginate && paginate.count > 1" class="q-pagination row no-wrap items-center">
            <template v-if="paginate.pageIndex > 0">
                <q-btn dense flat no-caps @click.prevent="setIndex(0)" icon="skip_previous"></q-btn>
                <q-btn dense flat no-caps @click.prevent="setIndex(paginate.pageIndex-1)" icon="fast_rewind"></q-btn>
            </template>
            <template v-else><q-btn dense flat no-caps disabled icon="skip_previous"></q-btn><q-btn dense flat no-caps disabled icon="fast_rewind"></q-btn></template>
            <q-btn v-for="prevIndex in prevArray" :key="prevIndex" dense flat no-caps @click.prevent="setIndex(prevIndex)" :label="prevIndex+1" color="primary"></q-btn>
            <q-btn dense flat no-caps disabled>{{paginate.pageIndex+1}} / {{paginate.pageMaxIndex+1}} ({{paginate.pageRangeLow}}-{{paginate.pageRangeHigh}} / {{paginate.count}})</q-btn>
            <q-btn v-for="nextIndex in nextArray" :key="nextIndex" dense flat no-caps @click.prevent="setIndex(nextIndex)" :label="nextIndex+1" color="primary"></q-btn>
            <template v-if="paginate.pageIndex < paginate.pageMaxIndex">
                <q-btn dense flat no-caps @click.prevent="setIndex(paginate.pageIndex+1)" icon="fast_forward"></q-btn>
                <q-btn dense flat no-caps @click.prevent="setIndex(paginate.pageMaxIndex)" icon="skip_next"></q-btn>
            </template>
            <template v-else><q-btn dense flat no-caps disabled icon="fast_forward"></q-btn><q-btn dense flat no-caps disabled icon="skip_next"></q-btn></template>
        </div>
    `
};

window.AgiComponents['m-form-go-page'] = {
    name: "mFormGoPage",
    props: { idVal: { type: String, required: true }, maxIndex: Number, formList: Object },
    data() { return { pageIndex: "" } },
    methods: {
        goPage() {
            var formList = this.formList; var newIndex = +this.pageIndex - 1;
            if (formList) { formList.setPageIndex(newIndex); } else if (this.$root) { this.$root.setParameters({ pageIndex: newIndex }); }
            var vm = this; this.$nextTick(function () { vm.pageIndex = ""; });
        }
    },
    template: `
        <q-form v-if="!formList || (formList.paginate && formList.paginate.pageMaxIndex > 4)" @submit.prevent="goPage">
            <q-input dense v-model="pageIndex" type="text" size="4" name="pageIndex" placeholder="Page #" :rules="[val => /^\\d*$/.test(val) || 'digits only']">
                <template v-slot:append><q-btn dense flat no-caps type="submit" icon="redo" @click="goPage"></q-btn></template>
            </q-input>
        </q-form>
    `
};

window.AgiComponents['m-form-column-config'] = {
    name: "mFormColumnConfig",
    props: { id: String, action: String, columnsInitial: { type: Array, required: true }, formLocation: { type: String, required: true }, findParameters: Object },
    data() { return { columns: typeof moqui.deepCopy === 'function' ? moqui.deepCopy(this.columnsInitial) : JSON.parse(JSON.stringify(this.columnsInitial)) } },
    methods: {
        moveInCol(columnIdx, fieldIdx, newFieldIdx) { var children = this.columns[columnIdx].children; var fieldObj = children.splice(fieldIdx, 1)[0]; children.splice(newFieldIdx, 0, fieldObj); },
        moveToCol(columnIdx, fieldIdx, newColumnIdx) { var columnObj = this.columns[columnIdx]; var newColumnObj = newColumnIdx >= this.columns.length ? this.addColumn() : this.columns[newColumnIdx]; var fieldObj = columnObj.children.splice(fieldIdx, 1)[0]; newColumnObj.children.push(fieldObj); },
        addColumn() { var oldLength = this.columns.length; var lastCol = this.columns[oldLength - 1]; var newId = lastCol.id.split("_")[0] + "_" + oldLength; var newLabel = lastCol.label.split(" ")[0] + " " + oldLength; this.columns.push({ id: newId, label: newLabel, children: [] }); return this.columns[oldLength]; },
        hideField(columnIdx, fieldIdx) { if (columnIdx === 0) return; var hiddenObj = this.columns[0]; var columnObj = this.columns[columnIdx]; var fieldObj = columnObj.children.splice(fieldIdx, 1)[0]; hiddenObj.children.push(fieldObj); },
        resetColumns() { this.columns = typeof moqui.deepCopy === 'function' ? moqui.deepCopy(this.columnsInitial) : JSON.parse(JSON.stringify(this.columnsInitial)); },
        saveColumns() { this.generalFormFields(); var fields = this.$refs.mForm.fields; fields.SaveColumns = "SaveColumns"; fields.columnsTree = JSON.stringify(this.columns); this.$refs.mForm.submitGo(); },
        resetToDefault() { this.generalFormFields(); this.$refs.mForm.fields.ResetColumns = "ResetColumns"; this.$refs.mForm.submitGo(); },
        generalFormFields() {
            var fields = this.$refs.mForm.fields; fields.formLocation = this.formLocation;
            if (this.findParameters) { var keys = Object.keys(this.findParameters); for (var i = 0; i < keys.length; i++) { fields[keys[i]] = this.findParameters[keys[i]]; } }
            if (window.innerWidth <= 600 || Quasar.Platform.is.mobile) fields._uiType = 'mobile';
        }
    },
    template: `
        <m-form ref="mForm" :id="id" :action="action">
            <q-list v-for="(column, columnIdx) in columns" :key="column.id" bordered dense>
                <q-item-label header>{{column.label}}</q-item-label>
                <q-item v-for="(field, fieldIdx) in column.children" :key="field.id">
                    <q-item-section side v-if="columnIdx !== 0"><q-btn dense flat icon="cancel" @click="hideField(columnIdx, fieldIdx)"></q-btn></q-section>
                    <q-item-section><q-item-label>{{field.label}}</q-item-label></q-item-section>
                    <q-item-section v-if="columnIdx === 0" side>
                        <q-btn-dropdown dense outline no-caps label="Display"><q-list dense>
                            <q-item v-for="(toCol, toColIdx) in columns.slice(1)" :key="toCol.id" clickable><q-item-section @click="moveToCol(columnIdx, fieldIdx, toColIdx+1)">{{toCol.label}}</q-item-section></q-item>
                            <q-item clickable><q-item-section @click="moveToCol(columnIdx, fieldIdx, columns.length+1)">New Column</q-item-section></q-item>
                        </q-list></q-btn-dropdown>
                    </q-section>
                    <q-item-section v-else side><q-btn-group flat>
                        <q-btn :disabled="columnIdx <= 1" dense flat icon="north" @click="moveToCol(columnIdx, fieldIdx, columnIdx-1)"></q-btn>
                        <q-btn :disabled="fieldIdx === 0" dense flat icon="expand_less" @click="moveInCol(columnIdx, fieldIdx, fieldIdx-1)"></q-btn>
                        <q-btn :disabled="(fieldIdx + 1) === column.children.length" dense flat icon="expand_more" @click="moveInCol(columnIdx, fieldIdx, fieldIdx+1)"></q-btn>
                        <q-btn dense flat icon="south" @click="moveToCol(columnIdx, fieldIdx, columnIdx+1)"></q-btn>
                    </q-btn-group></q-item-section>
                </q-item>
            </q-list>
            <div class="q-my-md">
                <q-btn dense outline no-caps @click.prevent="saveColumns()" label="Save Changes"></q-btn>
                <q-btn dense outline no-caps @click.prevent="resetColumns()" label="Undo Changes"></q-btn>
                <q-btn dense outline no-caps @click.prevent="resetToDefault()" label="Reset to Default"></q-btn>
            </div>
        </m-form>
    `
};

window.AgiComponents['m-form-query'] = {
    name: "mFormQuery",
    props: { id: String, formEventString: { type: String, default: "" }, searchObj: { type: Object, default: () => ({}) } },
    provide() { return { formQueryState: this.searchState } },
    data() { return { searchState: Object.assign({}, this.searchObj), loading: false } },
    methods: {
        submitQuery() {
            var newParams = {}; for (var key in this.searchState) { if (this.searchState[key] !== null && this.searchState[key] !== undefined && this.searchState[key] !== '') newParams[key] = this.searchState[key]; }
            if (this.formEventString) { try { new Function('searchState', 'moqui', this.formEventString).call(this, this.searchState, moqui); } catch (e) { console.error(e); } }
            if (this.$root && typeof this.$root.setParameters === 'function') this.$root.setParameters(newParams);
        },
        resetQuery() { this.searchState = {}; this.submitQuery(); this.$emit('reset'); }
    },
    watch: { searchObj: { deep: true, handler(newVal) { this.searchState = Object.assign({}, newVal); } } },
    template: `
        <q-card flat bordered class="q-mb-md q-pa-sm">
          <q-form ref="qForm" @submit.prevent="submitQuery" @reset.prevent="resetQuery">
            <div class="row q-col-gutter-sm">
              <slot :searchState="searchState" :loading="loading"></slot>
              <div class="col-12 flex items-center q-mt-sm">
                <q-btn type="submit" color="primary" label="Search" class="q-mr-sm" :loading="loading" />
                <q-btn type="reset" color="secondary" label="Clear" outline :disable="loading" />
              </div>
            </div>
          </q-form>
        </q-card>
    `
};

window.AgiComponents['m-form-query-field'] = {
    name: "mFormQueryField",
    inject: ['formQueryState'],
    props: { name: { type: String, required: true }, label: { type: String, default: "" }, type: { type: String, default: "text" }, operator: { type: String, default: "" }, options: { type: Array, default: () => [] }, optionsUrl: String, optionsParameters: Object, optionsLoadInit: { type: Boolean, default: false } },
    created() { if (this.operator && this.formQueryState && !this.formQueryState[this.name + '_op']) this.formQueryState[this.name + '_op'] = this.operator; },
    methods: { toggleOp() { var current = this.formQueryState[this.name + '_op']; this.formQueryState[this.name + '_op'] = (current === 'begins' ? 'contains' : 'begins'); } },
    template: `
        <div class="col-6 q-pb-sm q-pr-md" style="min-width: 200px;">
          <q-input v-if="type === 'text'" v-model="formQueryState[name]" :name="name" :label="label" dense outlined clearable>
            <template v-slot:append>
              <q-btn flat round dense :icon="formQueryState[name + '_op'] === 'begins' ? 'start' : 'search'" @click="toggleOp" size="sm" color="grey-7"></q-btn>
            </template>
          </q-input>
          <m-date-time v-else-if="type === 'date' || type === 'date-time'" :model-value="formQueryState[name]" @update:model-value="formQueryState[name] = $event" :name="name" :label="label" :type="type" dense outlined />
          <m-drop-down v-else-if="type === 'drop-down'" :model-value="formQueryState[name]" @update:model-value="formQueryState[name] = $event" :name="name" :label="label" :options="options" :options-url="optionsUrl" :options-parameters="optionsParameters" :options-load-init="optionsLoadInit" allow-empty dense outlined />
          <q-input v-else v-model="formQueryState[name]" :name="name" :label="label" dense outlined clearable />
        </div>
    `
};

window.AgiComponents['m-form-list'] = {
    name: "mFormList",
    props: { name: { type: String, required: true }, id: String, rows: { type: [String, Array], required: true }, search: Object, action: String, multi: Boolean, skipForm: Boolean, skipHeader: Boolean, headerForm: Boolean, headerDialog: Boolean, savedFinds: Boolean, selectColumns: Boolean, allButton: Boolean, csvButton: Boolean, textButton: Boolean, pdfButton: Boolean, columns: [String, Number] },
    data() { return { rowList: [], paginate: null, searchObj: null, moqui: moqui } },
    computed: {
        idVal() { return (this.id && this.id.length > 0) ? this.id : this.name; },
        csvUrl() { if (!this.$root) return ''; return this.$root.currentPath + '?' + moqui.objToSearch($.extend({}, this.searchObj, { renderMode: 'csv', pageNoLimit: 'true', lastStandalone: 'true', saveFilename: (this.name + '.csv') })); }
    },
    methods: {
        fetchRows() {
            if (Array.isArray(this.rows) || !this.$root) { return; }
            var vm = this; var searchObj = this.search || this.$root.currentParameters;
            var url = this.rows; if (url.indexOf('/') === -1) url = this.$root.currentPath + '/actions/' + url;
            $.ajax({
                type: "GET", url: url, data: searchObj, dataType: "json", headers: { Accept: 'application/json' },
                error: moqui.handleAjaxError, success: function (list, status, jqXHR) {
                    if (list && Array.isArray(list)) {
                        var getHeader = jqXHR.getResponseHeader; var count = Number(getHeader("X-Total-Count"));
                        if (count && !isNaN(count)) {
                            vm.paginate = { count: Number(count), pageIndex: Number(getHeader("X-Page-Index")), pageSize: Number(getHeader("X-Page-Size")), pageMaxIndex: Number(getHeader("X-Page-Max-Index")), pageRangeLow: Number(getHeader("X-Page-Range-Low")), pageRangeHigh: Number(getHeader("X-Page-Range-High")) };
                        }
                        vm.rowList = list;
                    }
                }
            });
        },
        setPageIndex(newIndex) { if (!this.searchObj) { this.searchObj = { pageIndex: newIndex }; } else { this.searchObj.pageIndex = newIndex; } this.fetchRows(); }
    },
    watch: { rows(newRows) { if (Array.isArray(newRows)) { this.rowList = newRows; } else { this.fetchRows(); } }, search() { this.fetchRows(); } },
    mounted() { this.searchObj = this.search || this.$root?.currentParameters; if (Array.isArray(this.rows)) { this.rowList = this.rows; } else { this.fetchRows(); } },
    template: `
        <div>
            <template v-if="!multi && !skipForm">
                <m-form v-for="(fields, rowIndex) in rowList" :key="rowIndex" :name="idVal+'_'+rowIndex" :id="idVal+'_'+rowIndex" :action="action"><slot name="rowForm" :fields="fields"></slot></m-form>
            </template>
            <m-form v-if="multi && !skipForm" :name="idVal" :id="idVal" :action="action">
                <input type="hidden" name="moquiFormName" :value="name"><input type="hidden" name="_isMulti" value="true">
                <template v-for="(fields, rowIndex) in rowList" :key="rowIndex"><slot name="rowForm" :fields="fields"></slot></template>
            </m-form>
            <m-form-link v-if="!skipHeader && headerForm && !headerDialog" :name="idVal+'._header'" :id="idVal+'._header'" :action="$root?.currentLinkPath">
                <input v-if="searchObj && searchObj.orderByField" type="hidden" name="orderByField" :value="searchObj.orderByField"><slot name="headerForm" :search="searchObj"></slot>
            </m-form-link>
            <div class="q-table__container q-table__card q-table--horizontal-separator q-table--dense q-table--flat">
                <table class="q-table" :id="idVal+'_table'">
                    <thead>
                        <tr class="form-list-nav-row"><th :colspan="columns?columns:'100'"><q-bar>
                            <m-form-paginate :paginate="paginate" :form-list="this"></m-form-paginate>
                            <m-form-go-page :id-val="idVal" :form-list="this"></m-form-go-page>
                            <a v-if="csvButton" :href="csvUrl" class="btn btn-default">CSV</a><slot name="nav"></slot>
                        </q-bar></th></tr><slot name="header" :search="searchObj"></slot>
                    </thead>
                    <tbody><tr v-for="(fields, rowIndex) in rowList" :key="rowIndex"><slot name="row" :fields="fields" :row-index="rowIndex" :moqui="moqui"></slot></tr></tbody>
                </table>
            </div>
        </div>
    `
};

window.AgiComponents['m-date-time'] = {
    name: "mDateTime",
    props: { id: String, name: { type: String, required: true }, modelValue: String, type: { type: String, default: 'date-time' }, label: String, size: String, format: String, tooltip: String, form: String, rules: Array, disable: Boolean, autoYear: String, minuteStep: { type: Number, default: 5 }, bgColor: String },
    emits: ['update:modelValue'],
    methods: {
        focusDate(event) { if (this.type === 'time' || this.autoYear === 'false') return; if (!this.modelValue || !this.modelValue.length) { var year = this.autoYear?.match(/^[12]\d\d\d$/) ? this.autoYear : new Date().getFullYear(); this.$emit('update:modelValue', String(year)); } },
        blurDate(event) { if (this.type === 'time' || !this.modelValue) return; if (this.modelValue.indexOf('d') > 0) { this.$emit('update:modelValue', ''); } else if (this.modelValue.indexOf('hh:mm') > 0) { this.$emit('update:modelValue', this.modelValue.replace('hh:mm', '12:00')); } else if (this.modelValue.indexOf(':mm') > 0) { this.$emit('update:modelValue', this.modelValue.replace(':mm', ':00')); } }
    },
    computed: {
        dateModel: { get() { return this.modelValue || null; }, set(val) { this.$emit('update:modelValue', val); } },
        formatVal() { if (this.format?.length) return this.format; return this.type === 'time' ? 'HH:mm' : (this.type === 'date' ? 'YYYY-MM-DD' : 'YYYY-MM-DD HH:mm'); },
        inputMask() { return this.formatVal.replace(/\w/g, '#'); },
        sizeVal() { if (this.size?.length) return this.size; return this.type === 'time' ? '9' : (this.type === 'date' ? '10' : '16'); }
    },
    template: `
        <q-input dense outlined stack-label :label="label" :model-value="modelValue" @update:model-value="$emit('update:modelValue', $event)" @focus="focusDate" @blur="blurDate" :rules="rules" :mask="inputMask" fill-mask :id="id" :name="name" :form="form" :disable="disable" :size="sizeVal" style="max-width:max-content;" :bg-color="bgColor">
            <template v-slot:prepend v-if="type=='date' || type=='date-time'">
                <q-icon name="event" class="cursor-pointer"><q-popup-proxy ref="qDateProxy"><q-date :model-value="dateModel" @update:model-value="val => { dateModel = val; $refs.qDateProxy.hide(); } " :mask="formatVal"></q-date></q-popup-proxy></q-icon>
            </template>
            <template v-slot:append v-if="type=='time' || type=='date-time'">
                <q-icon name="access_time" class="cursor-pointer"><q-popup-proxy ref="qTimeProxy"><q-time :model-value="dateModel" @update:model-value="val => { dateModel = val; $refs.qTimeProxy.hide(); }" :mask="formatVal" format24h></q-time></q-popup-proxy></q-icon>
            </template>
            <template v-slot:after><slot name="after"></slot></template>
        </q-input>
    `
};

moqui.dateOffsets = [{ value: '0', label: 'This' }, { value: '-1', label: 'Last' }, { value: '1', label: 'Next' }];
moqui.datePeriods = [{ value: 'day', label: 'Day' }, { value: '7d', label: '7 Days' }, { value: 'month', label: 'Month' }];
window.AgiComponents['m-date-period'] = {
    name: "mDatePeriod",
    props: { fields: { type: Object, required: true }, name: { type: String, required: true }, id: String, allowEmpty: Boolean, fromThruType: { type: String, default: 'date' }, form: String, tooltip: String, label: String },
    data() { return { fromThruMode: false, dateOffsets: moqui.dateOffsets.slice(), datePeriods: moqui.datePeriods.slice(), fieldsOriginal: Object.assign({}, this.fields) } },
    mounted() { if (this.fields[this.name + '_from'] || this.fields[this.name + '_thru']) this.fromThruMode = true; },
    methods: {
        toggleMode() { this.fromThruMode = !this.fromThruMode; },
        clearAll() { this.fields[this.name + '_pdate'] = null; this.fields[this.name + '_poffset'] = null; this.fields[this.name + '_period'] = null; this.fields[this.name + '_from'] = null; this.fields[this.name + '_thru'] = null; },
        fieldChanged(name) { return !moqui.equalsOrPlaceholder(this.fields[name], this.fieldsOriginal[name]); }
    },
    template: `
        <div v-if="fromThruMode" class="row">
            <m-date-time :name="name+'_from'" :id="id+'_from'" :label="label+' From'" :form="form" :type="fromThruType" :model-value="fields[name+'_from']" @update:model-value="fields[name+'_from'] = $event" :bg-color="fieldChanged(name+'_from')?'blue-1':''"></m-date-time>
            <q-icon class="q-my-auto" name="remove"></q-icon>
            <m-date-time :name="name+'_thru'" :id="id+'_thru'" :label="label+' Thru'" :form="form" :type="fromThruType" :model-value="fields[name+'_thru']" @update:model-value="fields[name+'_thru'] = $event" :bg-color="fieldChanged(name+'_thru')?'blue-1':''">
                <template v-slot:after><q-btn dense flat icon="calendar_view_day" @click="toggleMode"></q-btn><q-btn dense flat icon="clear" @click="clearAll"></q-btn></template>
            </m-date-time>
        </div>
        <div v-else class="row">
            <q-input dense outlined stack-label :label="label" v-model="fields[name+'_pdate']" mask="####-##-##" fill-mask :id="id" :name="name+'_pdate'" :form="form" style="max-width:max-content;" :bg-color="fieldChanged(name+'_pdate')?'blue-1':''">
                <template v-slot:before>
                    <q-select class="q-pr-xs" dense outlined options-dense emit-value map-options v-model="fields[name+'_poffset']" :options="dateOffsets" label="Offset" behavior="menu"></q-select>
                    <q-select dense outlined options-dense emit-value map-options v-model="fields[name+'_period']" :options="datePeriods" label="Period" behavior="menu"></q-select>
                </template>
                <template v-slot:prepend><q-icon name="event" class="cursor-pointer"><q-popup-proxy ref="qDateProxy"><q-date v-model="fields[name+'_pdate']" mask="YYYY-MM-DD" @update:model-value="function(){$refs.qDateProxy.hide()}"></q-date></q-popup-proxy></q-icon></template>
                <template v-slot:after><q-btn dense flat icon="date_range" @click="toggleMode"></q-btn><q-btn dense flat icon="clear" @click="clearAll"></q-btn></template>
            </q-input>
        </div>
    `
};

window.AgiComponents['m-display'] = {
    name: "mDisplay",
    props: { modelValue: String, display: String, valueUrl: String, valueParameters: Object, dependsOn: Object, dependsOptional: Boolean, valueLoadInit: Boolean, fields: Object, tooltip: String, label: String, labelWrapper: Boolean, name: String, id: String },
    data() { return { curDisplay: this.display, loading: false } },
    computed: { displayValue() { return this.curDisplay?.length ? this.curDisplay : this.modelValue; } },
    methods: {
        populateFromUrl() {
            if (!this.valueUrl || !this.$root) return;
            var vm = this; this.loading = true;
            $.ajax({
                type: "POST", url: this.valueUrl, data: { moquiSessionToken: this.$root.moquiSessionToken }, dataType: "text",
                success: function (text) {
                    vm.loading = false; var label = text; try { var r = JSON.parse(text); label = r.label || text; } catch (e) { }
                    vm.curDisplay = label;
                },
                error: function (xhr) { vm.loading = false; }
            });
        }
    },
    mounted() { if (this.valueLoadInit) this.populateFromUrl(); },
    template: `
        <q-input v-if="labelWrapper" dense outlined readonly stack-label autogrow :model-value="displayValue" :label="label" :id="id" :name="name" :loading="loading"></q-input>
        <span v-else :id="id">{{displayValue}}</span>
    `
};

window.AgiComponents['m-drop-down'] = {
    name: "mDropDown",
    props: { modelValue: [Array, String], options: { type: Array, default: () => [] }, combo: Boolean, allowEmpty: Boolean, multiple: Boolean, requiredManualSelect: Boolean, submitOnSelect: Boolean, optionsUrl: String, optionsParameters: Object, optionsLoadInit: Boolean, serverSearch: Boolean, labelField: { type: String, default: 'label' }, valueField: { type: String, default: 'value' }, dependsOn: Object, dependsOptional: Boolean, form: String, fields: Object, tooltip: String, label: String, name: String, id: String, disable: Boolean, bgColor: String, onSelectGoTo: String },
    data() { return { curOptions: this.options, allOptions: this.options, loading: false } },
    methods: {
        handleInput($event) {
            if (this.onSelectGoTo?.length && $event[this.onSelectGoTo] && this.$root) { this.$root.setUrl($event[this.onSelectGoTo]); }
            else { this.$emit('update:modelValue', $event); }
            if (this.submitOnSelect) { var vm = this; this.$nextTick(function () { if (vm.$parent?.$parent?.submitForm) vm.$parent.$parent.submitForm(); }); }
        },
        filterFn(search, doneFn, abortFn) { if (this.optionsUrl?.length) { this.populateFromUrl(search, doneFn); } else { doneFn(); } },
        populateFromUrl(search, doneFn) {
            if (!this.optionsUrl || !this.$root) return;
            var vm = this; this.loading = true;
            $.ajax({
                type: "POST", url: this.optionsUrl, data: { moquiSessionToken: this.$root.moquiSessionToken, term: search || '' }, dataType: "json",
                success: function (data) {
                    vm.loading = false; var list = Array.isArray(data) ? data : data.options || [];
                    var proc = list.map(o => ({ value: o[vm.valueField] || o.value, label: o[vm.labelField] || o.label }));
                    vm.curOptions = proc; vm.allOptions = proc; if (doneFn) doneFn();
                },
                error: function () { vm.loading = false; }
            });
        },
        optionLabel(val) { return this.allOptions.find(o => o.value === val)?.label || ""; },
        removeValue(val) { var nv = this.modelValue.filter(v => v !== val); this.$emit('update:modelValue', nv); }
    },
    mounted() { if (this.optionsLoadInit) this.populateFromUrl(); },
    template: `
        <q-select ref="qSelect" :model-value="modelValue" @update:model-value="handleInput($event)" dense outlined options-dense use-input :hide-selected="multiple" :name="name" :id="id" :form="form" @filter="filterFn" :clearable="allowEmpty||multiple" :disable="disable" :multiple="multiple" :emit-value="!onSelectGoTo" map-options behavior="menu" stack-label :label="label" :loading="loading" :bg-color="bgColor" :options="curOptions">
            <template v-slot:no-option><q-item><q-item-section class="text-grey">No results</q-item-section></q-item></template>
            <template v-if="multiple" v-slot:prepend><div><q-chip v-for="ve in modelValue" :key="ve" dense size="md" removable @remove="removeValue(ve)">{{optionLabel(ve)}}</q-chip></div></template>
        </q-select>
    `
};

window.AgiComponents['m-text-line'] = {
    name: "mTextLine",
    props: { modelValue: String, type: { type: String, default: 'text' }, id: String, name: String, size: String, fields: Object, dense: Boolean, outlined: Boolean, bgColor: String, label: String, tooltip: String, prefix: String, disable: Boolean, mask: String, fillMask: String, reverseFillMask: Boolean, rules: Array, defaultUrl: String, defaultParameters: Object, dependsOn: Object, dependsOptional: Boolean, defaultLoadInit: Boolean },
    data() { return { loading: false } },
    template: `
        <q-input :dense="dense" :outlined="outlined" :bg-color="bgColor" stack-label :label="label" :prefix="prefix" :model-value="modelValue" @update:model-value="$emit('update:modelValue', $event)" :type="type" :id="id" :name="name" :size="size" :loading="loading" :rules="rules" :disable="disable" :mask="mask" :fill-mask="fillMask" :reverse-fill-mask="reverseFillMask" autocapitalize="off" autocomplete="off">
            <q-tooltip v-if="tooltip">{{tooltip}}</q-tooltip>
        </q-input>
    `
};

/* ==========================================================================
   8. THIRD PARTY LAZY-LOADING WRAPPERS (CHARTS, MERMAID, EDITORS)
   ========================================================================== */

window.AgiComponents['m-chart'] = {
    props: { config: { type: Object, required: true }, height: { type: String, default: '400px' }, width: { type: String, default: '100%' } },
    data() { return { instance: null } },
    mounted() { var vm = this; if (window.Chart) { vm.instance = new Chart(vm.$refs.canvas, vm.config); } },
    template: '<div class="chart-container" style="position:relative;" :style="{height:height,width:width}"><canvas ref="canvas"></canvas></div>'
};

window.AgiComponents['m-mermaid'] = {
    props: { config: { type: Object, default: () => ({ startOnLoad: true, securityLevel: 'loose' }) }, height: { type: String, default: '400px' }, width: { type: String, default: '100%' } },
    mounted() { if (window.mermaid) mermaid.init(this.config, this.$refs.mermaid); },
    template: '<pre ref="mermaid" class="mermaid" :style="{height:height,width:width}"><slot></slot></pre>'
};

window.AgiComponents['m-ck-editor'] = {
    props: { modelValue: { type: String, default: '' }, useInline: Boolean, config: Object, readOnly: { type: Boolean, default: null } },
    mounted() { if (window.CKEDITOR) this.ckeditor = CKEDITOR[this.useInline ? 'inline' : 'replace'](this.$refs.area, this.config); },
    template: '<div><textarea ref="area"></textarea></div>'
};

window.AgiComponents['m-simple-mde'] = {
    props: { modelValue: { type: String, default: '' }, config: Object },
    mounted() { if (window.SimpleMDE) this.simplemde = new SimpleMDE(Object.assign({ element: this.$refs.area, initialValue: this.modelValue }, this.config)); },
    template: '<div><textarea ref="area"></textarea></div>'
};

/* ==========================================================================
   9. SUBSCREENS NESTING AND MENU INTERFACES
   ========================================================================== */

window.AgiComponents['m-subscreens-tabs'] = {
    name: "mSubscreensTabs",
    props: { passedPathIndex: { type: [Number, String], default: -1 } },
    data() { return { pathIndex: -1 } },
    computed: {
        subscreens() { if (this.pathIndex < 0 || !this.$root?.navMenuList) return []; return this.$root.navMenuList[this.pathIndex]?.subscreens || []; },
        activeTab() { if (this.pathIndex < 0 || !this.$root?.navMenuList) return null; var activeName = null; $.each(this.$root.navMenuList[this.pathIndex]?.subscreens || [], function (idx, tab) { if (tab.active) activeName = tab.name; }); return activeName; }
    },
    methods: { goTo(path) { if (this.$root) this.$root.setUrl(this.$root.getLinkPath(path)); } },
    mounted() {
        if (this.passedPathIndex !== -1 && this.passedPathIndex !== undefined && this.passedPathIndex !== "-1") { this.pathIndex = parseInt(this.passedPathIndex); }
        else {
            let depth = -1, p = this.$parent;
            while (p) { if (p.$options?.name === 'mSubscreensActive' || p.activePathIndex !== undefined) { depth = p.activePathIndex; break; } p = p.$parent; }
            this.pathIndex = depth + 1;
        }
    },
    template: `
        <div v-if="subscreens.length > 1">
            <q-tabs dense no-caps align="left" active-color="primary" indicator-color="primary" :model-value="activeTab">
                <q-tab v-for="tab in subscreens" :key="tab.name" :name="tab.name" :label="tab.title" :disable="tab.disableLink" @click.prevent="goTo(tab.pathWithParams)"></q-tab>
            </q-tabs><q-separator class="q-mb-md"></q-separator>
        </div>
    `
};

window.AgiComponents['m-subscreens-active'] = {
    name: "mSubscreensActive",
    props: { pathIndex: { type: [Number, String], default: -1 }, itemName: String },
    data() { return { activeComponent: Vue.markRaw(moqui.EmptyComponent), activePathIndex: -1, pathName: null } },
    methods: {
        loadActive() {
            var vm = this; var root = vm.$root; if (!root?.currentPathList) return false;
            var pathIndex = vm.activePathIndex; var curPathList = root.currentPathList; var newPath = curPathList[pathIndex];

            let parent = vm.$parent;
            while (parent) {
                if (parent.pathName === newPath) {
                    this.activeComponent = Vue.markRaw(moqui.EmptyComponent); return true;
                }
                parent = parent.$parent;
            }
            if (pathIndex === 0 && (!newPath || newPath === "")) {
                this.activeComponent = Vue.markRaw(moqui.EmptyComponent);
                return true;
            }
            if (pathIndex > 0 && (!newPath || pathIndex >= curPathList.length)) {
                this.activeComponent = Vue.markRaw(moqui.EmptyComponent);
                return true;
            }

            var fullPath = root.basePath + '/' + curPathList.slice(0, pathIndex + 1).join('/');
            if (this.itemName) {
                const parentNav = root.navMenuList[pathIndex + root.basePathSize - 1];
                const subItem = parentNav?.subscreens?.find(s => s.name === this.itemName);
                if (subItem) { fullPath = subItem.pathWithParams; newPath = this.itemName; } else { this.activeComponent = Vue.markRaw(moqui.EmptyComponent); return true; }
            }

            if (root.loadingSubscreens?.[fullPath]) return false;
            root.loadingSubscreens = root.loadingSubscreens || {};

            var urlInfo = { path: fullPath, lastStandalone: -(pathIndex + root.basePathSize + 1) };
            if (pathIndex === (curPathList.length - 1) && root.extraPathList?.length) urlInfo.extraPath = root.extraPathList.join('/');
            if (root.currentSearch?.length) urlInfo.search = root.currentSearch;
            urlInfo.bodyParameters = root.bodyParameters;

            var qvtFullPath = fullPath;
            if (root.linkBasePath && root.linkBasePath !== '/' && qvtFullPath.startsWith(root.linkBasePath)) qvtFullPath = qvtFullPath.substring(root.linkBasePath.length);
            if (!qvtFullPath.startsWith('/')) qvtFullPath = '/' + qvtFullPath;

            root.loadingSubscreens[fullPath] = true; root.loading++;
            root.currentLoadRequest = moqui.loadComponent(urlInfo, function (comp) {
                delete root.loadingSubscreens[fullPath]; root.currentLoadRequest = null;
                vm.activeComponent = Vue.markRaw(comp);
                if (!vm.itemName && vm.$router) {
                    if (pathIndex === (root.currentPathList.length - 1)) vm.$router.replace(qvtFullPath);
                }
                root.loading--;
            });
            return true;
        }
    },
    created() {
        let depth = -1, p = this.$parent;
        while (p) { if (p.activePathIndex !== undefined) { depth = p.activePathIndex; break; } p = p.$parent; }
        this.activePathIndex = (this.pathIndex !== -1 && this.pathIndex !== undefined) ? parseInt(this.pathIndex) : depth + 1;
    },
    mounted() {
        if (this.$root && typeof this.$root.addSubscreen === 'function') {
            this.$root.addSubscreen(this);
        }
    },
    unmounted() { if (this.$root && typeof this.$root.removeSubscreen === 'function') this.$root.removeSubscreen(this); },
    template: '<component :is="activeComponent" style="height:100%;width:100%;"></component>'
};

window.AgiComponents['m-menu-nav-item'] = {
    name: "mMenuNavItem",
    props: { menuIndex: Number },
    computed: { navMenuItem() { return this.$root?.navMenuList?.[this.menuIndex]; }, navMenuLength() { return this.$root?.navMenuList?.length || 0; } },
    methods: { go() { if (this.navMenuItem) this.$root.setUrl(this.navMenuItem.pathWithParams); } },
    template: `
        <q-expansion-item v-if="navMenuItem && navMenuItem.subscreens && navMenuItem.subscreens.length" :value="true" switch-toggle-side dense dense-toggle expanded-icon="arrow_drop_down" :to="navMenuItem.pathWithParams" @input="go">
            <template v-slot:header><m-menu-item-content :menu-item="navMenuItem" active></m-menu-item-content></template>
            <template v-slot:default><m-menu-subscreen-item v-for="(ss, idx) in navMenuItem.subscreens" :key="ss.name" :menu-index="menuIndex" :subscreen-index="idx"></m-menu-subscreen-item></template>
        </q-expansion-item>
        <q-expansion-item v-else-if="menuIndex < (navMenuLength - 1)" :value="true" switch-toggle-side dense dense-toggle expanded-icon="arrow_drop_down" :to="navMenuItem.pathWithParams" @input="go">
            <template v-slot:header><m-menu-item-content :menu-item="navMenuItem" active></m-menu-item-content></template>
            <template v-slot:default><m-menu-nav-item :menu-index="menuIndex + 1"></m-menu-nav-item></template>
        </q-expansion-item>
        <q-expansion-item v-else-if="navMenuItem" :value="false" switch-toggle-side dense dense-toggle expand-icon="arrow_right" :to="navMenuItem.pathWithParams" @input="go">
            <template v-slot:header><m-menu-item-content :menu-item="navMenuItem" active></m-menu-item-content></template>
        </q-expansion-item>
    `
};

window.AgiComponents['m-menu-subscreen-item'] = {
    name: "mMenuSubscreenItem",
    props: { menuIndex: Number, subscreenIndex: Number },
    computed: { subscreen() { return this.$root.navMenuList[this.menuIndex].subscreens[this.subscreenIndex]; } },
    methods: { go() { this.$root.setUrl(this.subscreen.pathWithParams); } },
    template: `
        <m-menu-nav-item v-if="subscreen.active" :menu-index="menuIndex + 1"></m-menu-nav-item>
        <q-expansion-item v-else :value="false" switch-toggle-side dense dense-toggle expand-icon="arrow_right" :to="subscreen.pathWithParams" @input="go">
            <template v-slot:header><m-menu-item-content :menu-item="subscreen"></m-menu-item-content></template>
        </q-expansion-item>
    `
};

window.AgiComponents['m-menu-item-content'] = {
    name: "mMenuItemContent",
    props: { menuItem: Object, active: Boolean },
    template: `
        <div class="q-item__section column q-item__section--main justify-center"><div class="q-item__label">
        <i v-if="menuItem.image && menuItem.imageType === 'icon'" :class="menuItem.image" style="padding-right: 8px;"></i>
        <span v-else-if="menuItem.image" style="padding-right:8px;"><img :src="menuItem.image" :alt="menuItem.title" height="14" class="invertible"></span>
        <span :class="{'text-primary':active}">{{menuItem.title}}</span>
        </div></div>
    `
};

/* ==========================================================================
   10. REGISTRATION AND APPLICATION ASSOCIATION HOOK
   ========================================================================== */

moqui.configureRuntimeApp = function (app) {
    if (!app) return;

    // Direct filters configuration layout block
    app.config.globalProperties.$filters = {
        format(value) { return typeof moqui.format === 'function' ? moqui.format(value) : value; }
    };

    // Mount all structural layouts across the shared registry array
    if (window.AgiComponents) {
        Object.keys(window.AgiComponents).forEach(tagName => {
            app.component(tagName, window.AgiComponents[tagName]);
        });
    }

    app.config.compilerOptions.whitespace = 'preserve';
    console.info("⚙️ [AgiVue] Unified component dictionary and filter decorators registered successfully.");
};