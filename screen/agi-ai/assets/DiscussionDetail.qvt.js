(function () {
    const DiscussionDetail = {
        name: 'DiscussionDetail',
        template: `
            <div class="discussion-detail-container q-pa-sm border-dashed rounded-borders bg-grey-1">
                <!-- Header Bar with Collapsible Toggle -->
                <div class="row items-center justify-between q-mb-xs q-px-xs">
                    <span class="text-caption text-weight-bold text-grey-7 font-mono">
                        SPECIFICATION DETAIL [{{ node?.wikiPageId || 'DRAFT' }}]
                    </span>
                    <!-- 🎯 Toggle Collapse/Expand Button -->
                    <q-btn 
                        flat 
                        dense 
                        round 
                        size="xs" 
                        :icon="isCollapsed ? 'expand_more' : 'expand_less'" 
                        color="grey-7" 
                        @click="isCollapsed = !isCollapsed"
                    >
                        <q-tooltip>{{ isCollapsed ? 'Expand Detail' : 'Collapse Detail' }}</q-tooltip>
                    </q-btn>
                </div>

                <!-- Collapsible Container Body -->
                <q-slide-transition>
                    <div v-show="!isCollapsed">
                        <slot :node="node">
                            <agi-intent-detail :node="node" />
                        </slot>
                    </div>
                </q-slide-transition>
            </div>
        `,
        props: {
            node: { type: Object, required: true }
        },
        data() {
            return {
                isCollapsed: false
            };
        }
    };

    window.DiscussionDetail = DiscussionDetail;
    if (!window.AgiComponents) window.AgiComponents = {};
    window.AgiComponents['discussion-detail'] = DiscussionDetail;

    const registerComp = () => {
        if (window.moqui && window.moqui.webrootVueApp) {
            window.moqui.webrootVueApp.component('discussion-detail', DiscussionDetail);
            window.moqui.webrootVueApp.component('DiscussionDetail', DiscussionDetail);
        } else {
            setTimeout(registerComp, 50);
        }
    };
    registerComp();
})();