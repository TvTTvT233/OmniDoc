import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
const mocks = vi.hoisted(() => ({ read: vi.fn() }))
vi.mock('../../api/document', () => ({ docApi: { getReference: mocks.read } }))
import DocumentReferences from './DocumentReferences.vue'
import { normalizeConversationMessage } from '../../api/conversationContract'
import { toConversationChatMessage } from '../../composables/useConversationWorkspace'

const reference = { referenceId: 'ref-one', documentId: 'doc-one', documentVersion: 1, documentTitle: '样例文档', segmentId: 'seg-one', generationId: 'gen-one', contentHash: 'hash-content', startOffset: 0, endOffset: 12, segmentHash: 'hash-segment' }
const snapshot = { ...reference, currentVersion: 2, status: 'HISTORICAL', content: '<script>历史片段</script>\n第二行' }
let wrapper
beforeEach(() => { vi.resetAllMocks(); mocks.read.mockResolvedValue({ data: snapshot }) })
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })
const render = () => { wrapper = mount(DocumentReferences, { props: { references: [reference] }, attachTo: document.body }); return wrapper }
describe('structured document references (mocked HTTP, actual reference component)', () => {
  it('renders the exact historical snapshot inside its panel as text, without source HTML', async () => {
    document.body.innerHTML = '<div class="paper">历史片段</div>'
    render()
    await wrapper.find('.reference-link').trigger('click'); await flushPromises()
    const panel = wrapper.get('[data-testid="reference-panel"]')
    expect(panel.get('[data-testid="reference-content"]').text()).toBe(snapshot.content)
    expect(panel.find('script').exists()).toBe(false)
    expect(panel.text()).toContain('历史版本引用（当前文档为 V2）')
    expect(mocks.read).toHaveBeenCalledWith('doc-one', 'ref-one')
  })
  it('identifies current-version references only from a consistent server response', async () => {
    mocks.read.mockResolvedValue({ data: { ...snapshot, currentVersion: 1, status: 'CURRENT' } })
    render(); await wrapper.find('.reference-link').trigger('click'); await flushPromises()
    expect(wrapper.get('[data-testid="reference-version-status"]').text()).toBe('当前版本引用')
  })
  it.each([403, 404, 410, 503])('clears previously read content on HTTP %s and does not fall back to current text', async status => {
    render(); await wrapper.find('.reference-link').trigger('click'); await flushPromises()
    mocks.read.mockRejectedValue({ response: { status } })
    await wrapper.find('.reference-link').trigger('click'); await flushPromises()
    expect(wrapper.get('[data-testid="reference-panel"]').find('[data-testid="reference-content"]').exists()).toBe(false)
    expect(wrapper.get('[role="alert"]').exists()).toBe(true)
  })
  it('rejects mismatched version/hash/status snapshots instead of displaying them', async () => {
    mocks.read.mockResolvedValue({ data: { ...snapshot, segmentHash: 'wrong' } })
    render(); await wrapper.find('.reference-link').trigger('click'); await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('引用读取失败')
    expect(wrapper.find('[data-testid="reference-content"]').exists()).toBe(false)
  })
  it('does not reopen closed panels when a slow request completes', async () => {
    let finish
    mocks.read.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    render(); await wrapper.find('.reference-link').trigger('click')
    await wrapper.find('.reference-close').trigger('click')
    finish({ data: snapshot }); await flushPromises()
    expect(wrapper.find('[data-testid="reference-panel"]').exists()).toBe(false)
  })
  it('preserves structured references through public history normalization and chat mapping', () => {
    const message = { messageId: 'message-1', conversationId: 'conversation-1', sequence: 1, role: 'ASSISTANT', messageType: 'TEXT', content: '回答正文', createdAt: '2026-10-21T00:00:00Z', references: [reference], metadata: { secret: 'not public' } }
    const normalized = normalizeConversationMessage(message)
    expect(toConversationChatMessage(normalized).references).toEqual([reference])
    expect(normalized.metadata).toEqual({})
    expect(normalizeConversationMessage({ ...message, references: undefined }).references).toEqual([])
    expect(normalizeConversationMessage({ ...message, references: [{ referenceId: 'model-invented' }] }).references).toEqual([])
  })
})
