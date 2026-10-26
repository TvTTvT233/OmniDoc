import { readonly, ref, shallowRef } from 'vue'
import { conversationApi } from '../api/conversation.js'
import {
  CONVERSATION_ERROR,
  CONVERSATION_STATUS,
  ConversationApiError,
  assertConversationId,
  normalizeConversationError,
} from '../api/conversationContract.js'
import { useConversationSession } from './useConversationSession.js'

const workspaces = new Map()
const PAGE_SIZE = 50
const MAX_EMPTY_MESSAGE_PAGES = 100

function appendUniqueConversations(current, incoming) {
  const byId = new Map(current.map((item) => [item.conversationId, item]))
  incoming.forEach((item) => byId.set(item.conversationId, item))
  return Array.from(byId.values())
}

function mergeMessagePage(current, older) {
  const bySequence = new Map()
  older.forEach((message) => bySequence.set(message.sequence, message))
  current.forEach((message) => bySequence.set(message.sequence, message))
  return Array.from(bySequence.values()).sort((left, right) => left.sequence - right.sequence)
}

export function toConversationChatMessage(message) {
  return Object.freeze({
    id: message.messageId,
    sequence: message.sequence,
    role: message.role === 'USER' ? 'user' : 'ai',
    text: message.content,
    references: message.references || [],
    messageType: message.messageType,
    isToolEvent: message.role === 'TOOL',
    createdAt: message.createdAt,
  })
}

