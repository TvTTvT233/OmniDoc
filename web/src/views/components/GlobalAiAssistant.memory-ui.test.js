import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  route: { path: '/dashboard', query: {} },
  getModels: vi.fn(),
  startChat: vi.fn(),
  getConversationHistory: vi.fn(),
  executeTask: vi.fn(),
  confirmApproval: vi.fn(),
  searchDocs: vi.fn(),
  confirm: vi.fn(),
  messageSuccess: vi.fn(),
  messageWarning: vi.fn(),
  messageError: vi.fn(),
  conversationApi: {
    createConversation: vi.fn(),
    listConversations: vi.fn(),
    getConversation: vi.fn(),
    listMessages: vi.fn(),
    archiveConversation: vi.fn(),
    restoreConversation: vi.fn(),
    deleteConversation: vi.fn(),
  },
}))

vi.mock('../../api/conversation.js', () => ({
  conversationApi: mocks.conversationApi,
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
    cancelApproval: vi.fn(),
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
    success: mocks.messageSuccess,
    warning: mocks.messageWarning,
    error: mocks.messageError,
  },
  ElMessageBox: { confirm: mocks.confirm },
}))

import GlobalAiAssistant from './GlobalAiAssistant.vue'
import {
  CONVERSATION_ENTRY,
  useConversationSession,
} from '../../composables/useConversationSession.js'

const ButtonStub = {
  inheritAttrs: false,
  props: ['disabled', 'loading'],
  template: '<button v-bind="$attrs" :disabled="disabled || loading"><slot /></button>',
}

const DrawerStub = {
  template: '<section><slot /></section>',
}

