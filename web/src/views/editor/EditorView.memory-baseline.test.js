import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  route: { path: '/editor/chat-mode', params: { id: 'chat-mode' } },
  routerPush: vi.fn(),
  getModels: vi.fn(),
  startChat: vi.fn(),
  executeTask: vi.fn(),
  getUserInfo: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => ({ push: mocks.routerPush }),
}))

vi.mock('../../api/ai', () => ({
  aiApi: {
    getModels: mocks.getModels,
    startChat: mocks.startChat,
  },
}))

vi.mock('../../api/agent', () => ({
  agentApi: { executeTask: mocks.executeTask },
}))

vi.mock('../../api/user', () => ({
  userApi: { getUserInfo: mocks.getUserInfo },
}))

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
    error: vi.fn(),
    info: vi.fn(),
  },
  ElMessageBox: { confirm: vi.fn() },
}))

import EditorView from './EditorView.vue'
import GlobalAiAssistant from '../components/GlobalAiAssistant.vue'
import { useGlobalAi } from '../../utils/useGlobalAi.js'

const ButtonStub = {
  template: '<button><slot /></button>',
}

const DrawerStub = {
  template: '<section><slot /></section>',
}

const globalStubs = {
  'el-button': ButtonStub,
  'el-drawer': DrawerStub,
  'el-icon': true,
  'el-select': true,
  'el-option': true,
  'el-avatar': true,
  'el-tooltip': { template: '<div><slot /></div>' },
  'el-divider': true,
}

const mountEditor = () => mount(EditorView, {
  global: { stubs: globalStubs },
})

const mountGlobalAssistant = () => mount(GlobalAiAssistant, {
  global: { stubs: globalStubs },
})

const sendFromEditor = async (wrapper, message) => {
  const input = wrapper.get('input.chat-input')
  await input.setValue(message)
  await input.trigger('keyup.enter')
  await flushPromises()
}

describe('EditorView current memory baseline', () => {
  beforeEach(() => {
    useGlobalAi().resetConversation()
    localStorage.clear()
    sessionStorage.clear()
    mocks.route.path = '/editor/chat-mode'
    mocks.route.params.id = 'chat-mode'
    mocks.getModels.mockResolvedValue({ data: [] })
    mocks.startChat.mockResolvedValue({ data: 'conv-editor-existing' })
    mocks.executeTask.mockResolvedValue({
      data: { answer: '编辑器回答', toolResults: [] },
    })
    mocks.getUserInfo.mockResolvedValue({ data: { username: 'tester' } })
    vi.spyOn(console, 'log').mockImplementation(() => {})
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  it('clears the chat UI for a new topic but reuses the existing backend id', async () => {
    const wrapper = mountEditor()
    await flushPromises()

    expect(mocks.startChat).toHaveBeenCalledOnce()

    await wrapper.get('.new-chat-btn').trigger('click')
    await sendFromEditor(wrapper, '新话题的第一条消息')

    expect(mocks.startChat).toHaveBeenCalledOnce()
    expect(mocks.executeTask).toHaveBeenCalledWith(expect.objectContaining({
      conversationId: 'conv-editor-existing',
      task: '新话题的第一条消息',
    }))

    wrapper.unmount()
  })

  it('falls back to default_conv when editor conversation creation fails', async () => {
    mocks.startChat.mockRejectedValueOnce(new Error('create failed'))

    const wrapper = mountEditor()
    await flushPromises()
    await sendFromEditor(wrapper, '创建失败后的消息')

    expect(mocks.executeTask).toHaveBeenCalledWith(expect.objectContaining({
      conversationId: 'default_conv',
      task: '创建失败后的消息',
    }))

    wrapper.unmount()
  })

  it('keeps global assistant and editor active conversation state split', async () => {
    const globalSession = useGlobalAi()
    globalSession.saveConvId('conv-global-existing')

    const editor = mountEditor()
    await flushPromises()
    await sendFromEditor(editor, '编辑器消息')

    const globalAssistant = mountGlobalAssistant()
    await flushPromises()
    const globalInput = globalAssistant.get('input.custom-input')
    await globalInput.setValue('全局助理消息')
    await globalInput.trigger('keyup.enter')
    await flushPromises()

    expect(mocks.executeTask).toHaveBeenNthCalledWith(1, expect.objectContaining({
      conversationId: 'conv-editor-existing',
      task: '编辑器消息',
    }))
    expect(mocks.executeTask).toHaveBeenNthCalledWith(2, expect.objectContaining({
      conversationId: 'conv-global-existing',
      task: '全局助理消息',
    }))
    expect(globalSession.currentConvId.value).toBe('conv-global-existing')

    editor.unmount()
    globalAssistant.unmount()
  })
})