export function useConversationWorkspace({ userId, entry, api = conversationApi } = {}) {
  const session = useConversationSession({ userId, entry, api })
  const workspaceKey = `${session.userId}\n${session.entry}`
  if (workspaces.has(workspaceKey)) return workspaces.get(workspaceKey)

  const activeConversation = shallowRef(null)
  const activeConversations = ref([])
  const archivedConversations = ref([])
  const activeCursor = ref(null)
  const archivedCursor = ref(null)
  const activeHasMore = ref(false)
  const archivedHasMore = ref(false)
  const messages = ref([])
  const nextBeforeSequence = ref(null)
  const hasOlderMessages = ref(false)
  const isLoadingConversations = ref(false)
  const isLoadingMessages = ref(false)
  const lastError = shallowRef(null)
  let messageLoadGeneration = 0
  let activationGeneration = 0
  let conversationLoadCount = 0
  let messageLoadCount = 0
  const conversationListGeneration = {
    [CONVERSATION_STATUS.ACTIVE]: 0,
    [CONVERSATION_STATUS.ARCHIVED]: 0,
  }

  const setError = (error) => {
    const normalized = normalizeConversationError(error)
    lastError.value = normalized
    return normalized
  }

  const loadConversationPage = async (status, { reset = false } = {}) => {
    const archived = status === CONVERSATION_STATUS.ARCHIVED
    const cursorRef = archived ? archivedCursor : activeCursor
    const hasMoreRef = archived ? archivedHasMore : activeHasMore
    const itemsRef = archived ? archivedConversations : activeConversations
    if (!reset && !hasMoreRef.value) return itemsRef.value

    const generation = ++conversationListGeneration[status]
    conversationLoadCount += 1
    isLoadingConversations.value = true
    try {
      const page = await api.listConversations({
        status,
        cursor: reset ? undefined : cursorRef.value,
        limit: PAGE_SIZE,
      })
      if (generation !== conversationListGeneration[status]) return itemsRef.value
      itemsRef.value = reset
        ? [...page.items]
        : appendUniqueConversations(itemsRef.value, page.items)
      cursorRef.value = page.nextCursor
      hasMoreRef.value = page.hasMore
      return itemsRef.value
    } finally {
      conversationLoadCount = Math.max(0, conversationLoadCount - 1)
      isLoadingConversations.value = conversationLoadCount > 0
    }
  }

  const refreshConversationLists = async () => {
    try {
      await Promise.all([
        loadConversationPage(CONVERSATION_STATUS.ACTIVE, { reset: true }),
        loadConversationPage(CONVERSATION_STATUS.ARCHIVED, { reset: true }),
      ])
      lastError.value = null
    } catch (error) {
      throw setError(error)
    }
  }

  const loadMessages = async ({ reset = false } = {}) => {
    const conversationId = session.activeConversationId.value
    if (!conversationId) {
      messages.value = []
      nextBeforeSequence.value = null
      hasOlderMessages.value = false
      return []
    }
    if (!reset && !hasOlderMessages.value) return messages.value

    const generation = reset ? ++messageLoadGeneration : messageLoadGeneration
    let beforeSequence = reset ? undefined : nextBeforeSequence.value
    let page
    let emptyPages = 0
    messageLoadCount += 1
    isLoadingMessages.value = true
    try {
      do {
        const requestedBoundary = beforeSequence
        page = await api.listMessages(conversationId, {
          beforeSequence,
          limit: PAGE_SIZE,
        })
        beforeSequence = page.nextBeforeSequence
        if (page.items.length > 0 || !page.hasMore) break
        if (!beforeSequence || beforeSequence === requestedBoundary) {
          throw new ConversationApiError(
            CONVERSATION_ERROR.RESPONSE_INVALID,
            'message pagination did not advance',
          )
        }
        emptyPages += 1
      } while (emptyPages < MAX_EMPTY_MESSAGE_PAGES)

      if (emptyPages >= MAX_EMPTY_MESSAGE_PAGES && page?.items.length === 0 && page.hasMore) {
        throw new ConversationApiError(
          CONVERSATION_ERROR.RESPONSE_INVALID,
          'message pagination exceeded the empty-page safety limit',
        )
      }
      if (generation !== messageLoadGeneration
          || conversationId !== session.activeConversationId.value) {
        return messages.value
      }

      messages.value = reset
        ? [...page.items]
        : mergeMessagePage(messages.value, page.items)
      nextBeforeSequence.value = page.nextBeforeSequence
      hasOlderMessages.value = page.hasMore
      lastError.value = null
      return messages.value
    } catch (error) {
      throw setError(error)
    } finally {
      messageLoadCount = Math.max(0, messageLoadCount - 1)
      isLoadingMessages.value = messageLoadCount > 0
    }
  }

  const openConversation = async (conversationOrId) => {
    const conversationId = assertConversationId(
      typeof conversationOrId === 'string'
        ? conversationOrId
        : conversationOrId?.conversationId,
    )
    const generation = ++activationGeneration
    try {
      const conversation = await api.getConversation(conversationId)
      if (generation !== activationGeneration) return null
      session.activateConversation(conversation)
      activeConversation.value = conversation
      messages.value = []
      nextBeforeSequence.value = null
      hasOlderMessages.value = false
      await loadMessages({ reset: true })
      if (generation !== activationGeneration) return null
      return conversation
    } catch (error) {
      throw setError(error)
    }
  }

  const initialize = async ({ preferredConversationId } = {}) => {
    try {
      await refreshConversationLists()
    } catch {
      // A transient list failure must not discard a restorable active pointer.
    }
    const storedId = session.activeConversationId.value
    const candidates = []
    if (preferredConversationId) {
      try {
        candidates.push(assertConversationId(preferredConversationId))
      } catch (error) {
        setError(error)
      }
    }
    if (storedId && !candidates.includes(storedId)) candidates.push(storedId)
    for (const conversationId of candidates) {
      try {
        return await openConversation(conversationId)
      } catch (error) {
        if (error.code !== CONVERSATION_ERROR.NOT_FOUND) return null
        if (conversationId === storedId) session.clearActiveConversation()
      }
    }
    activeConversation.value = null
    messages.value = []
    nextBeforeSequence.value = null
    hasOlderMessages.value = false
    return null
  }

  const createConversation = async (options = {}) => {
    const generation = ++activationGeneration
    try {
      const conversation = await api.createConversation(options)
      if (generation !== activationGeneration) {
        await refreshConversationLists().catch(() => {})
        return null
      }
      session.activateConversation(conversation)
      activeConversation.value = conversation
      messages.value = []
      nextBeforeSequence.value = null
      hasOlderMessages.value = false
      await refreshConversationLists().catch(() => {})
      return conversation
    } catch (error) {
      throw setError(error)
    }
  }

  const archiveConversation = async (conversationId = session.activeConversationId.value) => {
    activationGeneration += 1
    try {
      const archived = await api.archiveConversation(assertConversationId(conversationId))
      if (archived.conversationId === session.activeConversationId.value) {
        activeConversation.value = archived
      }
      await refreshConversationLists().catch(() => {})
      return archived
    } catch (error) {
      throw setError(error)
    }
  }

  const restoreConversation = async (conversationId = session.activeConversationId.value) => {
    activationGeneration += 1
    try {
      const restored = await api.restoreConversation(assertConversationId(conversationId))
      if (restored.conversationId === session.activeConversationId.value) {
        activeConversation.value = restored
      }
      await refreshConversationLists().catch(() => {})
      return restored
    } catch (error) {
      throw setError(error)
    }
  }

  const deleteConversation = async (conversationId = session.activeConversationId.value) => {
    const normalizedId = assertConversationId(conversationId)
    activationGeneration += 1
    try {
      const deleted = await api.deleteConversation(normalizedId)
      if (normalizedId === session.activeConversationId.value) {
        session.clearActiveConversation({ clearDraft: true })
        activeConversation.value = null
        messages.value = []
        nextBeforeSequence.value = null
        hasOlderMessages.value = false
        messageLoadGeneration += 1
      }
      activeConversations.value = activeConversations.value.filter(
        (item) => item.conversationId !== normalizedId,
      )
      archivedConversations.value = archivedConversations.value.filter(
        (item) => item.conversationId !== normalizedId,
      )
      return deleted
    } catch (error) {
      throw setError(error)
    }
  }

  const invalidateActiveConversation = ({ clearDraft = false } = {}) => {
    activationGeneration += 1
    session.clearActiveConversation({ clearDraft })
    activeConversation.value = null
    messages.value = []
    nextBeforeSequence.value = null
    hasOlderMessages.value = false
    messageLoadGeneration += 1
  }

  const beginSubmission = () => {
    if (activeConversation.value?.status === CONVERSATION_STATUS.ARCHIVED) {
      throw new ConversationApiError(
        CONVERSATION_ERROR.ARCHIVED,
        'archived conversations must be restored before sending',
      )
    }
    return session.beginSubmission()
  }

  const markSubmissionFailed = (error) => session.markSubmissionFailed(setError(error))

  const completeSubmission = async (clientRequestId) => {
    session.markSubmissionSucceeded(clientRequestId)
    const results = await Promise.allSettled([
      loadMessages({ reset: true }),
      refreshConversationLists(),
    ])
    const failed = results.find((result) => result.status === 'rejected')
    if (failed) setError(failed.reason)
    return Object.freeze({ historyReloadFailed: Boolean(failed) })
  }

  const value = Object.freeze({
    userId: session.userId,
    entry: session.entry,
    session,
    activeConversationId: session.activeConversationId,
    activeConversation: readonly(activeConversation),
    activeConversations: readonly(activeConversations),
    archivedConversations: readonly(archivedConversations),
    activeHasMore: readonly(activeHasMore),
    archivedHasMore: readonly(archivedHasMore),
    messages: readonly(messages),
    nextBeforeSequence: readonly(nextBeforeSequence),
    hasOlderMessages: readonly(hasOlderMessages),
    isLoadingConversations: readonly(isLoadingConversations),
    isLoadingMessages: readonly(isLoadingMessages),
    lastError: readonly(lastError),
    initialize,
    refreshConversationLists,
    loadMoreActiveConversations: () => loadConversationPage(CONVERSATION_STATUS.ACTIVE),
    loadMoreArchivedConversations: () => loadConversationPage(CONVERSATION_STATUS.ARCHIVED),
    openConversation,
    createConversation,
    archiveConversation,
    restoreConversation,
    deleteConversation,
    invalidateActiveConversation,
    loadOlderMessages: () => loadMessages(),
    reloadMessages: () => loadMessages({ reset: true }),
    setDraft: session.setDraft,
    beginSubmission,
    markSubmissionFailed,
    completeSubmission,
  })
  workspaces.set(workspaceKey, value)
  return value
}
