import { flushPromises, shallowMount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  api: Object.fromEntries(['getModels', 'analyzeText', 'extractKeywords', 'summarizeText', 'getSegmentStrategies', 'getDocumentSegments', 'indexWithSegment'].map(key => [key, vi.fn()])),
  doc: { getDocDetail: vi.fn(), updateDoc: vi.fn() },
  success: vi.fn(), error: vi.fn(),
}))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { id: 'baseline-document' }, query: {} }), useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }))
vi.mock('../../api/ai', () => ({ aiApi: mocks.api }))
vi.mock('../../api/document', () => ({ docApi: mocks.doc }))
vi.mock('../../api/agent', () => ({ agentApi: {} }))
vi.mock('../../api/file', () => ({ fileApi: {} }))
vi.mock('../../api/user', () => ({ userApi: { getUserInfo: async () => ({ data: { username: 'Baseline User' } }) } }))
vi.mock('../../utils/request', () => ({ default: {} }))
vi.mock('../../utils/jwt.js', () => ({ getAuthenticatedUserId: () => 'baseline-user', isAdmin: () => false }))
vi.mock('../../collaboration/CollaboratePanel.vue', () => ({ default: { template: '<div />' } }))
vi.mock('element-plus', () => ({ ElMessage: { success: mocks.success, error: mocks.error, warning: vi.fn(), info: vi.fn() }, ElMessageBox: { confirm: vi.fn() } }))
vi.mock('../../composables/useConversationWorkspace.js', async () => {
  const { ref } = await import('vue')
  return {
    toConversationChatMessage: value => value,
    useConversationWorkspace: () => ({
      activeConversationId: ref('baseline-conversation'), activeConversation: ref(null),
      activeConversations: ref([]), archivedConversations: ref([]), messages: ref([]),
      activeHasMore: ref(false), archivedHasMore: ref(false), hasOlderMessages: ref(false),
      isLoadingConversations: ref(false), isLoadingMessages: ref(false),
      session: { draft: ref('') }, initialize: async () => {}, setDraft: vi.fn(),
    }),
  }
})

import EditorView from './EditorView.vue'
import DashboardView from '../dashboard/IndexView.vue'

let wrapper
const source = '<p>第一段，包含完整的文档内容。</p><p>第二段，需要保留段落边界。</p>'
const stats = { totalCharacters: 31, lines: 2, chineseCharacters: 27, punctuations: 4 }
const mountView = async (component = EditorView) => {
  wrapper = shallowMount(component, {
    attachTo: document.body,
    global: {
      renderStubDefaultSlot: true,
      stubs: {
        'el-button': { props: ['disabled', 'loading'], template: '<button :disabled="disabled || loading"><slot /></button>' },
        'el-empty': { props: ['description'], template: '<p>{{ description }}</p>' },
      },
      directives: { loading: {} },
      mocks: { $router: { push: vi.fn() } },
      config: { warnHandler: () => {} },
    },
  })
  await flushPromises()
  return wrapper.vm.$.setupState
}

beforeEach(() => {
  vi.resetAllMocks()
  localStorage.clear(); sessionStorage.clear()
  mocks.doc.getDocDetail.mockResolvedValue({ data: { title: 'Baseline', content: source, summary: '已存摘要' } })
  mocks.api.getModels.mockResolvedValue({ data: [{ code: 'test-model', name: 'Test', available: true }] })
  mocks.api.analyzeText.mockResolvedValue({ data: stats })
  mocks.api.extractKeywords.mockResolvedValue({ data: { keywords: [{ word: '真实关键词' }] } })
  mocks.api.summarizeText.mockResolvedValue({ data: { summary: '真实摘要' } })
  mocks.api.getSegmentStrategies.mockResolvedValue({ data: { CHAPTER: '按章节分段' } })
  mocks.api.getDocumentSegments.mockResolvedValue({ data: [] })
  mocks.api.indexWithSegment.mockResolvedValue({ code: 200 })
})
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })

