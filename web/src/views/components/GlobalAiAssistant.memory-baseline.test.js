import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  getModels: vi.fn(),
  startChat: vi.fn(),
  getConversationHistory: vi.fn(),
  executeTask: vi.fn(),
  confirmApproval: vi.fn(),
  searchDocs: vi.fn(),
  confirm: vi.fn(),
}))

vi.mock('../../api/ai', () => ({
  aiApi: {
    getModels: mocks.getModels,
    startChat: mocks.startChat,
    getConversationHistory: mocks.getConversationHistory,
  },
}))

vi.mock('../../api/agent', () => ({
  agentApi: {
    executeTask: mocks.executeTask,
    confirmApproval: mocks.confirmApproval,
  },
}))

vi.mock('../../api/document.js', () => ({
  docApi: { searchDocs: mocks.searchDocs },
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ path: '/dashboard' }),
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    success: vi.fn(),
    warning: vi.fn(),
    error: vi.fn(),
  },
  ElMessageBox: { confirm: mocks.confirm },
}))

import GlobalAiAssistant from './GlobalAiAssistant.vue'
import { useGlobalAi } from '../../utils/useGlobalAi.js'

const ButtonStub = {
  template: '<button><slot /></button>',
}

const DrawerStub = {
  template: '<section><slot /></section>',
}

const mountAssistant = () => mount(GlobalAiAssistant, {
  global: {
    stubs: {
      'el-button': ButtonStub,
      'el-drawer': DrawerStub,
      'el-icon': true,
      'el-select': true,
      'el-option': true,
      Cpu: true,
      User: true,
      Document: true,
      Loading: true,
      Top: true,
    },
  },
})

describe('GlobalAiAssistant current memory baseline', () => {
  beforeEach(() => {
    const session = useGlobalAi()
    session.resetConversation()
    localStorage.clear()
    mocks.getModels.mockResolvedValue({ data: [] })
    mocks.startChat.mockResolvedValue({ data: 'conv-created' })
    mocks.executeTask.mockResolvedValue({
      data: { answer: '已完成', toolResults: [] },
    })
    mocks.confirm.mockResolvedValue('confirm')
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  it('does not load history or create a backend conversation when mounted', async () => {
    const wrapper = mountAssistant()
    await flushPromises()

    expect(mocks.getModels).toHaveBeenCalledOnce()
    expect(mocks.getConversationHistory).not.toHaveBeenCalled()
    expect(mocks.startChat).not.toHaveBeenCalled()
    expect(useGlobalAi().chatHistory.value).toEqual([])

    wrapper.unmount()
  })

  it('uses a temp id for the Agent request when backend conversation creation fails', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(1786400000123)
    mocks.startChat.mockRejectedValueOnce(new Error('create failed'))

    const wrapper = mountAssistant()
    await flushPromises()

    const input = wrapper.get('input.custom-input')
    await input.setValue('测试临时会话')
    await input.trigger('keyup.enter')
    await flushPromises()

    expect(mocks.startChat).toHaveBeenCalledOnce()
    expect(mocks.executeTask).toHaveBeenCalledWith(expect.objectContaining({
      conversationId: 'temp_1786400000123',
      task: '测试临时会话',
    }))
    expect(useGlobalAi().currentConvId.value).toBe('temp_1786400000123')

    wrapper.unmount()
  })
})
