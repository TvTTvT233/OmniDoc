import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  route: { path: '/dashboard' },
  createConversation: vi.fn(),
  listConversations: vi.fn(),
  getConversation: vi.fn(),
  listMessages: vi.fn(),
  archiveConversation: vi.fn(),
  restoreConversation: vi.fn(),
  deleteConversation: vi.fn(),
  getModels: vi.fn(),
  executeTask: vi.fn(),
  confirmApproval: vi.fn(),
  cancelApproval: vi.fn(),
  searchDocs: vi.fn(),
  confirm: vi.fn(),
  error: vi.fn(),
}))

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

vi.mock('../../api/ai', () => ({
  aiApi: { getModels: mocks.getModels },
}))

vi.mock('../../api/agent', () => ({
  agentApi: {
    executeTask: mocks.executeTask,
    confirmApproval: mocks.confirmApproval,
    cancelApproval: mocks.cancelApproval,
  },
}))

vi.mock('../../api/document.js', () => ({
  docApi: { searchDocs: mocks.searchDocs },
}))

vi.mock('vue-router', async () => {
  const actual = await vi.importActual('vue-router')
  return { ...actual, useRoute: () => mocks.route }
})

vi.mock('element-plus', () => ({
  ElMessage: {
    success: vi.fn(),
    warning: vi.fn(),
    error: mocks.error,
    info: vi.fn(),
  },
  ElMessageBox: { confirm: mocks.confirm },
}))

import GlobalAiAssistant from './GlobalAiAssistant.vue'

const conversation = (conversationId, status = 'ACTIVE') => ({
  conversationId,
  title: conversationId,
  status,
  createdAt: '2026-08-11T00:00:00Z',
  updatedAt: '2026-08-11T00:00:00Z',
})

const message = (conversationId, sequence, role, content) => ({
  messageId: `${conversationId}-${sequence}`,
  conversationId,
  sequence,
  role,
  messageType: 'TEXT',
  content,
  createdAt: '2026-08-11T00:00:00Z',
})

const token = (userId) => {
  const payload = btoa(JSON.stringify({ userId, exp: Math.floor(Date.now() / 1000) + 3600 }))
    .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')
  return `header.${payload}.signature`
}

const ButtonStub = { template: '<button v-bind="$attrs"><slot /></button>' }
const DrawerStub = { template: '<section><slot /></section>' }
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

describe('GlobalAiAssistant Memory V1 target behavior', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    Object.values(mocks).forEach((value) => value?.mockReset?.())
    mocks.getModels.mockResolvedValue({ data: [] })
    mocks.listConversations.mockResolvedValue({ items: [], nextCursor: null, hasMore: false })
    mocks.listMessages.mockResolvedValue({ items: [], nextBeforeSequence: null, hasMore: false })
    mocks.executeTask.mockResolvedValue({ data: { answer: '已完成', toolResults: [] } })
    mocks.confirm.mockResolvedValue('confirm')
  })

  it('restores durable structured history on mount', async () => {
    localStorage.setItem('accessToken', token('global-refresh'))
    localStorage.setItem(
      'omnidoc:conversation:v1:global-refresh:global-assistant:active',
      'conv-refresh',
    )
    mocks.getConversation.mockResolvedValue(conversation('conv-refresh'))
    mocks.listMessages.mockResolvedValue({
      items: [
        message('conv-refresh', 1, 'USER', '持久化用户消息'),
        message('conv-refresh', 2, 'ASSISTANT', '持久化助手回答'),
      ],
      nextBeforeSequence: null,
      hasMore: false,
    })

    const wrapper = mountAssistant()
    await flushPromises()

    expect(mocks.getConversation).toHaveBeenCalledWith('conv-refresh')
    expect(wrapper.text()).toContain('持久化用户消息')
    expect(wrapper.text()).toContain('持久化助手回答')
    wrapper.unmount()
  })

  it('keeps the draft and never executes the Agent when real conversation creation fails', async () => {
    localStorage.setItem('accessToken', token('global-create-fail'))
    mocks.createConversation.mockRejectedValue(new Error('create failed'))

    const wrapper = mountAssistant()
    await flushPromises()
    const input = wrapper.get('input.custom-input')
    await input.setValue('必须保留的草稿')
    await input.trigger('keyup.enter')
    await flushPromises()

    expect(mocks.createConversation).toHaveBeenCalledOnce()
    expect(mocks.executeTask).not.toHaveBeenCalled()
    expect(input.element.value).toBe('必须保留的草稿')
    expect(localStorage.getItem('global_conv_id')).toBeNull()
    expect(mocks.error).toHaveBeenCalled()
    wrapper.unmount()
  })
})
