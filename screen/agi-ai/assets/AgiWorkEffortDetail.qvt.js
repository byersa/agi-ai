(function () {
    const AgiWorkEffortDetail = {
        name: 'AgiWorkEffortDetail',
        template: `
            <q-form @submit="saveDetail" class="q-gutter-xs q-pa-xs">
                <q-input 
                    v-model="formData.workEffortName" 
                    label="Title / Summary" 
                    dense 
                    outlined 
                />
                <q-input 
                    v-model="formData.description" 
                    label="Detailed Description / Intent Specs" 
                    type="textarea" 
                    rows="3" 
                    dense 
                    outlined 
                />
                <q-input 
                    v-model="formData.targetMariaId" 
                    label="Canvas Element Target (#mariaId)" 
                    dense 
                    outlined 
                />
                <div class="row justify-end q-mt-xs">
                    <q-btn 
                        type="submit" 
                        label="Save Specification" 
                        icon="save" 
                        color="primary" 
                        size="sm" 
                        :loading="saving" 
                    />
                </div>
            </q-form>
        `,
        props: {
            node: { type: Object, required: true }
        },
        data() {
            return {
                saving: false,
                formData: {
                    workEffortName: this.node?.workEffortName || '',
                    description: this.node?.description || '',
                    targetMariaId: this.node?.targetMariaId || ''
                }
            };
        },
        watch: {
            node: {
                deep: true,
                handler(newNode) {
                    if (newNode) {
                        this.formData.workEffortName = newNode.workEffortName || '';
                        this.formData.description = newNode.description || '';
                        this.formData.targetMariaId = newNode.targetMariaId || '';
                    }
                }
            }
        },
        methods: {
            saveDetail() {
                this.saving = true;
                const vm = this;
                $.ajax({
                    type: 'POST',
                    url: '/rest/s1/agi-ide/blueprint/update-node',
                    data: {
                        workEffortId: vm.node.workEffortId,
                        workEffortName: vm.formData.workEffortName,
                        description: vm.formData.description,
                        targetMariaId: vm.formData.targetMariaId
                    },
                    headers: { 'moquiSessionToken': window.AGI_SERVER_CSRF_TOKEN || "" },
                    success: () => {
                        vm.saving = false;
                        vm.$q.notify({ type: 'positive', message: 'Specification saved successfully.' });
                    },
                    error: () => { vm.saving = false; }
                });
            }
        }
    };

    window.AgiWorkEffortDetail = AgiWorkEffortDetail;
    window.AgiComponents = window.AgiComponents || {};
    window.AgiComponents['agi-work-effort-detail'] = AgiWorkEffortDetail;

    const registerComp = () => {
        if (window.moqui && window.moqui.webrootVueApp) {
            window.moqui.webrootVueApp.component('agi-work-effort-detail', AgiWorkEffortDetail);
            window.moqui.webrootVueApp.component('AgiWorkEffortDetail', AgiWorkEffortDetail);
        } else {
            setTimeout(registerComp, 50);
        }
    };
    registerComp();
})();