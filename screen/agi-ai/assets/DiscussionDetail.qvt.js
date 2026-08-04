(function () {
    const DiscussionDetail = {
        name: 'DiscussionDetail',
        template: `
            <div class="discussion-detail-container q-pa-sm border-dashed rounded-borders bg-grey-1">
                <!-- Header Bar -->
                <div v-if="node" class="row items-center justify-between q-mb-xs q-px-xs">
                    <span class="text-caption text-weight-bold text-grey-7 font-mono">
                        SPECIFICATION DETAIL [{{ node.workEffortId || 'New Node' }}]
                    </span>
                </div>

                <!-- 🎯 1. Server-Compiled Moqui QMETA AST Node Interpreter -->
                <m-blueprint-node 
                    v-if="node && (node.formAst || node.qmetaAst)" 
                    :node="node.formAst || node.qmetaAst" 
                    :context="{ node: node }" 
                />

                <!-- 🎯 2. Scoped Slot / Default Fallback Controls -->
                <slot v-else :node="node">
                    <div class="q-gutter-y-xs q-pa-xs">
                        <q-input 
                            v-model="node.workEffortName" 
                            label="Title / Summary" 
                            dense 
                            outlined 
                            bg-color="white" 
                        />
                        <q-input 
                            v-model="node.description" 
                            label="Detailed Description / Intent Specs" 
                            type="textarea" 
                            rows="2" 
                            dense 
                            outlined 
                            bg-color="white" 
                        />
                        <q-input 
                            v-model="node.targetMariaId" 
                            label="Canvas Element Target (#mariaId)" 
                            dense 
                            outlined 
                            bg-color="white" 
                        />
                    </div>
                </slot>
            </div>
        `,
        props: {
            node: { type: Object, required: true }
        },
        mounted() {
            return;
        },

    };

    window.DiscussionDetail = DiscussionDetail;
    if (!window.AgiComponents) window.AgiComponents = {};
    window.AgiComponents['discussion-detail'] = DiscussionDetail;

    const registerDiscussionDetail = () => {
        if (window.moqui && window.moqui.webrootVueApp) {
            if (!window.moqui.webrootVueApp.component('discussion-detail')) {
                window.moqui.webrootVueApp.component('discussion-detail', DiscussionDetail);
                console.info("🚀 [AGI] Registered 'discussion-detail' successfully.");
            }
        } else {
            setTimeout(registerDiscussionDetail, 50);
        }
    };
    registerDiscussionDetail();
})();