(function () {
    const DiscussionTree = {
        name: 'DiscussionTree',
        template: `
            <div class="discussion-tree-root fit q-pa-sm">
                <!-- Global Tree Toolbar -->
                <div class="row items-center justify-between q-mb-xs q-px-xs">
                    <div class="text-caption text-weight-bold text-grey-8 row items-center">
                        <q-icon name="account_tree" class="q-mr-xs" color="primary" />
                        INTENT & DISCUSSION TREE
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

                <q-separator class="q-mb-sm" />

                <!-- Loading State -->
                <div v-if="loading" class="row justify-center q-my-md">
                    <q-spinner color="primary" size="2em" />
                </div>

                <!-- Error State -->
                <div v-else-if="error" class="text-negative text-caption q-pa-xs">
                    {{ error }}
                </div>

                <!-- Empty State -->
                <div v-else-if="!treeNodes || treeNodes.length === 0" class="text-grey-6 text-caption text-italic q-pa-sm">
                    No intent or discussion nodes found for this anchor.
                </div>

                <!-- Main Recursive Tree -->
                <q-tree
                    v-else
                    ref="qTreeRef"
                    :nodes="treeNodes"
                    node-key="workEffortId"
                    label-key="workEffortName"
                    default-expand-all
                    class="q-tree-custom"
                >
                    <!-- Node Header Slot -->
                    <template v-slot:default-header="prop">
                        <div 
                            class="row items-center full-width q-pa-xs rounded-borders tree-node-row cursor-pointer"
                            :class="{ 'bg-blue-1 text-primary text-weight-bold': selectedNodeId === prop.node.workEffortId }"
                            draggable="true"
                            @dragstart="handleDragStart($event, prop.node)"
                            @dragover.prevent="handleDragOver($event)"
                            @drop.prevent="handleDropAndPaste($event, prop.node)"
                            @click="selectNode(prop.node)"
                        >
                            <!-- Type Icon -->
                            <q-icon 
                                :name="getNodeIcon(prop.node.workEffortTypeEnumId)" 
                                :color="getNodeIconColor(prop.node.workEffortTypeEnumId)" 
                                size="18px"
                                class="q-mr-xs" 
                            />

                            <!-- Title -->
                            <div class="col-grow text-body2 font-mono text-weight-medium row items-center">
                                <span>{{ prop.node.workEffortName || 'Unnamed Node' }}</span>
                                <span v-if="prop.node.revisionNumber" class="text-caption text-grey-6 q-ml-xs">
                                    ({{ prop.node.revisionNumber }})
                                </span>

                                <!-- targetMariaId Badge (Canvas Element Pointer) -->
                                <q-badge v-if="prop.node.targetMariaId" color="deep-purple-2" text-color="deep-purple-9" class="q-ml-sm text-caption">
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
                                <q-tooltip>Status: {{ prop.node.statusId || 'WeInPlanning' }}</q-tooltip>
                            </q-checkbox>

                            <!-- Action Toolbar -->
                            <div class="row items-center q-gutter-xs node-action-box">
                                <q-icon name="add" size="16px" class="q-hoverable" @click.stop="addChildNode(prop.node)">
                                    <q-tooltip>Add Sub-Goal / Discussion</q-tooltip>
                                </q-icon>
                                <q-icon name="link" size="16px" color="secondary" class="q-hoverable" @click.stop="linkToAdditionalArtifact(prop.node)">
                                    <q-tooltip>Link to Additional Artifact (Many-to-Many)</q-tooltip>
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

                    <!-- 🎯 DYNAMIC NODE BODY SLOT: Delegates rendering to DiscussionDetail -->
                    <template v-slot:default-body="prop">
                        <div class="q-ml-md q-pa-xs">
                            <slot name="node-detail" :node="prop.node">
                                <!-- Default fallback if parent component passes no custom slot -->
                                <discussion-detail :node="prop.node" />
                            </slot>
                        </div>
                    </template>
                </q-tree>
            </div>
        `,

        props: {
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
            agiArtifactId() { this.fetchTree(); },
            sourceReferenceId() { this.fetchTree(); }
        },
        methods: {
            fetchTree() {
                this.loading = true;
                this.error = null;
                var vm = this;

                var reqData = {};
                if (this.agiArtifactId) reqData.agiArtifactId = this.agiArtifactId;
                if (this.sourceReferenceId) reqData.sourceReferenceId = this.sourceReferenceId;

                $.ajax({
                    type: 'POST',
                    url: '/rest/s1/agi-ide/blueprint/tree',
                    data: reqData,
                    dataType: 'json',
                    headers: { 'moquiSessionToken': window.AGI_SERVER_CSRF_TOKEN || "" },
                    error: function (jqXHR, textStatus, errorThrown) {
                        vm.error = "Error loading tree: " + errorThrown;
                        vm.loading = false;
                    },
                    success: function (data) {
                        vm.loading = false;
                        vm.treeNodes = vm.formatNodes(data?.treeNodes || data || []);
                        vm.$emit('tree-updated');
                    }
                });
            },

            formatNodes(nodes) {
                var vm = this;
                return nodes.map(function (n) {
                    return {
                        ...n,
                        isCompleted: n.statusId === 'WeComplete',
                        children: n.children ? vm.formatNodes(n.children) : []
                    };
                });
            },

            selectNode(node) {
                this.selectedNodeId = node.workEffortId;
                this.$emit('node-selected', node);

                if (node.revisionNumber || node.metaJsonBuffer) {
                    this.$emit('version-selected', {
                        workEffortId: node.workEffortId,
                        agiArtifactId: node.agiArtifactId || this.agiArtifactId,
                        versionTag: node.revisionNumber || 'v1.0.0',
                        metaJsonBuffer: node.metaJsonBuffer
                    });
                }
            },

            getNodeIcon(type) {
                if (type === 'WetIntent') return 'lightbulb';
                if (type === 'WetAction') return 'build';
                return 'chat';
            },
            getNodeIconColor(type) {
                if (type === 'WetIntent') return 'amber-9';
                if (type === 'WetAction') return 'positive';
                return 'primary';
            },

            addChildNode(parentNode) {
                var vm = this;

                // Step 1: Prompt for Node Type Selection
                this.$q.dialog({
                    title: 'Select Sub-Node Type',
                    message: 'Choose the type of node to create under: ' + parentNode.workEffortName,
                    options: {
                        type: 'radio',
                        model: 'WetDiscussion', // Default selection
                        items: [
                            { label: '💡 Intent / Goal (WetIntent)', value: 'WetIntent' },
                            { label: '💬 Discussion / Topic (WetDiscussion)', value: 'WetDiscussion' },
                            { label: '🛠️ Action / Implementation (WetAction)', value: 'WetAction' }
                        ]
                    },
                    cancel: true,
                    persistent: true
                }).onOk(function (selectedType) {

                    // Step 2: Prompt for Node Name/Title
                    vm.$q.dialog({
                        title: 'New Node Title',
                        message: 'Enter title for the new sub-node:',
                        prompt: { model: '', type: 'text' },
                        cancel: true,
                        persistent: true
                    }).onOk(function (val) {
                        if (!val) return;
                        vm.loading = true;

                        $.ajax({
                            type: 'POST',
                            url: '/rest/s1/agi-ide/blueprint/create-node',
                            data: {
                                parentWorkEffortId: parentNode.workEffortId,
                                workEffortName: val,
                                agiArtifactId: vm.agiArtifactId,
                                sourceReferenceId: vm.sourceReferenceId,
                                workEffortTypeEnumId: selectedType // 🎯 Dynamically pass user-selected type
                            },
                            dataType: 'json',
                            headers: { 'moquiSessionToken': window.AGI_SERVER_CSRF_TOKEN || "" },
                            success: function () {
                                vm.fetchTree();
                            },
                            error: function () {
                                vm.loading = false;
                            }
                        });
                    });
                });
            },

            // 🎯 LINK TO ADDITIONAL ARTIFACT (Many-to-Many Junction)
            linkToAdditionalArtifact(node) {
                var vm = this;
                this.$q.dialog({
                    title: 'Cross-Link WorkEffort Node',
                    message: 'Enter target AgiArtifact ID or path to associate with this decision:',
                    prompt: { model: '', type: 'text' },
                    cancel: true
                }).onOk(function (targetArtifactId) {
                    if (!targetArtifactId) return;
                    $.ajax({
                        type: 'POST',
                        url: '/rest/s1/agi-ide/blueprint/link-artifact',
                        data: {
                            workEffortId: node.workEffortId,
                            agiArtifactId: targetArtifactId,
                            assocTypeEnumId: 'AweaCrossReference'
                        },
                        dataType: 'json',
                        headers: { 'moquiSessionToken': window.AGI_SERVER_CSRF_TOKEN || "" },
                        success: function () {
                            vm.$q.notify({ type: 'positive', message: 'Linked decision node to target artifact successfully.' });
                        }
                    });
                });
            },

            toggleNodeStatus(node) {
                var vm = this;
                var newStatus = node.isCompleted ? 'WeComplete' : 'WeInProgress';
                $.ajax({
                    type: 'POST',
                    url: '/rest/s1/agi-ide/blueprint/update-status',
                    data: { workEffortId: node.workEffortId, statusId: newStatus },
                    dataType: 'json',
                    headers: { 'moquiSessionToken': window.AGI_SERVER_CSRF_TOKEN || "" },
                    success: function () {
                        vm.$emit('tree-updated');
                    }
                });
            },

            handleDragStart(e, node) {
                e.dataTransfer.effectAllowed = 'copy';
                e.dataTransfer.setData('text/plain', JSON.stringify(node));
                this.copiedNodeData = node;
            },
            handleDragOver(e) {
                e.dataTransfer.dropEffect = 'copy';
            },
            handleDropAndPaste(e, targetNode) {
                var rawData = e.dataTransfer.getData('text/plain');
                if (!rawData) return;
                var sourceNode = JSON.parse(rawData);

                if (sourceNode.workEffortId === targetNode.workEffortId) return;

                var vm = this;
                $.ajax({
                    type: 'POST',
                    url: '/rest/s1/agi-ide/blueprint/create-node',
                    data: {
                        parentWorkEffortId: targetNode.workEffortId,
                        workEffortName: sourceNode.workEffortName + ' (Moved)',
                        agiArtifactId: vm.agiArtifactId,
                        sourceReferenceId: vm.sourceReferenceId,
                        workEffortTypeEnumId: sourceNode.workEffortTypeEnumId
                    },
                    dataType: 'json',
                    headers: { 'moquiSessionToken': window.AGI_SERVER_CSRF_TOKEN || "" },
                    success: function () {
                        vm.fetchTree();
                    }
                });
            },

            copyNodeJson(node) {
                this.copiedNodeData = node;
                this.$q.notify({ type: 'info', message: 'Copied node: ' + node.workEffortName });
            },
            pasteCopiedNode(targetNode) {
                if (!this.copiedNodeData) {
                    this.$q.notify({ type: 'warning', message: 'No node data copied in clipboard.' });
                    return;
                }
                var vm = this;
                $.ajax({
                    type: 'POST',
                    url: '/rest/s1/agi-ide/blueprint/create-node',
                    data: {
                        parentWorkEffortId: targetNode.workEffortId,
                        workEffortName: vm.copiedNodeData.workEffortName + ' (Copy)',
                        workEffortTypeEnumId: vm.copiedNodeData.workEffortTypeEnumId,
                        agiArtifactId: vm.agiArtifactId,
                        sourceReferenceId: vm.sourceReferenceId
                    },
                    dataType: 'json',
                    headers: { 'moquiSessionToken': window.AGI_SERVER_CSRF_TOKEN || "" },
                    success: function () {
                        vm.fetchTree();
                    }
                });
            },

            deleteNode(node) {
                var vm = this;
                this.$q.dialog({
                    title: 'Confirm Cancellation',
                    message: 'Are you sure you want to cancel/delete: ' + node.workEffortName + '?',
                    cancel: true
                }).onOk(function () {
                    $.ajax({
                        type: 'POST',
                        url: '/rest/s1/agi-ide/blueprint/update-status',
                        data: { workEffortId: node.workEffortId, statusId: 'WeCancelled' },
                        dataType: 'json',
                        headers: { 'moquiSessionToken': window.AGI_SERVER_CSRF_TOKEN || "" },
                        success: function () {
                            vm.fetchTree();
                        }
                    });
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