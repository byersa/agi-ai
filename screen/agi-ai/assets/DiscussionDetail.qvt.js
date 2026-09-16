(function () {
    const DiscussionDetail = {
        name: 'DiscussionDetail',
        emits: ['message-posted', 'turn-dispatched', 'thread-selected'],
        props: {
            discussionIdProp: { type: String, default: '' },
            node: { type: Object, default: () => null },
            enableAiDispatch: { type: Boolean, default: true },
            allowMarkdown: { type: Boolean, default: true }
        },
        data() {
            return {
                discussionId: this.discussionIdProp || this.node?.discussionId || '',
                activeNode: this.node || null,
                discussion: null,
                messages: [],
                containerFacets: {},
                newInput: '',
                isSending: false
            };
        },
        watch: {
            discussionIdProp(val) {
                this.discussionId = val;
                if (val) this.loadTranscript();
            },
            node: {
                deep: true,
                handler(val) {
                    this.activeNode = val;
                    if (val?.discussionId && val.discussionId !== this.discussionId) {
                        this.discussionId = val.discussionId;
                    }
                    this.loadTranscript();
                }
            }
        },
        mounted() {
            if (!window.showdown && this.allowMarkdown) {
                const s = document.createElement('script');
                s.src = 'https://cdnjs.cloudflare.com/ajax/libs/showdown/2.1.0/showdown.min.js';
                s.onload = () => { this.$forceUpdate(); };
                document.head.appendChild(s);
            }
            if (this.discussionId) this.loadTranscript();
        },
        methods: {
            resolveCsrf() {
                return window.AGI_SERVER_CSRF_TOKEN || (window.moqui && window.moqui.moquiSessionToken) || "";
            },
            async loadTranscript() {
                if (!this.discussionId) return;
                try {
                    const resp = await axios.get('/rest/s1/agi-ai/discussions/transcript', {
                        params: { discussionId: this.discussionId },
                        headers: { 'moquiSessionToken': this.resolveCsrf() }
                    });
                    this.discussion = resp.data?.discussion || null;
                    this.messages = resp.data?.messages || [];
                    this.containerFacets = resp.data?.containerFacets || {};
                } catch (e) {
                    console.error("Failed to load transcript:", e);
                }
            },
            formatMessage(raw) {
                if (!raw) return '';
                let text = String(raw).trim();
                if (text.startsWith('{') && text.endsWith('}')) {
                    try {
                        const parsed = JSON.parse(text);
                        text = parsed.message || parsed.content || text;
                    } catch (e) { }
                }
                if (this.allowMarkdown && window.showdown) {
                    if (!this._conv) {
                        this._conv = new window.showdown.Converter({ tables: true, strikethrough: true, tasklists: true });
                        this._conv.setFlavor('github');
                    }
                    return this._conv.makeHtml(text);
                }
                return text.replace(/\n/g, '<br/>');
            },
            async postMessage() {
                if (!this.newInput.trim() || !this.discussionId) return;
                const text = this.newInput.trim();
                this.newInput = '';
                this.isSending = true;

                try {
                    const resp = await axios.post('/rest/s1/agi-ai/discussions/message', {
                        discussionId: this.discussionId,
                        parentMessageId: this.activeNode?.messageId || null,
                        content: text
                    }, { headers: { 'moquiSessionToken': this.resolveCsrf() } });

                    const userMsgId = resp.data?.messageId;
                    this.$emit('message-posted', resp.data);
                    await this.loadTranscript();

                    // Optional AI agent turn dispatch
                    if (this.enableAiDispatch) {
                        await axios.post('/rest/s1/agi-ai/discussions/dispatch', {
                            discussionId: this.discussionId,
                            parentMessageId: userMsgId,
                            userPrompt: text
                        }, { headers: { 'moquiSessionToken': this.resolveCsrf() } });
                        this.$emit('turn-dispatched', { discussionId: this.discussionId, userMsgId });
                        await this.loadTranscript();
                    }
                    this.isSending = false;
                } catch (e) {
                    this.isSending = false;
                    this.$q.notify({ type: 'negative', message: 'Failed to post: ' + e.message });
                }
            }
        },
        template: `
            <div class="fit column no-wrap bg-slate-950 text-white font-sans overflow-hidden">
                <!-- Discussion Header Bar -->
                <div class="row items-center justify-between q-pa-sm bg-slate-900" style="border-bottom: 1px solid #334155;">
                    <div class="row items-center q-gutter-x-sm">
                        <q-icon name="forum" color="primary" size="sm" />
                        <span class="text-subtitle2 text-weight-bold text-slate-100">{{ discussion?.name || 'Discussion' }}</span>
                    </div>
                    <q-btn flat round dense icon="refresh" size="xs" color="slate-400" @click="loadTranscript" />
                </div>

                <!-- Scrollable Conversational Thread -->
                <div class="col overflow-y-auto q-pa-md column q-gutter-y-sm">
                    <div v-if="messages.length === 0" class="text-slate-500 italic text-caption text-center q-my-xl">
                        No messages yet. Post below to start the conversation.
                    </div>
                    <div 
                        v-for="msg in messages" 
                        :key="msg.messageId"
                        class="column q-pa-sm rounded-borders"
                        :style="msg.role === 'assistant' 
                            ? 'background-color: #0f172a; border-left: 4px solid #38bdf8;' 
                            : 'background-color: #1e293b; border-left: 4px solid #64748b;'"
                    >
                        <div class="row items-center justify-between text-caption q-mb-xs">
                            <span class="text-weight-bold" :class="msg.role === 'assistant' ? 'text-cyan-3' : 'text-slate-200'">
                                {{ msg.role === 'assistant' ? '🤖 Assistant' : (msg.partyId || 'Participant') }}
                            </span>
                            <span class="text-slate-500" style="font-size: 11px;">{{ msg.entryDate }}</span>
                        </div>
                        <div class="text-slate-100 text-body2" v-html="formatMessage(msg.content)"></div>
                    </div>
                </div>

                <!-- Input Console -->
                <div class="q-pa-sm bg-slate-900" style="border-top: 1px solid #334155;">
                    <div class="row items-center q-gutter-x-sm">
                        <q-input 
                            v-model="newInput" 
                            type="textarea" 
                            rows="2" 
                            dark outlined dense 
                            class="col text-caption"
                            input-style="color: #f1f5f9; background-color: #020617;"
                            placeholder="Type a message..."
                            :disable="isSending || !discussionId"
                            @keydown.ctrl.enter="postMessage"
                        />
                        <q-btn color="primary" icon="send" label="Send" dense no-caps class="q-px-md" style="height: 48px;" :loading="isSending" @click="postMessage" />
                    </div>
                </div>
            </div>
        `
    };

    window.DiscussionDetail = DiscussionDetail;
    if (!window.AgiComponents) window.AgiComponents = {};
    window.AgiComponents['discussion-detail'] = DiscussionDetail;

    const registerComp = () => {
        if (window.moqui && window.moqui.webrootVueApp) {
            window.moqui.webrootVueApp.component('discussion-detail', DiscussionDetail);
        } else {
            setTimeout(registerComp, 50);
        }
    };
    registerComp();
})();