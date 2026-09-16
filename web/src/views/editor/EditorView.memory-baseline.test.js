import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  route: { path: '/editor/chat-mode', params: { id: 'chat-mode' }, query: {} },
  routerPush: vi.fn(),
  routerReplace: vi.fn(),
  createConversation: vi.fn(),
  listConversations: vi.fn(),
  getConversation: vi.fn(),
  listMessages: vi.fn(),
  archiveConversation: vi.fn(),
  restoreConversation: vi.fn(),
  deleteConversation: vi.fn(),
  getModels: vi.fn(),
  executeTask: vi.fn(),
  getUserInfo: vi.fn(),
  error: vi.fn(),
}))

vi.mock('vue-router', async () => {
  const actual = await vi.importActual('vue-router')
  return {
    ...actual,
    useRoute: () => mocks.route,
    useRouter: () => ({ push: mocks.routerPush, replace: mocks.routerReplace }),
  }
})

vi.mock('../../api/conversation.js', () => ({
  conversationApi: {
    createConversation: mocks.createConversation,
    listConversations: mocks.listConversations,
    getConversation: mocks.getConversation,
    listMessages: mocks.listMessages,
    archiveConversation: mocks.archiveConversation,
    restoreConversation: mocks.restoreConversation,
    deleteConversation: mocks.deleteConversation,
  },
}))

vi.mock('../../api/ai', () => ({ aiApi: { getModels: mocks.getModels } }))
vi.mock('../../api/agent', () => ({ agentApi: { executeTask: mocks.executeTask } }))
vi.mock('../../api/user', () => ({ userApi: { getUserInfo: mocks.getUserInfo } }))
vi.mock('../../api/file', () => ({ fileApi: {} }))
vi.mock('../../api/document', () => ({ docApi: {} }))
vi.mock('../../utils/request', () => ({ default: {} }))
vi.mock('../../collaboration/CollaboratePanel.vue', () => ({
  default: { template: '<div />' },
}))
vi.mock('element-plus', () => ({
  ElMessage: {
    success: vi.fn(),
    warning: vi.fn(),
    error: mocks.error,
    info: vi.fn(),
  },
  ElMessageBox: { confirm: vi.fn() },
}))

import EditorView from './EditorView.vue'

const conversation = (conversationId) => ({
  conversationId,
  title: conversationId,
  status: 'ACTIVE',
  createdAt: '2026-08-11T00:00:00Z',
  updatedAt: '2026-08-11T00:00:00Z',
})

const token = (userId) => {
  const payload = btoa(JSON.stringify({ userId, exp: Math.floor(Date.now() / 1000) + 3600 }))
    .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')
  return `header.${payload}.signature`
}

const ButtonStub = { template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>' }
const globalStubs = {
  'el-button': ButtonStub,
  'el-icon': true,
  'el-select': true,
  'el-option': true,
  'el-avatar': true,
  'el-tooltip': { template: '<div><slot /></div>' },
  'el-divider': true,
}
const mountEditor = () => mount(EditorView, { global: { stubs: globalStubs } })

describe('EditorView Memory V1 target behavior', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    Object.values(mocks).forEach((value) => value?.mockReset?.())
    mocks.route.path = '/editor/chat-mode'
    mocks.route.params = { id: 'chat-mode' }
    mocks.route.query = {}
    mocks.routerReplace.mockResolvedValue(undefined)
    mocks.getModels.mockResolvedValue({ data: [] })
    mocks.getUserInfo.mockResolvedValue({ data: { username: 'tester' } })
    mocks.listConversations.mockResolvedValue({ items: [], nextCursor: null, hasMore: false })
    mocks.listMessages.mockResolvedValue({ items: [], nextBeforeSequence: null, hasMore: false })
    mocks.executeTask.mockResolvedValue({ data: { answer: '编辑器回答', toolResults: [] } })
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  it('creates and switches to a real backend conversation before sending', async () => {
    localStorage.setItem('accessToken', token('editor-create'))
    mocks.createConversation.mockResolvedValue(conversation('conv-editor-new'))

    const wrapper = mountEditor()
    await flushPromises()
    await wrapper.get('.new-chat-btn').trigger('click')
    await flushPromises()
    const input = wrapper.get('input.chat-input')
    await input.setValue('新话题的第一条消息')
    await input.trigger('keyup.enter')
    await flushPromises()

    expect(mocks.createConversation).toHaveBeenCalledOnce()
    expect(mocks.executeTask).toHaveBeenCalledWith(expect.objectContaining({
      conversationId: 'conv-editor-new',
      task: '新话题的第一条消息',
      clientRequestId: expect.stringMatching(/^[0-9a-f-]{36}$/),
    }))
    expect(mocks.routerReplace).toHaveBeenCalledWith(expect.objectContaining({
      query: expect.objectContaining({ conversation: 'conv-editor-new' }),
    }))
    wrapper.unmount()
  })

  it('preserves the draft and does not send with a default id when creation fails', async () => {
    localStorage.setItem('accessToken', token('editor-create-fail'))
    mocks.createConversation.mockRejectedValue(new Error('create failed'))

    const wrapper = mountEditor()
    await flushPromises()
    const input = wrapper.get('input.chat-input')
    await input.setValue('创建失败后的消息')
    await input.trigger('keyup.enter')
    await flushPromises()

    expect(mocks.createConversation).toHaveBeenCalledOnce()
    expect(mocks.executeTask).not.toHaveBeenCalled()
    expect(input.element.value).toBe('创建失败后的消息')
    expect(mocks.error).toHaveBeenCalled()
    wrapper.unmount()
  })
})
