/**
 * BlueprintClient.js - A standalone library for rendering Aitree Blueprints using Quasar 2.
 */
(function () {
    // 1. Initialize the global dictionary container if it doesn't exist
    window.AgiComponents = window.AgiComponents || {};
    window.AgiComponents['BlueprintRenderer'] = {
        name: 'BlueprintRenderer',
        props: ['blueprint'],
        setup(props) {
            const dataStore = Vue.inject('clientStore', Vue.ref({}));
            const isReady = Vue.computed(() => dataStore.blueprintReady || true);

            const parentSelectedComp = Vue.inject('selectedComponent', null);
            const parentSelectComp = Vue.inject('selectComponent', null);

            const selectedComponent = parentSelectedComp || Vue.ref(null);
            const selectComponent = parentSelectComp || ((comp) => {
                selectedComponent.value = comp;
                console.log("Component selected:", comp);
            });

            if (!parentSelectedComp) Vue.provide('selectedComponent', selectedComponent);
            if (!parentSelectComp) Vue.provide('selectComponent', selectComponent);

            const sourceJson = Vue.ref(JSON.stringify(props.blueprint, null, 2));
            const renderMode = Vue.ref('visual');
            const isService = Vue.computed(() => ideMode.value === 'service');
            const toggleOptions = Vue.computed(() => [
                { icon: 'account_tree', value: 'visual', label: isService.value ? 'Flow' : 'Layout' },
                { icon: 'code', value: 'source', label: 'Source' }
            ]);

            const saveSource = () => {
                try {
                    const parsed = JSON.parse(sourceJson.value);
                    const event = new CustomEvent('blueprint-source-save', { detail: parsed });
                    window.dispatchEvent(event);
                } catch (e) {
                    console.error("Manual JSON Parse Error", e);
                    alert("Invalid JSON format. Please correct it before saving.");
                }
            };

            const ideMode = Vue.inject('ideMode', Vue.ref('screen'));
            const userInput = Vue.inject('userInput', null);

            const palettePosition = Vue.ref({ top: 120, left: (window.innerWidth || 1024) - 650 });
            const handlePan = (details) => {
                if (!details || !details.delta) return;
                palettePosition.value.top += (details.delta.y || 0);
                palettePosition.value.left += (details.delta.x || 0);
            };

            const uiRegistry = Vue.ref({ categories: [] });
            const serviceRegistry = Vue.ref({ categories: [] });

            const fetchRegistries = async () => {
                try {
                    const uiResp = await fetch('/rest/s1/agi-ai/getRegistry?type=ui-macro');
                    if (uiResp.ok) {
                        const uiData = await uiResp.json();
                        uiRegistry.value = uiData.registry || { categories: [] };
                    }

                    const serviceResp = await fetch('/rest/s1/agi-ai/getRegistry?type=service-macro');
                    if (serviceResp.ok) {
                        const serviceData = await serviceResp.json();
                        serviceRegistry.value = serviceData.registry || { categories: [] };
                    }
                } catch (e) {
                    console.error("Failed to fetch registries", e);
                }
            };

            Vue.onMounted(fetchRegistries);

            const copyToChat = (item) => {
                if (userInput && userInput.value !== undefined) {
                    userInput.value = item.command;
                    setTimeout(() => {
                        const inputEl = document.getElementById('ai-chat-input');
                        if (inputEl) inputEl.focus();
                    }, 100);
                }
            };

            const currentPalette = Vue.computed(() => {
                const registry = ideMode.value === 'service' ? serviceRegistry.value : uiRegistry.value;
                return (registry && registry.categories) ? registry.categories : [];
            });

            const modeLabel = Vue.computed(() => (ideMode.value || 'screen').toUpperCase() + ' MODE');

            return { dataStore, isReady, selectedComponent, isService, renderMode, sourceJson, saveSource, toggleOptions, currentPalette, copyToChat, palettePosition, handlePan, modeLabel, ideMode };
        },
        template: `
                <div class="blueprint-container q-pa-md full-height relative-position" v-if="blueprint">
                    <!-- FLOATING TOOLS PALETTE (DRAGGABLE & DYNAMIC) -->
                    <div v-touch-pan.prevent.mouse="handlePan" 
                         :style="{ position: 'fixed', top: palettePosition.top + 'px', left: palettePosition.left + 'px', zIndex: 10000, width: '240px', cursor: 'grab' }">
                        <q-card class="palette-card shadow-24 overflow-hidden" style="background: white; border: 2px solid #3f51b5; border-radius: 8px;">
                            <div class="bg-indigo-10 text-white q-pa-sm text-weight-bold row items-center" style="font-size: 0.8rem;">
                                <q-icon name="drag_indicator" class="q-mr-sm" size="18px" />
                                <div class="col">TOOL PALETTE</div>
                                <q-icon name="construction" color="amber" size="xs" />
                            </div>
                            
                            <q-list dense bordered separator class="bg-white scroll" style="max-height: 400px;">
                                <q-expansion-item v-for="cat in currentPalette" :key="cat.name"
                                                 dense expand-separator :icon="cat.name === 'Layout' ? 'grid_view' : 'category'"
                                                 :label="cat.name" 
                                                 header-class="text-indigo-10 text-weight-bold bg-indigo-1"
                                                 style="font-size: 0.75rem;">
                                    <q-list dense>
                                        <q-item v-for="item in cat.items" :key="item.label" clickable v-ripple @click="copyToChat(item)" class="q-pl-lg">
                                            <q-item-section avatar side><q-icon :name="item.icon" color="indigo-7" size="16px" /></q-item-section>
                                            <q-item-section class="text-caption text-weight-medium" style="color: #1a237e;">{{ item.label }}</q-item-section>
                                            <q-item-section side><q-icon name="add_circle" color="green-7" size="12px" /></q-item-section>
                                            <q-tooltip anchor="center left" self="center right">{{ item.description }}</q-tooltip>
                                        </q-item>
                                    </q-list>
                                </q-expansion-item>
                            </q-list>

                            <div class="q-pa-xs text-center text-caption bg-indigo-1 text-indigo-10 text-uppercase text-weight-bold" style="font-size: 10px; border-top: 1px solid #ddd;">
                                {{ modeLabel }}
                            </div>
                        </q-card>
                    </div>

                    <!-- Mode Header & Toggle -->
                    <div class="row items-center q-mb-md">
                        <q-icon :name="isService ? 'settings' : 'web'" color="indigo" size="md" class="q-mr-sm"></q-icon>
                        <div class="col">
                            <div class="text-h5 text-weight-bold text-indigo-10">{{ blueprint.meta?.title || 'Untitled Blueprint' }}</div>
                            <div class="text-overline text-indigo-4" style="line-height: 1.2;">{{ isService ? 'Service Logic Pipeline' : 'User Interface Layout' }}</div>
                        </div>
                        <q-btn-toggle
                            v-model="renderMode"
                            flat dense
                            toggle-color="indigo"
                            color="grey-4"
                            :options="toggleOptions"
                        />
                    </div>

                    <q-separator class="q-my-md"></q-separator>
                    
                    <div v-if="renderMode === 'visual'" class="col scroll">
                         <div class="q-mt-sm" v-if="ideMode === 'screen' && blueprint.structure && isReady">
                            <component-factory :components="blueprint.structure" />
                        </div>
                        <div class="q-mt-md" v-if="ideMode === 'service' && isReady">
                            <logic-renderer :actions="blueprint.actions || []" />
                        </div>
                    </div>

                    <!-- SOURCE EDITOR -->
                    <div v-else class="col column bg-grey-10 rounded-borders overflow-hidden" style="border: 1px solid #333;">
                        <div class="row items-center q-pa-sm bg-grey-9 text-grey-4">
                            <q-icon name="edit_note" class="q-mr-xs"></q-icon>
                            <div class="text-caption text-weight-bold">MANUAL SOURCE OVERRIDE</div>
                            <q-space />
                            <q-btn flat dense size="sm" color="amber" icon="save" label="Push Artifact" @click="saveSource"></q-btn>
                        </div>
                        <q-input
                            v-model="sourceJson"
                            type="textarea"
                            filled dark square
                            class="col"
                            input-style="font-family: 'Fira Code', 'Courier New', monospace; font-size: 13px; line-height: 1.5; height: 100%;"
                            spellcheck="false"
                        />
                    </div>
                </div>
            `
    };

    // =========================================================================
    // 2. THE RECURSIVE BLUEPRINT CORE INTERPRETATION NODE
    // =========================================================================
    window.AgiComponents['m-blueprint-node'] = {
        name: 'MBlueprintNode',
        props: {
            node: { required: true },
            context: { type: Object, default: () => ({}) }
        },
        provide() {
            return {
                nodeContext: this.context
            };
        },
        methods: {
            handleNodeClick(event, node) {
                if (!node) return;
                event.stopPropagation();

                let mId = node.mariaId || node.id || node.attributes?.name || node.name;
                if ((!mId || mId === 'maria_field' || mId === 'field') && (node.attributes?.name || node.name)) {
                    mId = `maria_field#${node.attributes?.name || node.name}`;
                }

                if (mId && (mId.includes('agi-workspace-root') || mId.includes('AgiWorkspace'))) {
                    return;
                }

                console.log("🎯 [CLICK DETECTED ON NODE]:", {
                    tag: node._moquiTag || node.name,
                    mariaId: mId,
                    nodeAttributes: node.attributes
                });

                const payload = {
                    event: 'element-selected-by-id',
                    mariaId: mId
                };

                // Broadcast on Channel and dispatch Window event
                try {
                    if (!window.__agiContextBus) {
                        window.__agiContextBus = new BroadcastChannel('agi-ide-context-bus');
                    }
                    window.__agiContextBus.postMessage(payload);
                } catch (e) { }

                window.dispatchEvent(new CustomEvent('element-selected-by-id', { detail: payload }));
            }
        },
        render() {
            if (!this.node) return null;

            // Raw primitive values
            if (typeof this.node === 'string' || typeof this.node === 'number') {
                return this.node;
            }

            let activeNode = Array.isArray(this.node) ? (this.node[0] || {}) : this.node;

            // 1. Tag & Attribute Normalization
            const rawTag = (activeNode._moquiTag || activeNode['@type'] || activeNode.name || activeNode.tag || 'container').toString();
            const tag = rawTag.startsWith('@') ? rawTag.slice(1) : rawTag;
            const explicitType = activeNode['@type'];
            const rawAttrs = activeNode.attributes || {};
            const childNodes = activeNode.children || activeNode.widgets || [];

            // 2. Selection & ContextBus Identity Attributes
            let mId = activeNode.mariaId || activeNode.id || activeNode.attributes?.name || activeNode.name || '';
            if ((!mId || mId === 'maria_field' || mId === 'field') && (rawAttrs.name || activeNode.name)) {
                const fName = rawAttrs.name || activeNode.name;
                mId = (this.context?.selectedMariaId && !this.context.selectedMariaId.includes(fName))
                    ? `${this.context.selectedMariaId}#${fName}`
                    : fName;
            }

            const isSelected = !!(this.context?.selectedMariaId && mId && this.context.selectedMariaId === mId);

            const withSelection = (customProps = {}, defaultClass = '') => {
                const finalClass = [
                    defaultClass,
                    customProps.class || '',
                    isSelected ? 'agi-canvas-selected-node' : ''
                ].filter(Boolean).join(' ');

                return {
                    ...customProps,
                    class: finalClass,
                    'data-maria-id': mId || undefined,
                    'mariaid': mId || undefined,
                    onClick: (e) => this.handleNodeClick(e, activeNode)
                };
            };

            // Helper to recursively render children
            const renderChildren = () => {
                return childNodes.map(child => {
                    if (typeof child === 'string' || typeof child === 'number') return child;
                    return Vue.h(window.AgiComponents['m-blueprint-node'], {
                        node: child,
                        context: this.context
                    });
                });
            };

            // Floating AI action badge
            const renderActionBadge = (node) => {
                const nodeTitle = node.attributes?.name || node.name || node.title || node._moquiTag || 'element';
                return Vue.h('div', {
                    class: [
                        'agi-action-badge absolute-top-right z-top row items-center q-gutter-x-xs rounded-borders shadow-2',
                        isSelected ? 'bg-primary text-white is-active' : 'bg-slate-800 text-slate-200'
                    ].join(' '),
                    style: 'transform: translate(-4px, -50%); font-size: 10px; padding: 2px 6px; border-radius: 4px; pointer-events: auto; cursor: pointer;'
                }, [
                    Vue.h('span', { class: 'text-weight-bold font-mono' }, nodeTitle),
                    Vue.h(Vue.resolveComponent('q-btn'), {
                        icon: 'terminal',
                        size: 'xs',
                        dense: true,
                        flat: true,
                        round: true,
                        color: 'white',
                        onClick: (e) => {
                            e.stopPropagation();
                            const mId = node.mariaId || node.id || nodeTitle;
                            const payload = {
                                event: 'open-prompt-editor',
                                focusCoordinate: mId,
                                artifactLocation: this.context?.screenPath || '',
                                targetComponent: 'nursinghome',
                                adHocPrompt: `Refactor element [${nodeTitle}] (${mId}): `
                            };

                            try {
                                if (window.__agiContextBus) window.__agiContextBus.postMessage(payload);
                            } catch (err) { }
                            window.dispatchEvent(new CustomEvent('open-prompt-editor', { detail: payload }));
                        }
                    })
                ]);
            };

            // 3. TIER 1: Check for explicitly registered custom components
            const isNativeHtmlTag = (t) => ['div', 'span', 'form', 'button', 'label', 'input', 'select', 'textarea', 'p', 'table', 'tr', 'td', 'th'].includes(t);

            // Intercept form-list so IDE doesn't invoke live runtime MFormList
            if (tag === 'form-list' || explicitType === 'm-form-list' || tag === 'm-form-list') {
                const fields = childNodes.filter(c => (c._moquiTag === 'field' || c.name === 'field'));
                return Vue.h(Vue.resolveComponent('q-card'), withSelection({
                    flat: false,
                    bordered: true
                }, 'q-mb-md full-width bg-white rounded-borders shadow-1 relative-position agi-hover-container'), {
                    default: () => [
                        renderActionBadge(activeNode),
                        Vue.h('div', { class: 'bg-blue-grey-1 q-pa-sm text-subtitle2 text-weight-bold row items-center justify-between' }, [
                            Vue.h('span', `List: ${activeNode.name || 'Data Grid'}`),
                            Vue.h(Vue.resolveComponent('q-icon'), { name: 'table_view', size: '18px', color: 'blue-grey-6' })
                        ]),
                        Vue.h('div', { class: 'q-pa-xs scroll' }, [
                            Vue.h('table', { class: 'full-width text-left', style: 'border-collapse: collapse;' }, [
                                Vue.h('thead', [
                                    Vue.h('tr', { class: 'bg-grey-2 text-caption text-grey-8' }, fields.map(f => {
                                        const hField = (f.children || []).find(c => c._moquiTag === 'header-field' || c.name === 'header-field');
                                        const title = hField?.title || hField?.attributes?.title || f.title || f.name || 'Column';
                                        return Vue.h('th', { class: 'q-pa-sm border-bottom' }, title);
                                    }))
                                ]),
                                Vue.h('tbody', [
                                    Vue.h('tr', { class: 'text-caption' }, fields.map(f => {
                                        const dField = (f.children || []).find(c => c._moquiTag === 'default-field' || c.name === 'default-field') || f;
                                        return Vue.h('td', { class: 'q-pa-sm text-grey-6', style: 'border-bottom: 1px solid #eee;' }, [
                                            Vue.h(window.AgiComponents['m-blueprint-node'], { node: dField, context: this.context })
                                        ]);
                                    }))
                                ])
                            ])
                        ])
                    ]
                });
            }

            let CustomComp = null;
            if (explicitType && !isNativeHtmlTag(explicitType)) {
                CustomComp = window.AgiComponents[explicitType] || Vue.resolveComponent(explicitType);
            } else if (tag && !isNativeHtmlTag(tag)) {
                CustomComp = window.AgiComponents[tag];
            }

            if (CustomComp && typeof CustomComp !== 'string') {
                let propsMap = withSelection({ ...rawAttrs });
                if (activeNode.id) propsMap.id = activeNode.id;

                return Vue.h(CustomComp, propsMap, {
                    default: renderChildren
                });
            }

            // 4. TIER 2: Semantic Moqui XML AST Mapping to Quasar UI
            switch (tag) {
                case 'screen':
                case 'widgets':
                    return Vue.h('div', withSelection({}, 'blueprint-widgets-root full-width column q-gutter-y-md'), renderChildren());

                case 'container-box':
                    return Vue.h(Vue.resolveComponent('q-card'), withSelection({
                        flat: false,
                        bordered: true
                    }, 'shadow-1 q-mb-md full-width bg-white rounded-borders relative-position agi-hover-container'), {
                        default: () => [
                            renderActionBadge(activeNode),
                            ...renderChildren()
                        ]
                    });

                case 'form-single':
                    return Vue.h('form', withSelection({
                        onSubmit: (e) => e.preventDefault()
                    }, 'moqui-form-single full-width column q-gutter-y-sm relative-position agi-hover-container'), [
                        renderActionBadge(activeNode),
                        ...renderChildren()
                    ]);

                case 'box-body':
                    return Vue.h(Vue.resolveComponent('q-card-section'), withSelection({}, 'q-pa-md column q-gutter-y-sm'), { default: renderChildren });

                case 'field':
                    const fieldIdentifier = rawAttrs.name || activeNode.name || mId;
                    return Vue.h('div', withSelection({
                        'data-field-name': fieldIdentifier,
                        'name': fieldIdentifier
                    }, 'moqui-field-wrapper full-width q-mb-xs relative-position agi-hover-container'), [
                        renderActionBadge(activeNode),
                        ...renderChildren()
                    ]);

                case 'default-field':
                case 'header-field':
                    const fieldTitle = rawAttrs.title || activeNode.title || '';
                    return Vue.h('div', { class: 'column full-width' }, [
                        fieldTitle ? Vue.h('label', {
                            class: 'text-caption text-weight-medium q-mb-xs',
                            style: 'color: var(--agi-text-main);'
                        }, fieldTitle) : null,
                        Vue.h('div', { class: 'full-width' }, renderChildren())
                    ]);

                case 'display-entity':
                    return Vue.h('span', { class: 'text-caption text-indigo-9 bg-indigo-1 q-px-xs rounded-borders font-mono' },
                        `[${rawAttrs['entity-name'] || 'Entity'}: ${rawAttrs['text'] || 'Value'}]`
                    );

                case 'link':
                case 'm-link':
                    return Vue.h(Vue.resolveComponent('q-btn'), withSelection({
                        label: activeNode.text || rawAttrs.text || 'Link',
                        dense: true,
                        flat: true,
                        size: 'sm',
                        color: 'primary'
                    }));

                case 'box-header':
                    const headerTitle = rawAttrs.title || activeNode.title || 'Panel';
                    return Vue.h(Vue.resolveComponent('q-card-section'), withSelection({}, 'bg-blue-grey-1 q-py-sm text-subtitle2 text-weight-bold row items-center justify-between'), () => [
                        Vue.h('span', { style: 'color: var(--agi-text-main);' }, headerTitle),
                        Vue.h(Vue.resolveComponent('q-icon'), { name: 'widgets', size: '16px', color: 'blue-grey-6' })
                    ]);

                case 'text-line':
                case 'm-text-line':
                    return Vue.h(Vue.resolveComponent('q-input'), {
                        modelValue: rawAttrs.value || '',
                        placeholder: rawAttrs.placeholder || (rawAttrs.size ? `Size: ${rawAttrs.size}` : ''),
                        outlined: true,
                        dense: true,
                        readonly: true,
                        class: 'bg-white pointer-events-none'
                    });

                case 'date-time':
                case 'm-date-time':
                    return Vue.h(Vue.resolveComponent('q-input'), {
                        modelValue: rawAttrs.format ? `Format: ${rawAttrs.format}` : 'YYYY-MM-DD',
                        outlined: true,
                        dense: true,
                        readonly: true,
                        class: 'bg-white pointer-events-none',
                        append: () => Vue.h(Vue.resolveComponent('q-icon'), { name: 'event', class: 'cursor-pointer' })
                    });

                case 'drop-down':
                case 'm-drop-down':
                    const options = childNodes
                        .filter(c => (c._moquiTag === 'option' || c.name === 'option'))
                        .map(c => (c.attributes?.text || c.text || c.attributes?.key || c.key || ''));

                    return Vue.h(Vue.resolveComponent('q-select'), {
                        modelValue: options.length > 0 ? options[0] : 'Select Option...',
                        options: options.length > 0 ? options : ['Select Option...'],
                        outlined: true,
                        dense: true,
                        readonly: true,
                        class: 'bg-white pointer-events-none'
                    });

                case 'submit':
                case 'm-submit':
                case 'q-btn':
                    return Vue.h(Vue.resolveComponent('q-btn'), withSelection({
                        label: rawAttrs.text || activeNode.title || 'Submit',
                        icon: (rawAttrs.icon || '').replace(/^fa fa-/, '') || 'check',
                        color: 'primary',
                        dense: true,
                        unelevated: true
                    }, 'q-px-md q-mt-sm'));

                case 'container-row':
                    return Vue.h('div', withSelection({}, 'row q-col-gutter-md full-width'), renderChildren());

                case 'container-col':
                    const mdSize = rawAttrs.md || '12';
                    return Vue.h('div', withSelection({}, `col-12 col-md-${mdSize}`), renderChildren());

                case 'container':
                case 'div':
                    return Vue.h('div', withSelection({
                        style: rawAttrs.style || ''
                    }, 'moqui-container q-pa-xs'), renderChildren());

                case 'label':
                case 'span':
                    return Vue.h('div', withSelection({}, rawAttrs.style || 'text-body2 text-grey-9'),
                        rawAttrs.text || (childNodes[0] && typeof childNodes[0] === 'string' ? childNodes[0] : ''));

                default:
                    return Vue.h('div', withSelection({
                        'data-tag': tag
                    }, 'moqui-generic-node q-pa-xs'), renderChildren());
            }
        }
    };

    window.AgiComponents['ComponentFactory'] = {
        props: ['components'],
        render() {
            if (!this.components || !Array.isArray(this.components)) return null;
            const BlueprintComponent = Vue.resolveComponent('BlueprintComponent');
            return Vue.h('div', { class: 'column q-gutter-md' },
                this.components.map((comp, i) => Vue.h(BlueprintComponent, { component: comp, key: comp.id || comp.component || i }))
            );
        }
    };

    window.AgiComponents['BlueprintComponent'] = {
        props: ['component'],
        setup() {
            const dataStore = Vue.inject('blueprintDataStore', Vue.ref({}));
            const selectComponent = Vue.inject('selectComponent', () => { });
            const selectedComponent = Vue.inject('selectedComponent', Vue.ref(null));
            return { dataStore, selectComponent, selectedComponent };
        },
        render() {
            const comp = this.component;
            if (!comp) return null;

            const isSelected = this.selectedComponent && this.selectedComponent.id === comp.id;

            let type = comp.component ? comp.component.toLowerCase() : 'div';
            const props = comp.properties || {};
            const children = comp.children || [];

            let macroDef = BlueprintClient.macros[type];
            let resolvedComponent = type;
            let resolvedProps = { ...props };
            let resolvedChildren = [...children];
            let isQuasar = false;

            if (macroDef) {
                resolvedComponent = macroDef.component || 'div';
                resolvedProps = { ...macroDef.properties, ...resolvedProps };
                if (macroDef.children) {
                    resolvedChildren = [...(macroDef.children || []), ...resolvedChildren];
                }
                type = resolvedComponent.toLowerCase();
            }

            let boundValue = this.dataStore[comp.id];
            if (comp.id && boundValue !== undefined) {
                resolvedProps.value = boundValue;
            }

            if (type.startsWith('q-')) {
                isQuasar = true;

                if (resolvedProps.required === true && !resolvedProps.rules) {
                    const labelText = resolvedProps.label || "Field";
                    resolvedProps.rules = [(val) => {
                        return (!!val && val.toString().trim().length > 0) || (labelText + " is required");
                    }];
                    resolvedProps['lazy-rules'] = false;
                }

                const currentVal = (this.dataStore[comp.id] !== undefined) ? this.dataStore[comp.id] : (resolvedProps.modelValue || (resolvedProps.value || ""));
                resolvedProps.modelValue = currentVal;
                resolvedProps['onUpdate:modelValue'] = (val) => {
                    this.dataStore[comp.id] = val;
                };
                delete resolvedProps.value;
            }

            let quasarCompName = resolvedComponent;
            let quasarProps = { ...resolvedProps };

            if (!isQuasar) {
                switch (type) {
                    case 'displayfield':
                    case 'text-field':
                        quasarCompName = 'q-input';
                        quasarProps = {
                            outlined: true,
                            label: resolvedProps.label || resolvedProps.name,
                            modelValue: resolvedProps.value || '',
                            readonly: type === 'displayfield',
                            ...resolvedProps
                        };
                        isQuasar = true;
                        break;
                    case 'container':
                        quasarCompName = 'div';
                        quasarProps = { class: 'q-pa-sm bg-grey-2 rounded-borders', ...resolvedProps };
                        break;
                    case 'header':
                        quasarCompName = 'div';
                        quasarProps = { class: 'text-h6 q-mb-sm', ...resolvedProps };
                        return Vue.h(quasarCompName, quasarProps, resolvedProps.text || 'Header');
                    default:
                        quasarCompName = 'div';
                        quasarProps = { ...resolvedProps, class: 'q-pa-sm border-dashed text-caption text-grey', style: 'border: 1px dashed #ccc' };
                }
            }

            const QuasarComp = (quasarCompName.startsWith('q-')) ? Vue.resolveComponent(quasarCompName) : quasarCompName;
            const ComponentFactory = Vue.resolveComponent('ComponentFactory');

            let childNodes = undefined;
            if (resolvedChildren.length > 0) {
                childNodes = { default: () => Vue.h(ComponentFactory, { components: resolvedChildren }) };
            } else if (resolvedProps.text && !isQuasar) {
                childNodes = { default: () => resolvedProps.text };
            }

            const finalQuasarProps = {
                ...quasarProps,
                onClick: (e) => {
                    e.stopPropagation();
                    this.selectComponent(comp);
                },
                style: (quasarProps.style || '') + (isSelected ? '; border: 2px solid #1976D2 !important; box-shadow: 0 0 10px rgba(25,118,210,0.5)' : '')
            };

            return childNodes ? Vue.h(QuasarComp, finalQuasarProps, childNodes) : Vue.h(QuasarComp, finalQuasarProps);
        }
    };

    window.AgiComponents['LogicRenderer'] = {
        props: ['actions'],
        template: `
                <div class="column items-center q-gutter-y-lg q-py-xl" style="position: relative;">
                    <div style="position: absolute; top: 0; bottom: 0; left: 50%; width: 4px; background: rgba(63, 81, 181, 0.1); transform: translateX(-50%); z-index: 0;"></div>
                    
                    <template v-for="(action, i) in actions" :key="action.id || i">
                        <logic-action :action="action" :index="i" />
                    </template>
                    
                    <div class="q-pa-lg bg-indigo-1 rounded-borders border-dashed text-center" style="width: 300px; border: 2px dashed rgba(63, 81, 181, 0.3); color: #3f51b5; cursor: pointer; z-index: 1;">
                         <q-icon name="add_circle" size="md" class="q-mb-xs"></q-icon>
                         <div class="text-weight-bold">Append Action</div>
                    </div>
                </div>
            `
    };

    window.AgiComponents['LogicAction'] = {
        props: ['action', 'index'],
        setup(props) {
            const selectComponent = Vue.inject('selectComponent', () => { });
            const selectedComponent = Vue.inject('selectedComponent', Vue.ref(null));
            const isSelected = Vue.computed(() => selectedComponent.value && selectedComponent.value.id === props.action.id);
            return { selectComponent, isSelected };
        },
        template: `
                <q-card class="logic-action-card shadow-10 cursor-pointer" 
                        :class="isSelected ? 'bg-indigo-10 text-white' : 'bg-white text-indigo-10'"
                        style="width: 450px; z-index: 10; border-radius: 12px; transition: all 0.3s ease;"
                        @click="selectComponent(action)">
                    <q-card-section class="q-pa-md">
                        <div class="row no-wrap items-center">
                            <div class="bg-indigo-1 text-indigo-10 q-pa-sm rounded-borders q-mr-md shadow-inner text-weight-bold" style="min-width: 35px; text-align: center;">
                                {{ index + 1 }}
                            </div>
                            <div class="col">
                                <div class="text-overline opacity-60" style="line-height: 1;">LOGIC STEP</div>
                                <div class="text-h6 text-weight-bold truncate" style="line-height: 1.2;">{{ action.type || 'Action' }}</div>
                            </div>
                            <q-icon name="bolt" :color="isSelected ? 'amber' : 'indigo-4'" size="md" />
                        </div>
                    </q-card-section>
                    
                    <q-separator :dark="isSelected"></q-separator>
                    
                    <q-card-section v-if="action.id" class="q-py-sm q-px-md opacity-80 italic text-caption">
                         id: {{ action.id }}
                    </q-card-section>
                </q-card>
            `
    };

    const BlueprintClient = {
        macros: {},

        loadMacros: async function () {
            try {
                const response = await fetch('/rest/s1/agi-ide/getUiMacros');
                const data = await response.json();
                this.macros = data.macros || {};
                console.log("Aitree UI Macros Loaded:", this.macros);
            } catch (e) {
                console.error("Failed to load macros", e);
            }
        },

        fetchBlueprint: async function (componentName, screenPath) {
            const response = await fetch(`/rest/s1/agi-ide/getBlueprint?componentName=${componentName}&screenPath=${screenPath}`);
            const data = await response.json();
            return data.blueprint;
        },

        setupSSE: function (componentName, screenPath, onUpdate, onCommand) {
            const url = `/rest/s1/agi-ide/registerClient?componentName=${componentName}&screenPath=${screenPath}`;
            const eventSource = new EventSource(url);

            eventSource.addEventListener('update', (event) => {
                console.log("Blueprint Update Received:", event.data);
                const data = JSON.parse(event.data);
                if (data.screen === screenPath) {
                    this.fetchBlueprint(componentName, screenPath).then(onUpdate);
                }
            });

            eventSource.addEventListener('connected', (event) => {
                console.log("SSE Connected:", JSON.parse(event.data));
            });

            eventSource.addEventListener('command', (event) => {
                console.log("Blueprint Command Received:", event.data);
                if (onCommand) {
                    onCommand(JSON.parse(event.data));
                }
            });

            eventSource.onerror = (err) => {
                console.error("SSE Error:", err);
            };

            return eventSource;
        },

        processCommand: function (blueprint, cmd) {
            if (!blueprint || !cmd) return;

            const findCompById = (structure, id) => {
                if (!structure) return null;
                for (let comp of structure) {
                    if (comp.id === id) return comp;
                    if (comp.children) {
                        const found = findCompById(comp.children, id);
                        if (found) return found;
                    }
                }
                return null;
            };

            switch (cmd.action) {
                case 'updateProperty':
                    const target = findCompById(blueprint.structure, cmd.payload.id);
                    if (target) {
                        const originalStyle = target.properties.style || '';
                        target.properties = {
                            ...(target.properties || {}),
                            ...(cmd.payload.properties || {})
                        };
                        setTimeout(() => {
                            target.properties.style = originalStyle;
                        }, 2000);
                    }
                    break;
                case 'addComponent':
                    if (!blueprint.structure) blueprint.structure = [];
                    blueprint.structure.push(cmd.payload);
                    break;
                case 'addMultipleComponents':
                    if (!blueprint.structure) blueprint.structure = [];
                    if (cmd.payload && Array.isArray(cmd.payload.components)) {
                        cmd.payload.components.forEach(c => blueprint.structure.push(c));
                    }
                    break;
                case 'clear':
                    blueprint.structure = [];
                    break;
                default:
                    console.warn("[Flash-Safe] Unknown command action:", cmd.action);
            }
        },
    };

    window.BlueprintClient = BlueprintClient;
    console.info("🔌 [BlueprintClient] Component definitions successfully linked to window.AgiComponents.");
})();