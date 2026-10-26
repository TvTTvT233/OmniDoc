import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
const mocks = vi.hoisted(() => ({ route: { params: { id: 'chat-mode' }, path: '/editor/chat-mode', query: {} }, messages: [], reference: null, read: vi.fn(), task: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => mocks.route, useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }))
vi.mock('../../utils/jwt.js', () => ({ getAuthenticatedUserId: () => 'user', isAdmin: () => false }))
vi.mock('../../utils/request', () => ({ default: {} }))
vi.mock('../../api/document', () => ({ docApi: { getReference: mocks.read, getDocDetail: async () => ({ data: { title: 'Document', version: 2, content: '<p>reference body</p>', snapshotState: 'VERIFIED' } }), getIndexState: async () => ({ data: { documentId: 'doc-one', currentVersion: 2, indexedVersion: null, indexState: 'FAILED', queryable: false, activeGenerationId: null } }) } }))
vi.mock('../../api/ai', () => ({ aiApi: { getModels: async () => ({ data: [] }), analyzeText: async () => ({ data: {} }), extractKeywords: async () => ({ data: [] }), summarizeText: async () => ({ data: { summary: 'summary' } }) } }))
vi.mock('../../api/user', () => ({ userApi: { getUserInfo: async () => ({ data: { username: 'User' } }) } }))
vi.mock('../../api/file', () => ({ fileApi: {} }))
vi.mock('../../api/agent', () => ({ agentApi: { getOverview: async () => ({ data: { tools: [], tasks: [] } }), getTaskHistory: mocks.task } }))
vi.mock('../../collaboration/CollaboratePanel.vue', () => ({ default: { template: '<div />' } }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), error: vi.fn() }, ElMessageBox: {} }))
vi.mock('../../composables/useConversationWorkspace.js', async () => {
  const { ref } = await import('vue')
  const actual = await vi.importActual('../../composables/useConversationWorkspace.js')
  return { toConversationChatMessage: actual.toConversationChatMessage, useConversationWorkspace: () => ({
    activeConversationId: ref('conv-one'), activeConversation: ref(null), activeConversations: ref([]), archivedConversations: ref([]), messages: ref(mocks.messages),
    activeHasMore: ref(false), archivedHasMore: ref(false), hasOlderMessages: ref(false), isLoadingConversations: ref(false), isLoadingMessages: ref(false),
    session: { draft: ref('') }, initialize: async () => {},
  }) }
})
import Editor from '../editor/EditorView.vue'
import GlobalAssistant from './GlobalAiAssistant.vue'
import Workbench from '../agent/AgentWorkbench.vue'
import { normalizeConversationMessage } from '../../api/conversationContract'
const refItem = { referenceId: 'reference-one', documentId: 'doc-one', documentVersion: 1, documentTitle: 'Source title', segmentId: 'seg-one', generationId: 'gen-one', contentHash: 'content-hash', startOffset: 0, endOffset: 14, segmentHash: 'seg-hash' }
let wrapper
beforeEach(() => {
  vi.clearAllMocks()
  mocks.messages = [normalizeConversationMessage({ messageId: 'msg-one', conversationId: 'conv-one', sequence: 1, role: 'ASSISTANT', messageType: 'TEXT', content: 'answer', createdAt: '2026-10-21', references: [refItem] })]
  mocks.read.mockResolvedValue({ data: { ...refItem, currentVersion: 2, status: 'HISTORICAL', content: 'reference body' } })
  mocks.task.mockResolvedValue({ data: { answer: 'historical result', references: [refItem] } })
})
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })
const render = (component) => mount(component, { attachTo: document.body, global: {
  stubs: { ...Object.fromEntries(['el-drawer', 'el-scrollbar', 'el-card', 'el-row', 'el-col', 'el-icon', 'el-avatar', 'el-tooltip', 'el-tag', 'el-select', 'el-option', 'el-table', 'el-dropdown', 'el-dropdown-menu', 'el-dropdown-item'].map(name => [name, { template: '<div><slot /><slot name="header" /></div>' }])), 'el-table-column': { template: '<span />' } },
  directives: { loading: {} }, mocks: { $router: { push: vi.fn() } }, config: { warnHandler: () => {} },
} })
describe('all answer entrances share protected reference resolution', () => {
  it.each(['editor', 'chat-mode', 'global', 'workbench'])('renders references recovered from %s history, with scoped content assertions', async entrance => {
    mocks.route.params.id = entrance === 'editor' ? 'doc-one' : 'chat-mode'
    mocks.route.path = '/editor/' + mocks.route.params.id
    const component = entrance === 'global' ? GlobalAssistant : entrance === 'workbench' ? Workbench : Editor
    wrapper = render(component); await flushPromises()
    if (entrance === 'workbench') { await wrapper.vm.$.setupState.viewTaskDetail('trace-one'); await flushPromises() }
    expect(wrapper.findAll('.reference-link')).toHaveLength(1)
    await wrapper.get('.reference-link').trigger('click'); await flushPromises()
    expect(wrapper.get('[data-testid="reference-panel"]').get('[data-testid="reference-content"]').text()).toBe('reference body')
    expect(wrapper.get('[data-testid="reference-version-status"]').text()).toContain('历史版本')
    wrapper.unmount()
    wrapper = render(component); await flushPromises()
    if (entrance === 'workbench') { await wrapper.vm.$.setupState.viewTaskDetail('trace-one'); await flushPromises() }
    expect(wrapper.findAll('.reference-link')).toHaveLength(1)
  })
})
