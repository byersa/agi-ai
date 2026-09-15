(function () {
    const DiscussionTree = {
        name: 'DiscussionTree',
        emits: ['node-selected', 'discussion-selected'],
        props: {
            targetComponent: { type: String, default: '' },
            scopeId: { type: String, default: '' }
        },
        data() {
            return {
                treeNodes: [],
                loading: false,
                selectedNodeKey: ''
            };
        },
        mounted() {
            this.fetchTree();
        },
        methods: {
            resolveCsrf() {
                return window.AGI_SERVER_CSRF_TOKEN || (window.moqui && window.moqui.moquiSessionToken) || "";
            },
            async fetchTree() {
                this.loading = true;
                try {
                    const resp = await axios.get('/rest/s1/agi-ai/discussions', {
                        params: { targetComponent: this.targetComponent || null },
                        headers: { 'moquiSessionToken': this.resolveCsrf() }
                    });
                    this.treeNodes = resp.data?.treeNodes || [];
                    this.loading = false;
                } catch (e) {
                    this.loading = false;
                }
            },
            selectNode(node) {
                this.selectedNodeKey = node.nodeKey;
                this.$emit('node-selected', node);
                if (node.discussionId) this.$emit('discussion-selected', node);
            }
        },
        template: `
            <div class="fit q-pa-xs bg-slate-950 text-white font-sans">
                <div class="row items-center justify-between q-pa-xs">
                    <span class="text-caption text-weight-bold text-slate-300">DISCUSSIONS</span>
                    <q-btn flat round dense icon="refresh" size="xs" color="slate-400" @click="fetchTree" />
                </div>
                <q-separator dark class="q-my-xs bg-slate-800" />
                <q-tree
                    :nodes="treeNodes"
                    node-key="nodeKey"
                    label-key="label"
                    default-expand-all
                    class="text-caption text-slate-200"
                >
                    <template v-slot:default-header="prop">
                        <div 
                            class="row items-center full-width q-pa-xs cursor-pointer rounded-borders"
                            :class="{ 'bg-primary text-white text-weight-bold': selectedNodeKey === prop.node.nodeKey }"
                            @click="selectNode(prop.node)"
                        >
                            <q-icon :name="prop.node.discussionId && !prop.node.messageId ? 'forum' : 'chat_bubble'" size="14px" class="q-mr-xs" />
                            <span class="col-grow ellipsis">{{ prop.node.label }}</span>
                            <q-badge v-if="prop.node.messageCount" color="slate-800" text-color="cyan-3" class="q-ml-xs" style="font-size: 9px;">
                                {{ prop.node.messageCount }}
                            </q-badge>
                        </div>
                    </template>
                </q-tree>
            </div>
        `
    };

    window.DiscussionTree = DiscussionTree;
    if (!window.AgiComponents) window.AgiComponents = {};
    window.AgiComponents['discussion-tree'] = DiscussionTree;

    const registerTree = () => {
        if (window.moqui && window.moqui.webrootVueApp) {
            window.moqui.webrootVueApp.component('discussion-tree', DiscussionTree);
        } else {
            setTimeout(registerTree, 50);
        }
    };
    registerTree();
})();