const mountAssistant = () => mount(GlobalAiAssistant, {
  global: {
    stubs: {
      'el-button': ButtonStub,
      'el-drawer': DrawerStub,
      'el-icon': { template: '<i><slot /></i>' },
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

const conversation = (conversationId, overrides = {}) => ({
  conversationId,
  title: `会话 ${conversationId}`,
  status: 'ACTIVE',
  createdAt: '2026-08-11T00:00:00Z',
  updatedAt: '2026-08-11T00:01:00Z',
  ...overrides,
})

const message = (conversationId, sequence, role, content) => ({
  messageId: `msg-${conversationId}-${sequence}`,
  conversationId,
  sequence,
  role,
  messageType: 'TEXT',
  content,
  traceId: null,
  toolCallId: null,
  metadata: {},
  createdAt: `2026-08-11T00:00:0${sequence}Z`,
})

const page = (items, { nextBeforeSequence = null, hasMore = false } = {}) => ({
  items,
  nextBeforeSequence,
  hasMore,
})

const token = (userId) => {
  const payload = btoa(JSON.stringify({ userId, exp: Math.floor(Date.now() / 1000) + 3600 }))
    .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')
  return `header.${payload}.signature`
}

const deferred = () => {
  let resolve
  let reject
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

const findAction = (wrapper, { testId, text, extraSelectors = [] }) => {
  const selectors = [
    `[data-testid="${testId}"]`,
    `[aria-label*="${text.source.replaceAll('\\', '')}"]`,
    `[title*="${text.source.replaceAll('\\', '')}"]`,
    ...extraSelectors,
  ]
  for (const selector of selectors) {
    const found = wrapper.find(selector)
    if (found.exists()) return found
  }
  const found = wrapper.findAll('button').find((button) => text.test([
    button.text(),
    button.attributes('title'),
    button.attributes('aria-label'),
  ].filter(Boolean).join(' ')))
  if (found) return found
  throw new Error(`Missing user action: ${testId}`)
}

const findDraft = (wrapper) => {
  for (const selector of [
    '[data-testid="conversation-draft"]',
    'input.custom-input',
    'input.chat-input',
    'textarea',
  ]) {
    const found = wrapper.find(selector)
    if (found.exists()) return found
  }
  throw new Error('Missing conversation draft input')
}

const scopedSession = (userId) => useConversationSession({
  userId,
  entry: CONVERSATION_ENTRY.GLOBAL_ASSISTANT,
})

const seedActiveConversation = (userId, conversationId) => {
  localStorage.setItem('accessToken', token(userId))
  const session = scopedSession(userId)
  session.activateConversation(conversationId)
  return session
}

const expectTextsInOrder = (wrapper, texts) => {
  const rendered = wrapper.text()
  let previous = -1
  for (const value of texts) {
    const current = rendered.indexOf(value)
    expect(current, `expected rendered history to contain ${value}`).toBeGreaterThan(-1)
    expect(current, `expected ${value} after the previous message`).toBeGreaterThan(previous)
    previous = current
  }
}

describe('GlobalAiAssistant Memory V1 UI acceptance', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    vi.clearAllMocks()
    vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    mocks.getModels.mockResolvedValue({ data: [] })
    mocks.startChat.mockRejectedValue(new Error('legacy startChat must not be used by M06-C'))
    mocks.getConversationHistory.mockRejectedValue(new Error('legacy history must not be used'))
    mocks.confirm.mockResolvedValue('confirm')
    mocks.conversationApi.listConversations.mockResolvedValue({
      items: [],
      nextCursor: null,
      hasMore: false,
    })
  })

  it('restores the persisted conversation and loads older pages by beforeSequence', async () => {
    const userId = 'm06c-global-refresh'
    const conversationId = 'conv-global-refresh'
    seedActiveConversation(userId, conversationId)
    mocks.conversationApi.getConversation.mockResolvedValue(conversation(conversationId))
    mocks.conversationApi.listMessages.mockImplementation((id, options = {}) => {
      if (options.beforeSequence === 3) {
        return Promise.resolve(page([
          message(id, 1, 'USER', '第一条真实历史'),
          message(id, 2, 'ASSISTANT', '第二条真实历史'),
        ]))
      }
      return Promise.resolve(page([
        message(id, 3, 'USER', '第三条真实历史'),
        message(id, 4, 'ASSISTANT', '第四条真实历史'),
      ], { nextBeforeSequence: 3, hasMore: true }))
    })

    const wrapper = mountAssistant()
    await flushPromises()

    expect(mocks.conversationApi.getConversation).toHaveBeenCalledWith(conversationId)
    expect(mocks.conversationApi.listMessages).toHaveBeenCalledWith(
      conversationId,
      expect.not.objectContaining({ beforeSequence: expect.anything() }),
    )
    expect(wrapper.text()).toContain('第三条真实历史')
    expect(wrapper.text()).toContain('第四条真实历史')

    if (!wrapper.text().includes('第一条真实历史')) {
      const loadOlder = findAction(wrapper, {
        testId: 'conversation-load-older',
        text: /加载更多|更早消息|历史消息/,
      })
      await loadOlder.trigger('click')
      await flushPromises()
    }

    expect(mocks.conversationApi.listMessages).toHaveBeenCalledWith(
      conversationId,
      expect.objectContaining({ beforeSequence: 3 }),
    )
    expectTextsInOrder(wrapper, [
      '第一条真实历史',
      '第二条真实历史',
      '第三条真实历史',
      '第四条真实历史',
    ])
    wrapper.unmount()
  })

  it('continues message pagination when public projection returns an empty page with hasMore', async () => {
    const userId = 'm06c-global-empty-page'
    const conversationId = 'conv-global-empty-page'
    seedActiveConversation(userId, conversationId)
    mocks.conversationApi.getConversation.mockResolvedValue(conversation(conversationId))
    mocks.conversationApi.listMessages.mockImplementation((id, options = {}) => {
      if (options.beforeSequence === 50) {
        return Promise.resolve(page([], { nextBeforeSequence: 25, hasMore: true }))
      }
      if (options.beforeSequence === 25) {
        return Promise.resolve(page([
          message(id, 1, 'USER', '跨过空投影页的更早消息'),
        ]))
      }
      return Promise.resolve(page([
        message(id, 50, 'ASSISTANT', '最新公开消息'),
      ], { nextBeforeSequence: 50, hasMore: true }))
    })

    const wrapper = mountAssistant()
    await flushPromises()

    for (let attempt = 0; attempt < 2 && !wrapper.text().includes('跨过空投影页的更早消息'); attempt += 1) {
      await findAction(wrapper, {
        testId: 'conversation-load-older',
        text: /加载更多|更早消息|历史消息/,
      }).trigger('click')
      await flushPromises()
    }

    expect(mocks.conversationApi.listMessages).toHaveBeenCalledWith(
      conversationId,
      expect.objectContaining({ beforeSequence: 50 }),
    )
    expect(mocks.conversationApi.listMessages).toHaveBeenCalledWith(
      conversationId,
      expect.objectContaining({ beforeSequence: 25 }),
    )
    expectTextsInOrder(wrapper, ['跨过空投影页的更早消息', '最新公开消息'])
    wrapper.unmount()
  })

  it('keeps the old conversation visible until backend creation succeeds, then switches', async () => {
    const userId = 'm06c-global-create'
    const oldId = 'conv-global-old'
    const newId = 'conv-global-new'
    const session = seedActiveConversation(userId, oldId)
    mocks.conversationApi.getConversation.mockResolvedValue(conversation(oldId))
    mocks.conversationApi.listMessages.mockResolvedValue(page([
      message(oldId, 1, 'ASSISTANT', '旧会话必须保留到创建成功'),
    ]))
    const pendingCreate = deferred()
    mocks.conversationApi.createConversation.mockReturnValue(pendingCreate.promise)

    const wrapper = mountAssistant()
    await flushPromises()
    const draft = findDraft(wrapper)
    await draft.setValue('新会话草稿不能丢')
    const newConversation = findAction(wrapper, {
      testId: 'conversation-new',
      text: /新对话|开始新对话/,
      extraSelectors: ['.new-conversation-btn'],
    })
    await newConversation.trigger('click')
    await flushPromises()

    expect(mocks.conversationApi.createConversation).toHaveBeenCalledOnce()
    expect(session.activeConversationId.value).toBe(oldId)
    expect(wrapper.text()).toContain('旧会话必须保留到创建成功')
    expect(findDraft(wrapper).element.value).toBe('新会话草稿不能丢')

    pendingCreate.resolve(conversation(newId))
    await flushPromises()

    expect(session.activeConversationId.value).toBe(newId)
    expect(wrapper.text()).not.toContain('旧会话必须保留到创建成功')
    expect(findDraft(wrapper).element.value).toBe('新会话草稿不能丢')
    expect(mocks.startChat).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('preserves the draft and never executes an Agent request when conversation creation fails', async () => {
    const userId = 'm06c-global-create-failure'
    localStorage.setItem('accessToken', token(userId))
    mocks.conversationApi.createConversation.mockRejectedValue(new Error('create unavailable'))

    const wrapper = mountAssistant()
    await flushPromises()
    const draft = findDraft(wrapper)
    await draft.setValue('创建失败后仍需保留')
    await draft.trigger('keyup.enter')
    await flushPromises()

    expect(mocks.conversationApi.createConversation).toHaveBeenCalledOnce()
    expect(mocks.executeTask).not.toHaveBeenCalled()
    expect(findDraft(wrapper).element.value).toBe('创建失败后仍需保留')
    expect(scopedSession(userId).activeConversationId.value).toBeNull()
    expect(JSON.stringify({
      local: Object.values(localStorage),
      session: Object.values(sessionStorage),
    })).not.toMatch(/temp_|default_conv/)
    expect(wrapper.text()).not.toMatch(/指令已执行完毕|任务已完成|本地兜底回复/)
    wrapper.unmount()
  })

  it('reuses clientRequestId after failure, retains the draft, and shows no pseudo assistant reply', async () => {
    const userId = 'm06c-global-retry'
    const conversationId = 'conv-global-retry'
    seedActiveConversation(userId, conversationId)
    mocks.conversationApi.getConversation.mockResolvedValue(conversation(conversationId))
    mocks.conversationApi.listMessages.mockImplementation(() => Promise.resolve(page(
      mocks.executeTask.mock.calls.length >= 2
        ? [message(conversationId, 2, 'ASSISTANT', '真实后端回答')]
        : [],
    )))
    mocks.executeTask
      .mockRejectedValueOnce(new Error('model unavailable'))
      .mockResolvedValueOnce({ data: { answer: '真实后端回答', toolResults: [] } })

    const wrapper = mountAssistant()
    await flushPromises()
    const draft = findDraft(wrapper)
    await draft.setValue('同一请求只提交一次')
    await draft.trigger('keyup.enter')
    await flushPromises()

    expect(mocks.executeTask).toHaveBeenCalledOnce()
    const firstPayload = mocks.executeTask.mock.calls[0][0]
    expect(firstPayload).toMatchObject({
      conversationId,
      task: '同一请求只提交一次',
    })
    expect(firstPayload.clientRequestId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    )
    expect(findDraft(wrapper).element.value).toBe('同一请求只提交一次')
    expect(wrapper.text()).not.toMatch(/指令已执行完毕|任务已完成|本地兜底回复|智能纠错|智能润色|文档摘要/)

    await findDraft(wrapper).trigger('keyup.enter')
    await flushPromises()

    expect(mocks.executeTask).toHaveBeenCalledTimes(2)
    expect(mocks.executeTask.mock.calls[1][0].clientRequestId).toBe(firstPayload.clientRequestId)
    expect(findDraft(wrapper).element.value).toBe('')
    expect(wrapper.text()).toContain('真实后端回答')
    wrapper.unmount()
  })

  it('rejects legacy fallback ids and creates a durable conversation before sending', async () => {
    const userId = 'm06c-global-forbidden-id'
    const durableId = 'conv-global-durable'
    localStorage.setItem('accessToken', token(userId))
    localStorage.setItem(
      `omnidoc:conversation:v1:${encodeURIComponent(userId)}:global-assistant:active`,
      'temp_legacy',
    )
    localStorage.setItem('global_conv_id', 'default_conv')
    mocks.conversationApi.createConversation.mockResolvedValue(conversation(durableId))
    mocks.executeTask.mockResolvedValue({ data: { answer: '持久化回答', toolResults: [] } })

    const wrapper = mountAssistant()
    await flushPromises()
    const draft = findDraft(wrapper)
    await draft.setValue('必须先拿到真实 ID')
    await draft.trigger('keyup.enter')
    await flushPromises()

    expect(mocks.conversationApi.createConversation).toHaveBeenCalledOnce()
    expect(mocks.executeTask).toHaveBeenCalledOnce()
    expect(mocks.executeTask.mock.calls[0][0]).toMatchObject({
      conversationId: durableId,
      task: '必须先拿到真实 ID',
    })
    expect(mocks.executeTask.mock.calls[0][0].conversationId).not.toMatch(/^temp_|^default_conv$/)
    expect(mocks.startChat).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
