import { flushPromises, shallowMount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  api: Object.fromEntries(['getModels', 'analyzeText', 'extractKeywords', 'summarizeText', 'getSegmentStrategies', 'getDocumentSegments', 'indexWithSegment'].map(key => [key, vi.fn()])),
  doc: Object.fromEntries(['getDocDetail', 'updateDoc', 'getIndexState', 'buildIndex', 'retryIndex', 'retryParse', 'restoreVersion', 'getUserDocs', 'searchDocs'].map(key => [key, vi.fn()])),
  confirm: vi.fn(),
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
vi.mock('element-plus', () => ({ ElMessage: { success: mocks.success, error: mocks.error, warning: vi.fn(), info: vi.fn() }, ElMessageBox: { confirm: mocks.confirm } }))
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
const fragmentResponse = (segments) => ({ data: { indexState: 'SEGMENTS_AVAILABLE', documentVersion: 1, generationId: 'generation-1', segments } })
const notIndexedState = () => mocks.doc.getIndexState.mockResolvedValue({ data: { documentId: 'baseline-document', currentVersion: 1, indexedVersion: null, indexState: 'NOT_INDEXED', queryable: false, activeGenerationId: null } })
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
  mocks.doc.getDocDetail.mockResolvedValue({ data: { title: 'Baseline', content: source, summary: '已存摘要', version: 1, parseState: 'READY', snapshotState: 'VERIFIED', contentFormat: 'HTML' } })
  mocks.doc.updateDoc.mockResolvedValue({ data: { version: 2, parseState: 'READY', snapshotState: 'VERIFIED' } })
  mocks.doc.buildIndex.mockResolvedValue({ code: 200 })
  mocks.doc.retryIndex.mockResolvedValue({ code: 200 })
  mocks.doc.getIndexState.mockResolvedValue({ data: { documentId: 'baseline-document', currentVersion: 1, indexedVersion: 1, indexState: 'READY', queryable: true, activeGenerationId: 'generation-1', retryable: false } })
  mocks.api.getModels.mockResolvedValue({ data: [{ code: 'test-model', name: 'Test', available: true }] })
  mocks.api.analyzeText.mockResolvedValue({ data: stats })
  mocks.api.extractKeywords.mockResolvedValue({ data: { keywords: [{ word: '真实关键词' }] } })
  mocks.api.summarizeText.mockResolvedValue({ data: { summary: '真实摘要' } })
  mocks.api.getSegmentStrategies.mockResolvedValue({ data: { CHAPTER: '按章节分段' } })
  mocks.api.getDocumentSegments.mockResolvedValue(fragmentResponse([{ content: '当前可信分段' }]))
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
    mocks.doc.buildIndex.mockRejectedValueOnce(new Error('index unavailable'))
    await vm.doIndexDocument()
    expect(vm.segments).toEqual([])
    expect(vm.indexSucceeded).toBe(false)
    expect(mocks.success).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('无法确认是否完成')
    expect(wrapper.find('.paper').html()).toContain('第二段')
    mocks.api.getDocumentSegments.mockResolvedValue(fragmentResponse([{ content: '后端真实片段' }]))
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
    mocks.api.getDocumentSegments.mockResolvedValue(fragmentResponse([{ content: 'recovered' }]))
    await vm.loadDocumentSegments()
    expect(vm.segmentsError).toBe('')
    expect(mocks.doc.buildIndex).toHaveBeenCalledTimes(1)
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

  it('preserves rendered paragraph boundaries for analysis but indexes only the saved version', async () => {
    const vm = await mountView()
    const visibleText = '第一段，包含完整的文档内容。\n\n第二段，需要保留段落边界。'
    Object.defineProperty(wrapper.find('.paper').element, 'innerText', { configurable: true, value: visibleText })
    await vm.openRagSettings(); await vm.doIndexDocument()
    expect(vm.currentDocumentText()).toBe(visibleText)
    expect(mocks.doc.buildIndex).toHaveBeenCalledWith('baseline-document', 1, 'CHAPTER')
    expect(mocks.api.indexWithSegment).not.toHaveBeenCalled()
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
    notIndexedState()
    mocks.api.getDocumentSegments.mockResolvedValue({ data: { indexState: 'NOT_INDEXED', segments: [] } })
    const vm = await mountView()
    await vm.openRagSettings(); await flushPromises()
    expect(wrapper.text()).toContain('当前没有可用分段')
    expect(vm.segmentsIndexState).toBe('NOT_INDEXED')
    expect(vm.segmentsError).toBe('')
    expect(vm.segments).toEqual([])
    expect(mocks.success).not.toHaveBeenCalled()
    expect(mocks.api.indexWithSegment).not.toHaveBeenCalled()
    expect(wrapper.find('.paper').html()).toContain('第二段')
  })

  it('displays indexed fragments and clears the former not-indexed state on reload', async () => {
    notIndexedState()
    mocks.api.getDocumentSegments.mockResolvedValueOnce({ data: { indexState: 'NOT_INDEXED', segments: [] } })
    const vm = await mountView()
    await vm.openRagSettings()
    const fragments = [{ segmentId: 'segment-1', content: '已索引的真实片段' }]
    mocks.doc.getIndexState.mockResolvedValue({ data: { documentId: 'baseline-document', currentVersion: 1, indexedVersion: 1, indexState: 'READY', queryable: true, activeGenerationId: 'generation-1' } })
    mocks.api.getDocumentSegments.mockResolvedValueOnce(fragmentResponse(fragments))
    await vm.loadDocumentSegments(); await flushPromises()
    expect(vm.segments).toEqual(fragments)
    expect(wrapper.text()).toContain('已索引的真实片段')
    expect(wrapper.text()).not.toContain('尚未索引')
    expect(vm.segmentsIndexState).toBe('SEGMENTS_AVAILABLE')
  })

  it('does not infer a verified version or not-indexed from a legacy empty-array response', async () => {
    mocks.api.getDocumentSegments.mockResolvedValue({ data: [] })
    const vm = await mountView()
    await vm.openRagSettings(); await flushPromises()
    expect(vm.segmentsIndexState).toBe(null)
    expect(wrapper.text()).not.toContain('尚未索引')
    expect(wrapper.text()).toContain('分段明细加载失败，当前结果未知')
  })

  it.each([
    [401, '登录状态已失效'],
    [403, '无权读取该索引'],
    [404, '文档不存在或无权访问'],
    [409, '分段数据不完整或暂不可读'],
    [503, '分段读取服务暂不可用'],
  ])('keeps HTTP %s as a failure instead of not-indexed or stale fragments', async (status, message) => {
    mocks.api.getDocumentSegments.mockResolvedValueOnce(fragmentResponse([{ content: '过期片段' }]))
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
    notIndexedState()
    mocks.api.getDocumentSegments.mockRejectedValueOnce({ response: { status: 503 } })
    const vm = await mountView()
    await vm.openRagSettings()
    mocks.api.getDocumentSegments.mockResolvedValueOnce({ data: { indexState: 'NOT_INDEXED', segments: [] } })
    await wrapper.findAll('button').find(button => button.text() === '重试加载分段').trigger('click')
    await flushPromises()
    expect(vm.segmentsError).toBe('')
    expect(wrapper.text()).toContain('当前没有可用分段')
    expect(mocks.api.indexWithSegment).not.toHaveBeenCalled()
  })

  it('saves a dirty draft with CAS before requesting an index of the returned version', async () => {
    const vm = await mountView()
    await vm.openRagSettings()
    wrapper.find('.paper').element.innerHTML = '<p>已修改的完整内容</p>'
    await wrapper.find('.paper').trigger('input')
    await vm.doIndexDocument()
    expect(mocks.doc.updateDoc).toHaveBeenCalledWith('baseline-document', expect.objectContaining({ expectedVersion: 1, contentFormat: 'HTML', content: '<p>已修改的完整内容</p>' }))
    expect(mocks.doc.buildIndex).toHaveBeenCalledWith('baseline-document', 2, 'CHAPTER')
    expect(mocks.doc.updateDoc.mock.invocationCallOrder[0]).toBeLessThan(mocks.doc.buildIndex.mock.invocationCallOrder[0])
    expect(mocks.api.indexWithSegment).not.toHaveBeenCalled()
  })

  it('retains edits made during saving and does not index the older submitted draft', async () => {
    let finishSave
    mocks.doc.updateDoc.mockImplementation(() => new Promise(resolve => { finishSave = resolve }))
    const vm = await mountView()
    await vm.openRagSettings()
    wrapper.find('.paper').element.innerHTML = '<p>第一份修改</p>'
    await wrapper.find('.paper').trigger('input')
    const indexing = vm.doIndexDocument()
    await flushPromises()
    wrapper.find('.paper').element.innerHTML = '<p>保存期间的新修改</p>'
    await wrapper.find('.paper').trigger('input')
    finishSave({ data: { version: 2 } })
    await indexing; await flushPromises()
    expect(vm.savedVersion).toBe(2)
    expect(vm.isDirty).toBe(true)
    expect(wrapper.find('.paper').text()).toBe('保存期间的新修改')
    expect(vm.saveStatusText).toContain('仍有修改未保存')
    expect(mocks.doc.buildIndex).not.toHaveBeenCalled()
    expect(vm.indexQueryable).toBe(false)
  })

  it('preserves a conflicted draft and never indexes after a failed save', async () => {
    mocks.doc.updateDoc.mockRejectedValue({ response: { status: 409 } })
    const vm = await mountView()
    await vm.openRagSettings()
    wrapper.find('.paper').element.innerHTML = '<p>版本冲突时保留的草稿</p>'
    await wrapper.find('.paper').trigger('input')
    await vm.doIndexDocument()
    expect(vm.savedVersion).toBe(1)
    expect(vm.isDirty).toBe(true)
    expect(vm.saveStatusText).toContain('版本冲突')
    expect(wrapper.find('.paper').text()).toContain('保留的草稿')
    expect(mocks.doc.buildIndex).not.toHaveBeenCalled()
  })

  it('restores with expectedVersion and invalidates old ready/fragments', async () => {
    mocks.confirm.mockResolvedValue('confirm')
    mocks.doc.restoreVersion.mockResolvedValue({ data: { version: 2 } })
    const vm = await mountView()
    await vm.openRagSettings()
    mocks.doc.getDocDetail.mockResolvedValue({ data: { title: 'Restored', content: '<p>历史版本正文</p>', version: 2, snapshotState: 'VERIFIED', parseState: 'READY' } })
    mocks.doc.getIndexState.mockResolvedValue({ data: { documentId: 'baseline-document', currentVersion: 2, indexedVersion: null, indexState: 'FAILED', queryable: false, activeGenerationId: null, retryable: true } })
    await vm.handleRestore(1); await flushPromises()
    expect(mocks.doc.restoreVersion).toHaveBeenCalledWith('baseline-document', 1, 1)
    expect(vm.savedVersion).toBe(2)
    expect(vm.indexQueryable).toBe(false)
    expect(vm.segments).toEqual([])
    await vm.retryDocumentIndex()
    expect(mocks.doc.retryIndex).toHaveBeenCalledWith('baseline-document', 2)
  })

  it('does not overwrite a local draft with collaboration polling, but accepts a clean newer version', async () => {
    const vm = await mountView()
    wrapper.find('.paper').element.innerHTML = '<p>本地尚未保存</p>'
    await wrapper.find('.paper').trigger('input')
    expect(vm.acceptRemoteDocument({ version: 2, content: '<p>远端正文</p>' })).toBe(false)
    expect(wrapper.find('.paper').text()).toBe('本地尚未保存')
    wrapper.find('.paper').element.innerHTML = source
    await wrapper.find('.paper').trigger('input')
    expect(vm.acceptRemoteDocument({ version: 2, content: '<p>远端正文</p>' })).toBe(true)
    await flushPromises()
    expect(vm.savedVersion).toBe(2)
    expect(wrapper.find('.paper').text()).toBe('远端正文')
    expect(vm.indexQueryable).toBe(false)
  })

  it('never derives dashboard index readiness from a stored summary', async () => {
    localStorage.setItem('userId', 'baseline-user')
    mocks.doc.getUserDocs.mockResolvedValue({ data: [{ id: 'baseline-document', title: 'Example', summary: '摘要存在' }] })
    mocks.doc.getIndexState.mockResolvedValue({ data: { documentId: 'baseline-document', currentVersion: 2, indexedVersion: 1, indexState: 'FAILED', queryable: false, activeGenerationId: null } })
    const vm = await mountView(DashboardView)
    expect(vm.docList[0].queryable).toBe(false)
    expect(vm.docList[0].indexLabel).toContain('更新失败')
    expect(wrapper.text()).not.toContain('AI已就绪')
  })

  it.each([
    { documentVersion: 2, generationId: 'generation-1' },
    { documentVersion: 1, generationId: 'wrong-generation' },
    {},
  ])('rejects segments whose version/generation cannot match the current published index: %j', async binding => {
    mocks.api.getDocumentSegments.mockResolvedValue({ data: { indexState: 'SEGMENTS_AVAILABLE', segments: [{ content: '不可确认的旧片段' }], ...binding } })
    const vm = await mountView(); await vm.openRagSettings()
    expect(vm.segments).toEqual([])
    expect(vm.segmentsError).toContain('当前结果未知')
    expect(wrapper.text()).not.toContain('不可确认的旧片段')
  })

  it('renders declared plain text without stripping markdown, currency or interpreting HTML', async () => {
    mocks.doc.getDocDetail.mockResolvedValue({ data: { title: 'Plain', content: '# Heading\n$100 <img src=x>\n**literal** 😀', version: 1, contentFormat: 'PLAIN_TEXT', snapshotState: 'VERIFIED', parseState: 'READY' } })
    const vm = await mountView()
    expect(wrapper.find('.paper').text()).toContain('# Heading')
    expect(wrapper.find('.paper').text()).toContain('$100 <img src=x>')
    expect(wrapper.find('.paper').text()).toContain('**literal** 😀')
    expect(wrapper.find('.paper img').exists()).toBe(false)
    expect(vm.isDirty).toBe(false)
  })

  it('requires an explicit save to create a trusted legacy snapshot before indexing', async () => {
    mocks.doc.getDocDetail.mockResolvedValue({ data: { title: 'Legacy', content: source, version: 1, snapshotState: 'MIGRATION_REQUIRED', parseState: 'UNKNOWN' } })
    const vm = await mountView(); await vm.openRagSettings(); await vm.doIndexDocument()
    expect(mocks.doc.updateDoc).toHaveBeenCalledWith('baseline-document', expect.objectContaining({ expectedVersion: 1, contentFormat: 'HTML' }))
    expect(mocks.doc.buildIndex).toHaveBeenCalledWith('baseline-document', 2, 'CHAPTER')
  })

  it('keeps parse failure separate from empty content and retries only without unsaved edits', async () => {
    mocks.doc.getDocDetail.mockResolvedValue({ data: { title: 'Failed', content: '', version: 1, snapshotState: 'PARSE_FAILED', parseState: 'FAILED' } })
    mocks.doc.getIndexState.mockResolvedValue({ data: { documentId: 'baseline-document', currentVersion: 1, indexedVersion: null, indexState: 'FAILED', queryable: false, activeGenerationId: null, errorCode: 'PARSE_FAILED', retryable: false } })
    const vm = await mountView(); await vm.openRagSettings()
    expect(vm.indexLabel).toContain('解析失败')
    await vm.doIndexDocument(); expect(mocks.doc.buildIndex).not.toHaveBeenCalled()
    wrapper.find('.paper').element.innerHTML = '<p>手工恢复草稿</p>'; await wrapper.find('.paper').trigger('input')
    await vm.retryDocumentParse(); expect(mocks.doc.retryParse).not.toHaveBeenCalled()
    wrapper.find('.paper').element.innerHTML = ''; await wrapper.find('.paper').trigger('input')
    mocks.doc.retryParse.mockRejectedValue({ response: { status: 503 } })
    await vm.retryDocumentParse()
    expect(mocks.doc.retryParse).toHaveBeenCalledWith('baseline-document', 1)
    expect(vm.parseState).toBe('FAILED')
  })
})
