import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../api/conversation.js', () => ({
  conversationApi: {
    createConversation: vi.fn(),
  },
}))

const UUID_ONE = '11111111-1111-4111-8111-111111111111'
const UUID_TWO = '22222222-2222-4222-8222-222222222222'

const deferred = () => {
  let resolve
  let reject
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

describe('conversation session contract', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    vi.resetModules()
  })

  it('generates a UUID clientRequestId with browser cryptography', async () => {
    const module = await import('./useConversationSession.js')

    expect(module.createClientRequestId()).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    )
  })

  it('isolates active pointers and drafts by authenticated user and entry, including reload', async () => {
    const firstModule = await import('./useConversationSession.js')
    const userGlobal = firstModule.useConversationSession({
      userId: 'user-a',
      entry: firstModule.CONVERSATION_ENTRY.GLOBAL_ASSISTANT,
    })
    const userEditor = firstModule.useConversationSession({
      userId: 'user-a',
      entry: firstModule.CONVERSATION_ENTRY.EDITOR,
    })
    const otherUserGlobal = firstModule.useConversationSession({
      userId: 'user-b',
      entry: firstModule.CONVERSATION_ENTRY.GLOBAL_ASSISTANT,
    })

    userGlobal.activateConversation('conv-user-a-global')
    userGlobal.setDraft('global draft')

    expect(userEditor.activeConversationId.value).toBeNull()
    expect(userEditor.draft.value).toBe('')
    expect(otherUserGlobal.activeConversationId.value).toBeNull()

    vi.resetModules()
    const refreshedModule = await import('./useConversationSession.js')
    const refreshed = refreshedModule.useConversationSession({
      userId: 'user-a',
      entry: refreshedModule.CONVERSATION_ENTRY.GLOBAL_ASSISTANT,
    })

    expect(refreshed.activeConversationId.value).toBe('conv-user-a-global')
    expect(refreshed.draft.value).toBe('global draft')
  })

  it('switches only after create succeeds and preserves the draft while pending', async () => {
    const module = await import('./useConversationSession.js')
    const pending = deferred()
    const api = { createConversation: vi.fn(() => pending.promise) }
    const session = module.useConversationSession({
      userId: 'user-create-success',
      entry: module.CONVERSATION_ENTRY.GLOBAL_ASSISTANT,
      api,
    })
    session.activateConversation('conv-old')
    session.setDraft('保留中的草稿')

    const creating = session.createAndActivate({ title: 'new' })
    expect(session.activeConversationId.value).toBe('conv-old')
    expect(session.draft.value).toBe('保留中的草稿')
    expect(session.isCreating.value).toBe(true)

    pending.resolve({ conversationId: 'conv-new' })
    await creating

    expect(session.activeConversationId.value).toBe('conv-new')
    expect(session.draft.value).toBe('保留中的草稿')
    expect(session.isCreating.value).toBe(false)
  })

  it('keeps the old conversation and draft when create fails without inventing a fallback id', async () => {
    const module = await import('./useConversationSession.js')
    const api = { createConversation: vi.fn().mockRejectedValue(new Error('network unavailable')) }
    const session = module.useConversationSession({
      userId: 'user-create-failure',
      entry: module.CONVERSATION_ENTRY.EDITOR,
      api,
    })
    session.activateConversation('conv-stable')
    session.setDraft('不能丢失的草稿')

    await expect(session.createAndActivate()).rejects.toMatchObject({
      code: 'CONVERSATION_REQUEST_FAILED',
    })

    expect(session.activeConversationId.value).toBe('conv-stable')
    expect(session.draft.value).toBe('不能丢失的草稿')
    expect(localStorage.getItem('global_conv_id')).toBeNull()
    expect(Array.from({ length: localStorage.length }, (_, index) => localStorage.key(index)))
      .not.toEqual(expect.arrayContaining([expect.stringContaining('temp_')]))
    expect(session.activeConversationId.value).not.toBe('default_conv')
  })

  it('reuses a clientRequestId for retries and restores it within the browser session', async () => {
    const firstModule = await import('./useConversationSession.js')
    const idFactory = vi.fn().mockReturnValueOnce(UUID_ONE).mockReturnValueOnce(UUID_TWO)
    const first = firstModule.useConversationSession({
      userId: 'user-submit',
      entry: firstModule.CONVERSATION_ENTRY.GLOBAL_ASSISTANT,
      clientRequestIdFactory: idFactory,
    })
    first.activateConversation('conv-submit')
    first.setDraft('send this once')

    const initial = first.beginSubmission()
    first.markSubmissionFailed(new Error('retry later'))
    const retry = first.beginSubmission()

    expect(initial.clientRequestId).toBe(UUID_ONE)
    expect(retry.clientRequestId).toBe(UUID_ONE)
    expect(idFactory).toHaveBeenCalledOnce()

    vi.resetModules()
    const refreshedModule = await import('./useConversationSession.js')
    const refreshed = refreshedModule.useConversationSession({
      userId: 'user-submit',
      entry: refreshedModule.CONVERSATION_ENTRY.GLOBAL_ASSISTANT,
      clientRequestIdFactory: idFactory,
    })
    expect(refreshed.beginSubmission().clientRequestId).toBe(UUID_ONE)

    refreshed.markSubmissionSucceeded(UUID_ONE)
    expect(refreshed.draft.value).toBe('')
    expect(refreshed.pendingClientRequestId.value).toBeNull()

    refreshed.setDraft('a different request')
    expect(refreshed.beginSubmission().clientRequestId).toBe(UUID_TWO)
  })

  it('invalidates the pending request id when content or active conversation changes', async () => {
    const module = await import('./useConversationSession.js')
    const idFactory = vi.fn().mockReturnValueOnce(UUID_ONE).mockReturnValueOnce(UUID_TWO)
    const session = module.useConversationSession({
      userId: 'user-change',
      entry: module.CONVERSATION_ENTRY.EDITOR,
      clientRequestIdFactory: idFactory,
    })
    session.activateConversation('conv-one')
    session.setDraft('first content')
    expect(session.beginSubmission().clientRequestId).toBe(UUID_ONE)

    session.setDraft('changed content')
    expect(session.pendingClientRequestId.value).toBeNull()
    expect(session.beginSubmission().clientRequestId).toBe(UUID_TWO)

    session.activateConversation('conv-two')
    expect(session.pendingClientRequestId.value).toBeNull()
  })

  it('rejects unauthenticated scopes and legacy fallback ids', async () => {
    const module = await import('./useConversationSession.js')

    expect(() => module.useConversationSession({
      userId: '',
      entry: module.CONVERSATION_ENTRY.GLOBAL_ASSISTANT,
    })).toThrowError(/authenticated userId/)

    const session = module.useConversationSession({
      userId: 'user-invalid-id',
      entry: module.CONVERSATION_ENTRY.EDITOR,
    })
    expect(() => session.activateConversation('temp_123')).toThrowError(/conversationId/)
    expect(() => session.activateConversation('default_conv')).toThrowError(/conversationId/)
  })
})