describe('editor baseline truthful states (mocked component tests)', () => {
  it('does not invent analysis on failure and recovers with real statistics on retry', async () => {
    mocks.api.analyzeText.mockRejectedValueOnce(new Error('unavailable'))
    const vm = await mountView()
    expect(wrapper.text()).toContain('文本分析失败')
    expect(wrapper.find('.stats-grid').exists()).toBe(false)
    expect(wrapper.find('.paper').html()).toContain('第一段')
    await vm.analyzeDocumentText(); await flushPromises()
    expect(vm.textStats).toEqual(stats)
    expect(wrapper.find('.stats-grid').exists()).toBe(true)
  })

  it('rejects malformed analysis rather than rendering guessed zero counts', async () => {
    mocks.api.analyzeText.mockResolvedValue({ data: {} })
    await mountView()
    expect(wrapper.text()).toContain('文本分析失败')
    expect(wrapper.find('.stats-grid').exists()).toBe(false)
  })

  it('shows keyword and summary errors without fabricated content, preserving a prior summary', async () => {
    mocks.api.extractKeywords.mockRejectedValue(new Error('unavailable'))
    mocks.api.summarizeText.mockRejectedValue(new Error('unavailable'))
    const vm = await mountView()
    expect(vm.keywords).toEqual([])
    expect(wrapper.text()).toContain('关键词提取失败')
    expect(wrapper.text()).not.toContain('办公规范')
    expect(wrapper.text()).toContain('摘要更新失败，下方保留上次结果')
    expect(vm.aiSummary).toBe('已存摘要')
  })

  it('does not invent fragments on index failure, and a retry uses backend fragments', async () => {
    const vm = await mountView()
    await vm.openRagSettings()
    mocks.api.indexWithSegment.mockRejectedValueOnce(new Error('index unavailable'))
    await vm.doIndexDocument()
    expect(vm.segments).toEqual([])
    expect(vm.indexSucceeded).toBe(false)
    expect(mocks.success).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('无法确认是否完成')
    expect(wrapper.find('.paper').html()).toContain('第二段')
    mocks.api.getDocumentSegments.mockResolvedValue({ data: [{ content: '后端真实片段' }] })
    await vm.doIndexDocument(); await flushPromises()
    expect(vm.segments).toEqual([{ content: '后端真实片段' }])
    expect(vm.indexError).toBe('')
    expect(vm.indexSucceeded).toBe(true)
  })

  it('distinguishes an acknowledged write from unavailable segment details', async () => {
    const vm = await mountView()
    await vm.openRagSettings()
    mocks.api.getDocumentSegments.mockRejectedValueOnce(new Error('read unavailable'))
    await vm.doIndexDocument(); await flushPromises()
    expect(vm.indexSucceeded).toBe(true)
    expect(vm.indexError).toBe('')
    expect(wrapper.text()).toContain('分段明细加载失败，当前结果未知')
    expect(vm.segments).toEqual([])
    mocks.api.getDocumentSegments.mockResolvedValue({ data: [{ content: 'recovered' }] })
    await vm.loadDocumentSegments()
    expect(vm.segmentsError).toBe('')
    expect(mocks.api.indexWithSegment).toHaveBeenCalledTimes(1)
  })

  it('does not invent strategies or available models when discovery fails', async () => {
    mocks.api.getModels.mockRejectedValue(new Error('unavailable'))
    mocks.api.getSegmentStrategies.mockRejectedValue(new Error('unavailable'))
    const vm = await mountView()
    await vm.openRagSettings()
    await vm.doIndexDocument()
    expect(vm.modelOptions).toEqual([])
    expect(vm.strategyMap).toEqual({})
    expect(vm.selectedStrategy).toBe('')
    expect(mocks.api.indexWithSegment).not.toHaveBeenCalled()
    expect(vm.modelsError).toContain('可用性未知')
  })

  it('keeps load errors outside the document and prevents writes until a successful retry', async () => {
    mocks.doc.getDocDetail.mockRejectedValueOnce(new Error('load unavailable'))
    const vm = await mountView()
    expect(wrapper.text()).toContain('读取文档失败')
    expect(wrapper.find('.paper').text()).toBe('')
    expect(wrapper.find('.paper').attributes('contenteditable')).toBe('false')
    await vm.handleManualSave(); await vm.doIndexDocument()
    expect(vm.applyFrontendDocumentWrite({ content: 'replacement' })).toBe(false)
    expect(mocks.doc.updateDoc).not.toHaveBeenCalled()
    expect(mocks.api.indexWithSegment).not.toHaveBeenCalled()
    await vm.loadDocData(); await flushPromises()
    expect(vm.canEditDocument).toBe(true)
    expect(wrapper.find('.paper').text()).toContain('第一段')
  })

  it('preserves an existing editor body when a later read fails', async () => {
    const vm = await mountView()
    wrapper.find('.paper').element.innerHTML = '<p>尚未保存的用户修改</p>'
    mocks.doc.getDocDetail.mockRejectedValueOnce(new Error('load unavailable'))
    await vm.loadDocData(); await flushPromises()
    expect(wrapper.find('.paper').text()).toBe('尚未保存的用户修改')
    await vm.handleManualSave()
    expect(mocks.doc.updateDoc).not.toHaveBeenCalled()
  })

  it('preserves rendered paragraph boundaries when indexing', async () => {
    const vm = await mountView()
    const visibleText = '第一段，包含完整的文档内容。\n\n第二段，需要保留段落边界。'
    Object.defineProperty(wrapper.find('.paper').element, 'innerText', { configurable: true, value: visibleText })
    await vm.openRagSettings(); await vm.doIndexDocument()
    expect(mocks.api.indexWithSegment).toHaveBeenCalledWith('baseline-document', visibleText, 'CHAPTER')
  })

  it('does not advertise fake available models in the dashboard', async () => {
    mocks.api.getModels.mockRejectedValue(new Error('unavailable'))
    const vm = await mountView(DashboardView)
    expect(vm.aiModelsList).toEqual([])
    expect(vm.modelsError).toContain('可用性未知')
    await vm.fetchModels()
    expect(vm.aiModelsList).toEqual([])
  })

  it('shows not indexed only when the backend explicitly confirms that state', async () => {
    mocks.api.getDocumentSegments.mockResolvedValue({ data: { indexState: 'NOT_INDEXED', segments: [] } })
    const vm = await mountView()
    await vm.openRagSettings(); await flushPromises()
    expect(wrapper.text()).toContain('当前文档尚未索引')
    expect(vm.segmentsIndexState).toBe('NOT_INDEXED')
    expect(vm.segmentsError).toBe('')
    expect(vm.segments).toEqual([])
    expect(mocks.success).not.toHaveBeenCalled()
    expect(mocks.api.indexWithSegment).not.toHaveBeenCalled()
    expect(wrapper.find('.paper').html()).toContain('第二段')
  })

  it('displays indexed fragments and clears the former not-indexed state on reload', async () => {
    mocks.api.getDocumentSegments.mockResolvedValueOnce({ data: { indexState: 'NOT_INDEXED', segments: [] } })
    const vm = await mountView()
    await vm.openRagSettings()
    const fragments = [{ segmentId: 'segment-1', content: '已索引的真实片段' }]
    mocks.api.getDocumentSegments.mockResolvedValueOnce({ data: { indexState: 'SEGMENTS_AVAILABLE', segments: fragments } })
    await vm.loadDocumentSegments(); await flushPromises()
    expect(vm.segments).toEqual(fragments)
    expect(wrapper.text()).toContain('已索引的真实片段')
    expect(wrapper.text()).not.toContain('尚未索引')
    expect(vm.segmentsIndexState).toBe('SEGMENTS_AVAILABLE')
  })

  it('does not infer not-indexed from the legacy empty-array response', async () => {
    const vm = await mountView()
    await vm.openRagSettings(); await flushPromises()
    expect(vm.segmentsIndexState).toBe('LEGACY')
    expect(wrapper.text()).not.toContain('尚未索引')
    expect(wrapper.text()).toContain('未返回分段，接口未提供索引状态')
  })

  it.each([
    [401, '登录状态已失效'],
    [403, '无权读取该索引'],
    [404, '文档不存在或无权访问'],
    [409, '分段数据不完整或暂不可读'],
    [503, '分段读取服务暂不可用'],
  ])('keeps HTTP %s as a failure instead of not-indexed or stale fragments', async (status, message) => {
    mocks.api.getDocumentSegments.mockResolvedValueOnce({ data: { indexState: 'SEGMENTS_AVAILABLE', segments: [{ content: '过期片段' }] } })
    const vm = await mountView()
    await vm.openRagSettings()
    mocks.api.getDocumentSegments.mockRejectedValueOnce({ response: { status, data: { code: status } } })
    await vm.loadDocumentSegments(); await flushPromises()
    expect(vm.segmentsError).toContain(message)
    expect(vm.segmentsIndexState).toBe(null)
    expect(vm.segments).toEqual([])
    expect(wrapper.text()).not.toContain('尚未索引')
    expect(wrapper.text()).not.toContain('过期片段')
    expect(wrapper.text()).toContain('重试加载分段')
  })

  it.each([
    {},
    { indexState: 'NOT_INDEXED', segments: [{ content: '矛盾内容' }] },
    { indexState: 'SEGMENTS_AVAILABLE', segments: [{ content: null }] },
    { indexState: 'SEGMENTS_AVAILABLE', segments: [] },
    { indexState: 'LEGACY', segments: [] },
  ])('rejects an invalid or contradictory state response without claiming not-indexed: %j', async (data) => {
    mocks.api.getDocumentSegments.mockResolvedValue({ data })
    const vm = await mountView()
    await vm.openRagSettings(); await flushPromises()
    expect(vm.segmentsError).toBe('分段明细加载失败，当前结果未知。')
    expect(vm.segmentsIndexState).toBe(null)
    expect(wrapper.text()).not.toContain('尚未索引')
  })

  it('recovers from unavailable storage to a confirmed not-indexed response on retry', async () => {
    mocks.api.getDocumentSegments.mockRejectedValueOnce({ response: { status: 503 } })
    const vm = await mountView()
    await vm.openRagSettings()
    mocks.api.getDocumentSegments.mockResolvedValueOnce({ data: { indexState: 'NOT_INDEXED', segments: [] } })
    await wrapper.findAll('button').find(button => button.text() === '重试加载分段').trigger('click')
    await flushPromises()
    expect(vm.segmentsError).toBe('')
    expect(wrapper.text()).toContain('当前文档尚未索引')
    expect(mocks.api.indexWithSegment).not.toHaveBeenCalled()
  })
})
