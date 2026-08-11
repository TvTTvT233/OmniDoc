import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  route: { path: '/editor/chat-mode', params: { id: 'chat-mode' }, query: {} },
  routerPush: vi.fn(),
  routerReplace: vi.fn(),
  getModels: vi.fn(),
  startChat: vi.fn(),
  executeTask: vi.fn(),
  getUserInfo: vi.fn(),
  confirm: vi.fn(),
  messageSuccess: vi.fn(),
  messageWarning: vi.fn(),
  messageError: vi.fn(),
  messageInfo: vi.fn(),
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

vi.mock('vue-router', async () => {
  const actual = await vi.importActual('vue-router')
  return {
    ...actual,
    useRoute: () => mocks.route,
    useRouter: () => ({
      push: mocks.routerPush,
      replace: mocks.routerReplace,
    }),
  }
})

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
    success: mocks.messageSuccess,
    warning: mocks.messageWarning,
    error: mocks.messageError,
    info: mocks.messageInfo,
  },
  ElMessageBox: { confirm: mocks.confirm },
}))

import EditorView from './EditorView.vue'
import {
  CONVERSATION_ENTRY,
  useConversationSession,
} from '../../composables/useConversationSession.js'

const ButtonStub = {
  inheritAttrs: false,
  props: ['disabled', 'loading'],
  template: '<button v-bind="$attrs" :disabled="disabled || loading"><slot /></button>',
}

const globalStubs = {
  'el-button': ButtonStub,
  'el-icon': { template: '<i><slot /></i>' },
  'el-select': true,
  'el-option': true,
  'el-avatar': { template: '<span><slot /></span>' },
  'el-tooltip': { template: '<span><slot /></span>' },
  'el-divider': true,
  'el-dropdown': { template: '<div><slot /><slot name="dropdown" /></div>' },
  'el-dropdown-menu': { template: '<div><slot /></div>' },
  'el-dropdown-item': { template: '<button v-bind="$attrs"><slot /></button>' },
}

const mountEditor = () => mount(EditorView, {
  global: {
    stubs: globalStubs,
    mocks: {
      $router: { push: mocks.routerPush, replace: mocks.routerReplace },
    },
  },
})

