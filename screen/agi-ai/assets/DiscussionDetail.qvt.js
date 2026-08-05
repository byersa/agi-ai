(function () {
    const DiscussionDetail = {
        name: 'DiscussionDetail',
        template: `
            <div class="discussion-detail-container q-pa-sm border-dashed rounded-borders bg-grey-1">
                <div class="row items-center justify-between q-mb-xs q-px-xs">
                    <span class="text-caption text-weight-bold text-grey-7 font-mono">
                        SPECIFICATION DETAIL [{{ node?.workEffortId || 'NEW' }}]
                    </span>
                </div>

                <!-- 🎯 Slot Host: Renders custom inline Vue components directly -->
                <slot :node="node">
                    <!-- Default Fallback: External Vue Component Reference -->
                    <component 
                        v-if="component" 
                        :is="component" 
                        :node="node" 
                    />

                    <!-- Default Fallback: Moqui XML Host -->
                    <moqui-xml-host 
                        v-else-if="xml" 
                        :xml="xml" 
                        :context="node" 
                    />

                    <!-- Ultimate Fallback: Registered AgiWorkEffortDetail Component -->
                    <agi-work-effort-detail 
                        v-else 
                        :node="node" 
                    />
                </slot>
            </div>
        `,
        props: {
            node: { type: Object, required: true },
            component: { type: String, default: null },
            xml: { type: String, default: null }
        }
    };

    window.DiscussionDetail = DiscussionDetail;
    window.AgiComponents = window.AgiComponents || {};
    window.AgiComponents['discussion-detail'] = DiscussionDetail;

    const registerDiscussionDetail = () => {
        if (window.moqui && window.moqui.webrootVueApp) {
            window.moqui.webrootVueApp.component('discussion-detail', DiscussionDetail);
            window.moqui.webrootVueApp.component('DiscussionDetail', DiscussionDetail);
        } else {
            setTimeout(registerDiscussionDetail, 50);
        }
    };
    registerDiscussionDetail();
})();