// runtime/component/agi-ai/assets/AgiComponentLibrary.js
(function () {
    window.AgiComponentLibrary = {
        registerAll(app) {
            console.info("📦 [AGI-AI] Initializing lean macro component configurations...");

            // 1. Core Dropdown Wrapper
            app.component('m-drop-down', {
                props: ['modelValue', 'optionsUrl', 'label', 'allowEmpty', 'optionsLoadInit', 'dependsOn'],
                emits: ['update:modelValue'],
                template: `
                    <q-select dense outlined options-dense :label="label"
                        :model-value="modelValue" @update:model-value="$emit('update:modelValue', $event)"
                        :options="computedOptions" :loading="loading" @show="loadOptions" emit-value map-options behavior="menu" />
                `,
                data() { return { computedOptions: [], loading: false, loaded: false } },
                methods: {
                    loadOptions() {
                        if (this.loaded || !this.optionsUrl) return;
                        this.loading = true;
                        let url = this.optionsUrl;
                        if (this.dependsOn && window.moquiApp) {
                            let dependKey = Object.keys(this.dependsOn)[0];
                            let targetField = this.dependsOn[dependKey];
                            let parentVal = window.moquiApp.fields?.[targetField.replace('fields.', '')];
                            if (parentVal) url += (url.indexOf('?') > 0 ? '&' : '?') + dependKey + '=' + encodeURIComponent(parentVal);
                        }
                        $.ajax({
                            type: 'GET', url: url, dataType: 'json',
                            success: (data) => {
                                this.computedOptions = (data || []).map(opt => ({
                                    label: opt.label || opt.text || opt.value,
                                    value: opt.value
                                }));
                                this.loaded = true;
                            },
                            complete: () => { this.loading = false; }
                        });
                    }
                },
                mounted() { if (this.optionsLoadInit) this.loadOptions(); }
            });

            // 2. Simple Text Input Macro Placeholder
            app.component('m-text-line', {
                props: ['modelValue', 'label', 'disable', 'bgColor'],
                emits: ['update:modelValue'],
                template: `<q-input dense outlined stack-label :bg-color="bgColor" :label="label" :disable="disable" :model-value="modelValue" @update:model-value="$emit('update:modelValue', $event)" />`
            });

            // Future macro definitions (m-date-time, m-display) append cleanly right here...
        }
    };
})();