(function () {
    const DiscussionTree = {
        name: 'DiscussionTree',
        template: `
            <div class="discussion-tree-root fit q-pa-sm">
                <!-- Global Tree Toolbar -->
                <div class="row items-center justify-between q-mb-xs q-px-xs">
                    <div class="text-caption text-weight-bold text-grey-4 row items-center">
                        <q-icon name="account_tree" class="q-mr-xs" color="primary" />
                        INTENT &amp; DISCUSSION TREE
                    </div>
                    <div class="row q-gutter-xs">
                        <q-btn size="xs" flat round icon="unfold_more" @click="expandAllNodes">
                            <q-tooltip>Expand All</q-tooltip>
                        </q-btn>
                        <q-btn size="xs" flat round icon="unfold_less" @click="collapseAllNodes">
                            <q-tooltip>Collapse All</q-tooltip>
                        </q-btn>
                        <q-btn size="xs" flat round icon="refresh" @click="fetchTree">
                            <q-tooltip>Refresh Tree</q-tooltip>
                        </q-btn>
                    </div>
                </div>

                <q-separator class="q-mb-sm bg-slate-700" />

                <!-- Loading State -->
                <div v-if="loading" class="row justify-center q-my-md">
                    <q-spinner color="primary" size="2em" />
                </div>

                <!-- Error State -->
                <div v-else-if="error" class="text-negative text-caption q-pa-xs">
                    {{ error }}
                </div>

                <!-- Empty State -->
                <div v-else-if="!treeNodes || treeNodes.length === 0" class="text-grey-5 text-caption text-italic q-pa-sm">
                    No intent or discussion nodes found for this anchor.
                </div>

                <!-- Main Recursive Tree -->
                <q-tree
                    v-else
                    ref="qTreeRef"
                    :nodes="treeNodes"
                    node-key="nodeKey"
                    label-key="label"
                    default-expand-all
                    class="q-tree-custom text-white"
                >
                    <!-- Node Header Slot -->
                    <template v-slot:default-header="prop">
                        <div 
                            class="row items-center full-width q-pa-xs rounded-borders tree-node-row cursor-pointer"
                            :class="{ 'bg-slate-800 text-cyan-3 text-weight-bold': selectedNodeId === prop.node.nodeKey }"
                            draggable="true"
                            @dragstart="handleDragStart($event, prop.node)"
                            @dragover.prevent="handleDragOver($event)"
                            @drop.prevent="handleDropAndPaste($event, prop.node)"
                            @click="selectNode(prop.node)"
                        >
                            <!-- Type Icon -->
                            <q-icon 
                                :name="getNodeIcon(prop.node.workEffortTypeEnumId || prop.node.wikiSpaceId)" 
                                :color="getNodeIconColor(prop.node.workEffortTypeEnumId || prop.node.wikiSpaceId)" 
                                size="18px"
                                class="q-mr-xs" 
                            />

                            <!-- Title -->
                            <div class="col-grow text-body2 font-mono text-weight-medium row items-center">
                                <span>{{ prop.node.label || prop.node.pagePath || 'Unnamed Node' }}</span>
                                <span v-if="prop.node.publishedVersionName || prop.node.revisionNumber" class="text-caption text-grey-5 q-ml-xs">
                                    ({{ prop.node.publishedVersionName || prop.node.revisionNumber }})
                                </span>

                                <!-- targetMariaId Badge (Canvas Element Pointer) -->
                                <q-badge v-if="prop.node.targetMariaId" color="deep-purple-8" text-color="white" class="q-ml-sm text-caption">
                                    #{{ prop.node.targetMariaId }}
                                </q-badge>
                            </div>

                            <!-- Status Checkbox -->
                            <q-checkbox 
                                v-model="prop.node.isCompleted" 
                                size="xs" 
                                color="positive"
                                class="q-mr-xs"
                                @update:model-value="toggleNodeStatus(prop.node)"
                            >
                                <q-tooltip>Status: {{ prop.node.statusId || 'Active' }}</q-tooltip>
                            </q-checkbox>

                            <!-- Action Toolbar -->
                            <div class="row items-center q-gutter-xs node-action-box">
                                <q-icon name="add" size="16px" class="q-hoverable" @click.stop="addChildNode(prop.node)">
                                    <q-tooltip>Add Sub-Goal / Discussion</q-tooltip>
                                </q-icon>
                                <q-icon name="link" size="16px" color="secondary" class="q-hoverable" @click.stop="linkToAdditionalArtifact(prop.node)">
                                    <q-tooltip>Link to Additional Artifact</q-tooltip>
                                </q-icon>
                                <q-icon name="content_copy" size="16px" class="q-hoverable" @click.stop="copyNodeJson(prop.node)">
                                    <q-tooltip>Copy Node JSON</q-tooltip>
                                </q-icon>
                                <q-icon name="content_paste" size="16px" class="q-hoverable" @click.stop="pasteCopiedNode(prop.node)">
                                    <q-tooltip>Paste Child Node</q-tooltip>
                                </q-icon>
                                <q-icon name="delete" size="16px" color="negative" class="q-hoverable" @click.stop="deleteNode(prop.node)">
                                    <q-tooltip>Cancel / Delete Node</q-tooltip>
                                </q-icon>
                            </div>
                        </div>
                    </template>

                    <!-- Dynamic Node Detail Slot -->
                    <template v-slot:default-body="prop">
                        <div class="q-ml-md q-pa-xs">
                            <slot name="node-detail" :node="prop.node">
                                <discussion-detail :node="prop.node" />
                            </slot>
                        </div>
                    </template>
                </q-tree>
            </div>
        `,

        props: {
            wikiSpaceId: { type: String, default: 'AGI_INTENT' },
            agiArtifactId: { type: String, required: false },
            sourceReferenceId: { type: String, required: false }
        },
        emits: ['node-selected', 'version-selected', 'tree-updated'],
        data() {
            return {
                treeNodes: [],
                loading: false,
                error: null,
                selectedNodeId: '',
                copiedNodeData: null
            };
        },
        mounted() {
            this.fetchTree();
        },
        watch: {
            wikiSpaceId() { this.fetchTree(); },
            agiArtifactId() { this.fetchTree(); },
            sourceReferenceId() { this.fetchTree(); }
        },
        methods: {
            resolveHeaders() {
                return {
                    'moquiSessionToken': window.AGI_SERVER_CSRF_TOKEN
                        || (window.moqui && window.moqui.moquiSessionToken)
                        || (document.querySelector('meta[name="moqui-session-token"]')?.getAttribute('content'))
                        || ""
                };
            },

            async fetchTree() {
                this.loading = true;
                this.error = null;
                const vm = this;

                const artifactPathVal = this.sourceReferenceId || this.agiArtifactId || '';
                const targetSpace = this.wikiSpaceId || 'AGI_INTENT';

                try {
                    const response = await axios.get('/rest/s1/agi-ide/getWikiTreeNodes', {
                        params: {
                            wikiSpaceId: targetSpace,
                            artifactPath: artifactPathVal
                        },
                        headers: this.resolveHeaders()
                    });

                    vm.loading = false;
                    const resData = response.data;
                    vm.treeNodes = vm.formatNodes(resData?.treeNodes || resData || []);
                    vm.$emit('tree-updated');
                    vm.$nextTick(function () {
                        if (vm.$refs.qTreeRef) vm.$refs.qTreeRef.expandAll();
                    });
                } catch (err) {
                    vm.loading = false;
                    vm.error = "Error loading tree: " + (err.response?.data?.errors || err.message);
                }
            },

            formatNodes(nodes) {
                const vm = this;
                return nodes.map(function (n) {
                    const key = n.workEffortId || n.wikiPageId || n.id || String(Math.random());
                    return {
                        ...n,
                        nodeKey: key,
                        workEffortId: n.workEffortId || n.id || null,
                        wikiPageId: n.wikiPageId || null,
                        label: n.label || n.workEffortName || n.pagePath || 'Unnamed Node',
                        isCompleted: n.statusId === 'WeComplete',
                        children: n.children ? vm.formatNodes(n.children) : []
                    };
                });
            },

            selectNode(node) {
                this.selectedNodeId = node.nodeKey;
                this.$emit('node-selected', node);

                if (node.publishedVersionName || node.revisionNumber || node.metaJsonBuffer) {
                    this.$emit('version-selected', {
                        wikiPageId: node.wikiPageId,
                        workEffortId: node.workEffortId,
                        agiArtifactId: node.agiArtifactId || this.agiArtifactId,
                        versionTag: node.publishedVersionName || node.revisionNumber || 'v1.0.0',
                        metaJsonBuffer: node.metaJsonBuffer
                    });
                }
            },

            getNodeIcon(type) {
                if (type === 'WetIntent' || type === 'AGI_INTENT') return 'lightbulb';
                if (type === 'WetAction') return 'build';
                return 'chat';
            },
            getNodeIconColor(type) {
                if (type === 'WetIntent' || type === 'AGI_INTENT') return 'amber-9';
                if (type === 'WetAction') return 'positive';
                return 'primary';
            },

            addChildNode(parentNode) {
                const vm = this;

                this.$q.dialog({
                    title: 'New Sub-Node Title',
                    message: 'Enter title for the new sub-node under ' + (parentNode.label || parentNode.pagePath) + ':',
                    prompt: { model: '', type: 'text' },
                    cancel: true,
                    persistent: true
                }).onOk(async function (val) {
                    if (!val) return;
                    vm.loading = true;

                    const subPath = (parentNode.pagePath ? parentNode.pagePath + '/' : '') + val.replace(/\s+/g, '');

                    try {
                        await axios.post('/rest/s1/agi-ide/saveWikiNode', {
                            wikiSpaceId: vm.wikiSpaceId || 'AGI_INTENT',
                            parentWikiPageId: parentNode.wikiPageId,
                            pagePath: subPath,
                            content: '# ' + val + '\n\n'
                        }, { headers: vm.resolveHeaders() });

                        vm.fetchTree();
                    } catch (err) {
                        vm.loading = false;
                        if (vm.$q) vm.$q.notify({ type: 'negative', message: 'Failed to add node: ' + err.message });
                    }
                });
            },

            linkToAdditionalArtifact(node) {
                const vm = this;
                this.$q.dialog({
                    title: 'Cross-Link Wiki Node',
                    message: 'Enter target AgiArtifact ID or path to associate with this node:',
                    prompt: { model: '', type: 'text' },
                    cancel: true
                }).onOk(async function (targetArtifactId) {
                    if (!targetArtifactId) return;
                    try {
                        await axios.post('/rest/s1/agi-ide/blueprint/link-artifact', {
                            wikiPageId: node.wikiPageId,
                            workEffortId: node.workEffortId,
                            agiArtifactId: targetArtifactId,
                            assocTypeEnumId: 'AweaCrossReference'
                        }, { headers: vm.resolveHeaders() });

                        if (vm.$q) vm.$q.notify({ type: 'positive', message: 'Linked node to target artifact successfully.' });
                    } catch (err) {
                        if (vm.$q) vm.$q.notify({ type: 'negative', message: 'Link failed: ' + err.message });
                    }
                });
            },

            async toggleNodeStatus(node) {
                const vm = this;
                const targetWeId = node.workEffortId || (node.id && !node.id.startsWith('wiki_') ? node.id : null);

                // Guard: Do not dispatch if there is no backing WorkEffort record
                if (!targetWeId) {
                    if (vm.$q) {
                        vm.$q.notify({
                            type: 'warning',
                            message: 'Cannot update status: node has no associated WorkEffort record.'
                        });
                    }
                    return;
                }

                const newStatus = node.isCompleted ? 'WeComplete' : 'WeInProgress';
                try {
                    await axios.post('/rest/s1/agi-ide/blueprint/update-status', {
                        workEffortId: targetWeId,
                        statusId: newStatus,
                        agiArtifactId: node.agiArtifactId || vm.agiArtifactId || null
                    }, { headers: this.resolveHeaders() });

                    node.statusId = newStatus;
                    vm.$emit('tree-updated');
                } catch (err) {
                    node.isCompleted = !node.isCompleted;
                    if (vm.$q) vm.$q.notify({ type: 'negative', message: 'Failed to update status: ' + err.message });
                }
            },

            handleDragStart(e, node) {
                e.dataTransfer.effectAllowed = 'copy';
                e.dataTransfer.setData('text/plain', JSON.stringify(node));
                this.copiedNodeData = node;
            },
            handleDragOver(e) {
                e.dataTransfer.dropEffect = 'copy';
            },
            async handleDropAndPaste(e, targetNode) {
                const rawData = e.dataTransfer.getData('text/plain');
                if (!rawData) return;
                const sourceNode = JSON.parse(rawData);

                if (sourceNode.nodeKey === targetNode.nodeKey) return;

                const vm = this;
                const movedPath = (targetNode.pagePath ? targetNode.pagePath + '/' : '') + (sourceNode.label || 'MovedNode');

                try {
                    await axios.post('/rest/s1/agi-ide/saveWikiNode', {
                        wikiSpaceId: vm.wikiSpaceId || 'AGI_INTENT',
                        parentWikiPageId: targetNode.wikiPageId,
                        pagePath: movedPath,
                        content: sourceNode.content || ''
                    }, { headers: vm.resolveHeaders() });

                    vm.fetchTree();
                } catch (err) {
                    if (vm.$q) vm.$q.notify({ type: 'negative', message: 'Failed to move node: ' + err.message });
                }
            },

            copyNodeJson(node) {
                this.copiedNodeData = node;
                if (this.$q) this.$q.notify({ type: 'info', message: 'Copied node: ' + (node.label || node.pagePath) });
            },
            async pasteCopiedNode(targetNode) {
                if (!this.copiedNodeData) {
                    if (this.$q) this.$q.notify({ type: 'warning', message: 'No node data copied in clipboard.' });
                    return;
                }
                const vm = this;
                const pastedPath = (targetNode.pagePath ? targetNode.pagePath + '/' : '') + (vm.copiedNodeData.label || 'Copy');

                try {
                    await axios.post('/rest/s1/agi-ide/saveWikiNode', {
                        wikiSpaceId: vm.wikiSpaceId || 'AGI_INTENT',
                        parentWikiPageId: targetNode.wikiPageId,
                        pagePath: pastedPath,
                        content: vm.copiedNodeData.content || ''
                    }, { headers: vm.resolveHeaders() });

                    vm.fetchTree();
                } catch (err) {
                    if (vm.$q) vm.$q.notify({ type: 'negative', message: 'Failed to paste node: ' + err.message });
                }
            },

            deleteNode(node) {
                const vm = this;
                const targetWeId = node.workEffortId || (node.id && !node.id.startsWith('wiki_') ? node.id : null);

                if (!targetWeId) {
                    if (vm.$q) vm.$q.notify({ type: 'warning', message: 'Node has no WorkEffort ID to cancel.' });
                    return;
                }

                this.$q.dialog({
                    title: 'Confirm Cancellation',
                    message: 'Are you sure you want to cancel: ' + (node.label || node.pagePath) + '?',
                    cancel: true
                }).onOk(async function () {
                    try {
                        await axios.post('/rest/s1/agi-ide/blueprint/update-status', {
                            workEffortId: targetWeId,
                            statusId: 'WeCancelled',
                            agiArtifactId: node.agiArtifactId || vm.agiArtifactId || null
                        }, { headers: vm.resolveHeaders() });

                        vm.fetchTree();
                    } catch (err) {
                        if (vm.$q) vm.$q.notify({ type: 'negative', message: 'Failed to delete node: ' + err.message });
                    }
                });
            },

            expandAllNodes() { if (this.$refs.qTreeRef) this.$refs.qTreeRef.expandAll(); },
            collapseAllNodes() { if (this.$refs.qTreeRef) this.$refs.qTreeRef.collapseAll(); }
        }
    };

    window.DiscussionTree = DiscussionTree;
    if (!window.AgiComponents) window.AgiComponents = {};
    window.AgiComponents['discussion-tree'] = DiscussionTree;

    const registerDiscussionTree = () => {
        if (window.moqui && window.moqui.webrootVueApp) {
            if (!window.moqui.webrootVueApp.component('discussion-tree')) {
                window.moqui.webrootVueApp.component('discussion-tree', DiscussionTree);
                console.info("🚀 [AGI] Registered 'discussion-tree' successfully.");
            }
        } else {
            setTimeout(registerDiscussionTree, 50);
        }
    };
    registerDiscussionTree();
})();