import { beforeEach, describe, expect, it, vi } from 'vitest'
import { CONVERSATION_ENTRY } from './useConversationSession.js'
import { useConversationWorkspace } from './useConversationWorkspace.js'

const conversation = (conversationId, status = 'ACTIVE') => ({
  conversationId,
  title: conversationId,
  status,
  createdAt: '2026-08-11T00:00:00Z',
  updatedAt: '2026-08-11T00:00:00Z',
})

const message = (conversationId, sequence, role, content) => ({
  messageId: `${conversationId}-message-${sequence}`,
  conversationId,
  sequence,
  role,
  messageType: 'TEXT',
  content,
  traceId: null,
  toolCallId: null,
  metadata: {},
  createdAt: '2026-08-11T00:00:00Z',
})

function api(overrides = {}) {
  return {
    createConversation: vi.fn(),
    listConversations: vi.fn(async ({ status }) => ({
      items: [], nextCursor: null, hasMore: false, status,
    })),
    getConversation: vi.fn(),
    listMessages: vi.fn(),
    archiveConversation: vi.fn(),
    restoreConversation: vi.fn(),
    deleteConversation: vi.fn(),
    ...overrides,
  }
}

describe('conversation workspace orchestration contract', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
  })

  it('restores the active conversation and structured latest history after reload', async () => {
    localStorage.setItem(
      'omnidoc:conversation:v1:user-refresh:global-assistant:active',
      'conv-refresh',
    )
    const backend = api({
      getConversation: vi.fn().mockResolvedValue(conversation('conv-refresh')),
      listMessages: vi.fn().mockResolvedValue({
        items: [
          message('conv-refresh', 1, 'USER', '远端用户消息'),
          message('conv-refresh', 2, 'ASSISTANT', '远端助手消息'),
        ],
        nextBeforeSequence: null,
        hasMore: false,
      }),
    })
    const workspace = useConversationWorkspace({
      userId: 'user-refresh',
      entry: CONVERSATION_ENTRY.GLOBAL_ASSISTANT,
      api: backend,
    })

    await workspace.initialize()

    expect(workspace.activeConversationId.value).toBe('conv-refresh')
    expect(workspace.messages.value.map((item) => item.content))
      .toEqual(['远端用户消息', '远端助手消息'])
  })

  it('continues through an empty public page and prepends older messages without gaps', async () => {
    const backend = api({
      getConversation: vi.fn().mockResolvedValue(conversation('conv-pages')),
      listMessages: vi.fn()
        .mockResolvedValueOnce({
          items: [message('conv-pages', 3, 'ASSISTANT', 'latest')],
          nextBeforeSequence: 3,
          hasMore: true,
        })
        .mockResolvedValueOnce({ items: [], nextBeforeSequence: 2, hasMore: true })
        .mockResolvedValueOnce({
          items: [message('conv-pages', 1, 'USER', 'oldest')],
          nextBeforeSequence: null,
          hasMore: false,
        }),
    })
    const workspace = useConversationWorkspace({
      userId: 'user-pages',
      entry: CONVERSATION_ENTRY.EDITOR,
      api: backend,
    })

    await workspace.openConversation('conv-pages')
    await workspace.loadOlderMessages()

    expect(backend.listMessages).toHaveBeenNthCalledWith(2, 'conv-pages', {
      beforeSequence: 3,
      limit: 50,
    })
    expect(backend.listMessages).toHaveBeenNthCalledWith(3, 'conv-pages', {
      beforeSequence: 2,
      limit: 50,
    })
    expect(workspace.messages.value.map((item) => item.sequence)).toEqual([1, 3])
  })

  it('creates before switching and keeps the old pointer and draft on failure', async () => {
    const backend = api({
      createConversation: vi.fn().mockRejectedValue(new Error('network unavailable')),
    })
    const workspace = useConversationWorkspace({
      userId: 'user-create',
      entry: CONVERSATION_ENTRY.GLOBAL_ASSISTANT,
      api: backend,
    })
    workspace.session.activateConversation('conv-old')
    workspace.setDraft('必须保留的草稿')

    await expect(workspace.createConversation({ title: '新对话' })).rejects.toMatchObject({
      code: 'CONVERSATION_REQUEST_FAILED',
    })

    expect(workspace.activeConversationId.value).toBe('conv-old')
    expect(workspace.session.draft.value).toBe('必须保留的草稿')
  })

  it('archives, restores and permanently deletes through distinct operations', async () => {
    const backend = api({
      getConversation: vi.fn().mockResolvedValue(conversation('conv-life')),
      listMessages: vi.fn().mockResolvedValue({
        items: [], nextBeforeSequence: null, hasMore: false,
      }),
      archiveConversation: vi.fn().mockResolvedValue(conversation('conv-life', 'ARCHIVED')),
      restoreConversation: vi.fn().mockResolvedValue(conversation('conv-life', 'ACTIVE')),
      deleteConversation: vi.fn().mockResolvedValue({ conversationId: 'conv-life', deleted: true }),
    })
    const workspace = useConversationWorkspace({
      userId: 'user-life',
      entry: CONVERSATION_ENTRY.EDITOR,
      api: backend,
    })
    await workspace.openConversation('conv-life')
    workspace.setDraft('删除后应清理的草稿')

    await workspace.archiveConversation()
    expect(() => workspace.beginSubmission()).toThrowError(/restored before sending/)
    await workspace.restoreConversation()
    await workspace.deleteConversation()

    expect(backend.archiveConversation).toHaveBeenCalledWith('conv-life')
    expect(backend.restoreConversation).toHaveBeenCalledWith('conv-life')
    expect(backend.deleteConversation).toHaveBeenCalledWith('conv-life')
    expect(workspace.activeConversationId.value).toBeNull()
    expect(workspace.session.draft.value).toBe('')
    expect(workspace.messages.value).toEqual([])
  })

  it('keeps the last selected conversation when an earlier detail request finishes late', async () => {
    let resolveFirst
    let resolveSecond
    const first = new Promise((resolve) => { resolveFirst = resolve })
    const second = new Promise((resolve) => { resolveSecond = resolve })
    const backend = api({
      getConversation: vi.fn()
        .mockReturnValueOnce(first)
        .mockReturnValueOnce(second),
      listMessages: vi.fn().mockResolvedValue({
        items: [], nextBeforeSequence: null, hasMore: false,
      }),
    })
    const workspace = useConversationWorkspace({
      userId: 'user-race',
      entry: CONVERSATION_ENTRY.GLOBAL_ASSISTANT,
      api: backend,
    })

    const openingA = workspace.openConversation('conv-a')
    const openingB = workspace.openConversation('conv-b')
    resolveSecond(conversation('conv-b'))
    await openingB
    resolveFirst(conversation('conv-a'))
    await openingA

    expect(workspace.activeConversationId.value).toBe('conv-b')
  })

  it('does not let a late detail request replace a newly created conversation', async () => {
    let resolveDetail
    const detail = new Promise((resolve) => { resolveDetail = resolve })
    const backend = api({
      getConversation: vi.fn().mockReturnValue(detail),
      createConversation: vi.fn().mockResolvedValue(conversation('conv-new')),
      listMessages: vi.fn().mockResolvedValue({
        items: [], nextBeforeSequence: null, hasMore: false,
      }),
    })
    const workspace = useConversationWorkspace({
      userId: 'user-create-race',
      entry: CONVERSATION_ENTRY.GLOBAL_ASSISTANT,
      api: backend,
    })

    const openingOld = workspace.openConversation('conv-old')
    await workspace.createConversation({ title: 'new' })
    resolveDetail(conversation('conv-old'))
    await openingOld

    expect(workspace.activeConversationId.value).toBe('conv-new')
  })

  it('does not reactivate a deleted conversation when an older detail request finishes late', async () => {
    let resolveDetail
    const detail = new Promise((resolve) => { resolveDetail = resolve })
    const backend = api({
      getConversation: vi.fn().mockReturnValue(detail),
      deleteConversation: vi.fn().mockResolvedValue({
        conversationId: 'conv-delete-race',
        deleted: true,
      }),
      listMessages: vi.fn().mockResolvedValue({
        items: [], nextBeforeSequence: null, hasMore: false,
      }),
    })
    const workspace = useConversationWorkspace({
      userId: 'user-delete-race',
      entry: CONVERSATION_ENTRY.EDITOR,
      api: backend,
    })
    workspace.session.activateConversation('conv-delete-race')

    const openingDeleted = workspace.openConversation('conv-delete-race')
    await workspace.deleteConversation('conv-delete-race')
    resolveDetail(conversation('conv-delete-race'))
    await openingDeleted

    expect(workspace.activeConversationId.value).toBeNull()
  })

  it('ignores an invalid preferred route id and restores the stored conversation', async () => {
    localStorage.setItem(
      'omnidoc:conversation:v1:user-route:editor:active',
      'conv-stored',
    )
    const backend = api({
      getConversation: vi.fn().mockResolvedValue(conversation('conv-stored')),
      listMessages: vi.fn().mockResolvedValue({
        items: [], nextBeforeSequence: null, hasMore: false,
      }),
    })
    const workspace = useConversationWorkspace({
      userId: 'user-route',
      entry: CONVERSATION_ENTRY.EDITOR,
      api: backend,
    })

    await workspace.initialize({ preferredConversationId: 'invalid id!' })

    expect(backend.getConversation).toHaveBeenCalledWith('conv-stored')
    expect(workspace.activeConversationId.value).toBe('conv-stored')
  })
})