const conversation = (conversationId, title, status = 'ACTIVE') => ({
  conversationId,
  title,
  status,
  createdAt: '2026-08-11T00:00:00Z',
  updatedAt: '2026-08-11T00:01:00Z',
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

const messagePage = (items) => ({
  items,
  nextBeforeSequence: null,
  hasMore: false,
})

const token = (userId) => {
  const payload = btoa(JSON.stringify({ userId, exp: Math.floor(Date.now() / 1000) + 3600 }))
    .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')
  return `header.${payload}.signature`
}

const conversationPage = (items) => ({
  items,
  nextCursor: null,
  hasMore: false,
})

const deferred = () => {
  let resolve
  let reject
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

const editorSession = (userId) => useConversationSession({
  userId,
  entry: CONVERSATION_ENTRY.EDITOR,
})

const findAction = (wrapper, { testId, text, extraSelectors = [] }) => {
  const selectors = [`[data-testid="${testId}"]`, ...extraSelectors]
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

const findConversationItem = (wrapper, conversationId, title) => {
  for (const selector of [
    `[data-conversation-id="${conversationId}"]`,
    `[data-testid="conversation-item-${conversationId}"]`,
  ]) {
    const found = wrapper.find(selector)
    if (found.exists()) return found
  }
  const found = wrapper.findAll('.history-item, [role="option"], button, a')
    .find((item) => item.text().includes(title))
  if (found) return found
  throw new Error(`Missing history item: ${conversationId}`)
}

const findDraft = (wrapper) => {
  for (const selector of [
    '[data-testid="conversation-draft"]',
    'input.chat-input',
    'textarea',
  ]) {
    const found = wrapper.find(selector)
    if (found.exists()) return found
  }
  throw new Error('Missing editor conversation draft input')
}

const isDisabled = (wrapper) => wrapper.attributes('disabled') !== undefined
  || wrapper.attributes('aria-disabled') === 'true'
  || wrapper.classes().some((value) => /disabled|is-loading/.test(value))

const seedEditor = (userId, activeConversationId) => {
  localStorage.setItem('accessToken', token(userId))
  const session = editorSession(userId)
  if (activeConversationId) session.activateConversation(activeConversationId)
  return session
}

describe('EditorView Memory V1 conversation lifecycle acceptance', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    vi.clearAllMocks()
    vi.spyOn(console, 'log').mockImplementation(() => {})
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    vi.spyOn(console, 'error').mockImplementation(() => {})
    mocks.route.path = '/editor/chat-mode'
    mocks.route.params.id = 'chat-mode'
    mocks.route.query = {}
    mocks.getModels.mockResolvedValue({ data: [] })
    mocks.getUserInfo.mockResolvedValue({ data: { username: 'tester' } })
    mocks.startChat.mockRejectedValue(new Error('legacy startChat must not be used by M06-C'))
    mocks.confirm.mockResolvedValue('confirm')
  })

  it('uses the shared structured history list, switches existing conversations, then creates a new one', async () => {
    const userId = 'm06c-editor-switch'
    const first = conversation('conv-editor-a', '会话 A')
    const second = conversation('conv-editor-b', '会话 B')
    const created = conversation('conv-editor-new', '新建会话')
    const session = seedEditor(userId, first.conversationId)
    mocks.conversationApi.listConversations.mockResolvedValue(conversationPage([first, second]))
    mocks.conversationApi.getConversation.mockImplementation((id) => Promise.resolve(
      id === first.conversationId ? first : second,
    ))
    mocks.conversationApi.listMessages.mockImplementation((id) => Promise.resolve(messagePage([
      message(id, 1, 'USER', id === first.conversationId ? 'A 的真实历史' : 'B 的真实历史'),
    ])))
    mocks.conversationApi.createConversation.mockResolvedValue(created)

    const wrapper = mountEditor()
    await flushPromises()

    expect(mocks.conversationApi.listConversations).toHaveBeenCalledWith(
      expect.objectContaining({ status: 'ACTIVE' }),
    )
    expect(wrapper.text()).toContain('会话 A')
    expect(wrapper.text()).toContain('会话 B')
    expect(wrapper.text()).toContain('A 的真实历史')

    await findConversationItem(wrapper, second.conversationId, second.title).trigger('click')
    await flushPromises()

    expect(mocks.conversationApi.getConversation).toHaveBeenCalledWith(second.conversationId)
    expect(mocks.conversationApi.listMessages).toHaveBeenCalledWith(
      second.conversationId,
      expect.any(Object),
    )
    expect(session.activeConversationId.value).toBe(second.conversationId)
    expect(wrapper.text()).toContain('B 的真实历史')
    expect(wrapper.text()).not.toContain('A 的真实历史')

    await findAction(wrapper, {
      testId: 'conversation-new',
      text: /新对话|新话题/,
      extraSelectors: ['.new-chat-btn'],
    }).trigger('click')
    await flushPromises()

    expect(mocks.conversationApi.createConversation).toHaveBeenCalledOnce()
    expect(session.activeConversationId.value).toBe(created.conversationId)
    expect(mocks.startChat).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('archives an active conversation through the resource API and removes it from the active list', async () => {
    const userId = 'm06c-editor-archive'
    const target = conversation('conv-editor-archive', '待归档会话')
    const session = seedEditor(userId, target.conversationId)
    let activeItems = [target]
    mocks.conversationApi.listConversations.mockImplementation(({ status } = {}) => Promise.resolve(
      conversationPage(status === 'ARCHIVED' ? [] : activeItems),
    ))
    mocks.conversationApi.getConversation.mockResolvedValue(target)
    mocks.conversationApi.listMessages.mockResolvedValue(messagePage([
      message(target.conversationId, 1, 'USER', '归档前历史'),
    ]))
    mocks.conversationApi.archiveConversation.mockImplementation(async () => {
      activeItems = []
      return { ...target, status: 'ARCHIVED' }
    })

    const wrapper = mountEditor()
    await flushPromises()
    await findAction(wrapper, {
      testId: 'conversation-archive',
      text: /归档/,
    }).trigger('click')
    await flushPromises()

    expect(mocks.conversationApi.archiveConversation).toHaveBeenCalledWith(target.conversationId)
    const stillRenderedAsActive = wrapper.text().includes(target.title)
      && !wrapper.text().match(/已归档|归档会话/)
    expect(stillRenderedAsActive).toBe(false)
    expect(session.activeConversationId.value === null
      || session.activeConversationId.value === target.conversationId).toBe(true)
    wrapper.unmount()
  })

  it('restores an archived conversation and makes its durable history available again', async () => {
    const userId = 'm06c-editor-restore'
    const archived = conversation('conv-editor-restore', '可恢复会话', 'ARCHIVED')
    const session = seedEditor(userId, archived.conversationId)
    mocks.conversationApi.listConversations.mockImplementation(({ status } = {}) => Promise.resolve(
      conversationPage(status === 'ARCHIVED' ? [archived] : []),
    ))
    mocks.conversationApi.getConversation.mockResolvedValue(archived)
    mocks.conversationApi.listMessages.mockResolvedValue(messagePage([
      message(archived.conversationId, 1, 'ASSISTANT', '归档历史仍可读取'),
    ]))
    mocks.conversationApi.restoreConversation.mockResolvedValue({ ...archived, status: 'ACTIVE' })

    const wrapper = mountEditor()
    await flushPromises()

    if (!wrapper.text().includes('可恢复会话')) {
      await findAction(wrapper, {
        testId: 'conversation-filter-archived',
        text: /已归档|归档会话/,
      }).trigger('click')
      await flushPromises()
    }
    if (!wrapper.text().includes('归档历史仍可读取')) {
      await findConversationItem(wrapper, archived.conversationId, archived.title).trigger('click')
      await flushPromises()
    }

    await findAction(wrapper, {
      testId: 'conversation-restore',
      text: /恢复/,
    }).trigger('click')
    await flushPromises()

    expect(mocks.conversationApi.restoreConversation).toHaveBeenCalledWith(archived.conversationId)
    expect(session.activeConversationId.value).toBe(archived.conversationId)
    expect(wrapper.text()).toContain('归档历史仍可读取')
    wrapper.unmount()
  })

  it('permanently deletes only after confirmation and clears active local history', async () => {
    const userId = 'm06c-editor-delete'
    const target = conversation('conv-editor-delete', '永久删除会话')
    const session = seedEditor(userId, target.conversationId)
    mocks.conversationApi.listConversations.mockResolvedValue(conversationPage([target]))
    mocks.conversationApi.getConversation.mockResolvedValue(target)
    mocks.conversationApi.listMessages.mockResolvedValue(messagePage([
      message(target.conversationId, 1, 'USER', '删除后不得残留'),
    ]))
    mocks.conversationApi.deleteConversation.mockResolvedValue({
      conversationId: target.conversationId,
      deleted: true,
    })

    const wrapper = mountEditor()
    await flushPromises()
    await findAction(wrapper, {
      testId: 'conversation-delete',
      text: /永久删除|删除会话/,
    }).trigger('click')
    await flushPromises()

    expect(mocks.confirm).toHaveBeenCalledOnce()
    expect(mocks.conversationApi.deleteConversation).toHaveBeenCalledWith(target.conversationId)
    expect(session.activeConversationId.value).toBeNull()
    expect(wrapper.text()).not.toContain('删除后不得残留')
    expect(localStorage.getItem(
      `omnidoc:conversation:v1:${encodeURIComponent(userId)}:editor:active`,
    )).toBeNull()
    wrapper.unmount()
  })

  it('disables new/history switching while an Agent execution is in flight', async () => {
    const userId = 'm06c-editor-race'
    const running = conversation('conv-editor-running', '执行中的会话')
    const other = conversation('conv-editor-other', '不可竞态切换')
    seedEditor(userId, running.conversationId)
    mocks.conversationApi.listConversations.mockResolvedValue(conversationPage([running, other]))
    mocks.conversationApi.getConversation.mockResolvedValue(running)
    mocks.conversationApi.listMessages.mockResolvedValue(messagePage([]))
    const pendingExecution = deferred()
    mocks.executeTask.mockReturnValue(pendingExecution.promise)

    const wrapper = mountEditor()
    await flushPromises()
    const draft = findDraft(wrapper)
    await draft.setValue('执行期间不得切换')
    await draft.trigger('keyup.enter')
    await flushPromises()

    expect(mocks.executeTask).toHaveBeenCalledOnce()
    const newConversation = findAction(wrapper, {
      testId: 'conversation-new',
      text: /新对话|新话题/,
      extraSelectors: ['.new-chat-btn'],
    })
    const otherConversation = findConversationItem(wrapper, other.conversationId, other.title)

    try {
      expect(isDisabled(newConversation)).toBe(true)
      expect(isDisabled(otherConversation)).toBe(true)
      await newConversation.trigger('click')
      await otherConversation.trigger('click')
      await flushPromises()
      expect(mocks.conversationApi.createConversation).not.toHaveBeenCalled()
      expect(mocks.conversationApi.getConversation).not.toHaveBeenCalledWith(other.conversationId)
      expect(mocks.executeTask.mock.calls[0][0].conversationId).toBe(running.conversationId)
    } finally {
      pendingExecution.resolve({ data: { answer: '执行完成', toolResults: [] } })
      await flushPromises()
      wrapper.unmount()
    }
  })
})
