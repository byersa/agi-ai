(function () {
    const MoquiXmlHost = {
        name: 'MoquiXmlHost',
        props: {
            xml: { type: String, required: true },
            contextData: { type: Object, default: () => ({}) }
        },
        data() {
            return {
                ast: null,
                loading: false,
                error: null
            };
        },
        mounted() {
            this.compileXml();
        },
        watch: {
            xml() { this.compileXml(); }
        },
        methods: {
            compileXml() {
                if (!this.xml) return;
                var vm = this;
                vm.loading = true;

                $.ajax({
                    type: 'POST',
                    url: '/rest/s1/agi-ide/blueprint/compile-xml',
                    data: { xmlText: vm.xml },
                    dataType: 'json',
                    headers: { 'X-CSRF-Token': window.AGI_SERVER_CSRF_TOKEN || "" },
                    success: function (data) {
                        vm.loading = false;
                        vm.ast = data.qmetaAst || data;
                    },
                    error: function (err) {
                        vm.loading = false;
                        vm.error = "Failed to compile XML fragment";
                        console.error(err);
                    }
                });
            }
        },
        template: `
            <div class="moqui-xml-host">
                <div v-if="loading" class="q-pa-xs text-caption text-grey"><q-spinner size="1em"/> Compiling Moqui Macros...</div>
                <div v-else-if="error" class="text-negative text-caption">{{ error }}</div>
                <!-- 🎯 Delegates rendering directly to m-blueprint-node once compiled! -->
                <m-blueprint-node v-else-if="ast" :node="ast" :context="contextData" />
            </div>
        `
    };

    window.MoquiXmlHost = MoquiXmlHost;
    window.AgiComponents = window.AgiComponents || {};
    window.AgiComponents['moqui-xml-host'] = MoquiXmlHost;

    const registerMoquiXmlHost = () => {
        if (window.moqui && window.moqui.webrootVueApp) {
            if (!window.moqui.webrootVueApp.component('MoquiXmlHost')) {
                window.moqui.webrootVueApp.component('MoquiXmlHost', MoquiXmlHost);
                window.moqui.webrootVueApp.component('moqui-xml-host', MoquiXmlHost);
                console.info("🚀 [AGI] Registered 'MoquiXmlHost' successfully.");
            }
        } else {
            setTimeout(registerMoquiXmlHost, 50);
        }
    };
    registerMoquiXmlHost();
})();