import { readonly, ref, shallowRef } from 'vue'
import { conversationApi } from '../api/conversation.js'
import {
  CONVERSATION_ERROR,
  ConversationApiError,
  assertClientRequestId,
  assertConversationId,
  normalizeConversationError,
} from '../api/conversationContract.js'

export const CONVERSATION_ENTRY = Object.freeze({
  GLOBAL_ASSISTANT: 'global-assistant',
  EDITOR: 'editor',
})

const sessions = new Map()

function requiredUserId(value) {
  if (typeof value !== 'string' || !value.trim()) {
    throw new ConversationApiError(
      CONVERSATION_ERROR.REQUEST_INVALID,
      'authenticated userId is required',
    )
  }
  return value.trim()
}

function requiredEntry(value) {
  if (!Object.values(CONVERSATION_ENTRY).includes(value)) {
    throw new ConversationApiError(
      CONVERSATION_ERROR.REQUEST_INVALID,
      'conversation entry is not supported',
    )
  }
  return value
}

function storage(name) {
  try {
    return globalThis[name] ?? null
  } catch {
    return null
  }
}

function read(storageObject, key) {
  try {
    return storageObject?.getItem(key) ?? null
  } catch {
    return null
  }
}

function write(storageObject, key, value) {
  try {
    if (!storageObject) return
    if (value === null || value === undefined || value === '') {
      storageObject.removeItem(key)
    } else {
      storageObject.setItem(key, value)
    }
  } catch {
    // In-memory state remains usable when browser storage is unavailable.
  }
}

function storageKeys(userId, entry) {
  const scope = `${encodeURIComponent(userId)}:${entry}`
  return Object.freeze({
    active: `omnidoc:conversation:v1:${scope}:active`,
    draft: `omnidoc:conversation:v1:${scope}:draft`,
    submission: `omnidoc:conversation:v1:${scope}:submission`,
  })
}

function restoreConversationId(local, key) {
  const value = read(local, key)
  if (!value) return null
  try {
    return assertConversationId(value)
  } catch {
    write(local, key, null)
    return null
  }
}

function restoreSubmission(session, key, conversationId, draft) {
  const value = read(session, key)
  if (!value) return null
  try {
    const parsed = JSON.parse(value)
    if (parsed.conversationId !== conversationId || parsed.draft !== draft) {
      write(session, key, null)
      return null
    }
    return assertClientRequestId(parsed.clientRequestId)
  } catch {
    write(session, key, null)
    return null
  }
}

function secureUuid() {
  const cryptoObject = globalThis.crypto
  if (typeof cryptoObject?.randomUUID === 'function') {
    return assertClientRequestId(cryptoObject.randomUUID())
  }
  if (typeof cryptoObject?.getRandomValues !== 'function') {
    throw new ConversationApiError(
      CONVERSATION_ERROR.REQUEST_FAILED,
      'secure UUID generation is unavailable',
    )
  }

  const bytes = new Uint8Array(16)
  cryptoObject.getRandomValues(bytes)
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('')
  return assertClientRequestId(
    `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`,
  )
}

export function createClientRequestId() {
  return secureUuid()
}

export function useConversationSession({
  userId,
  entry,
  api = conversationApi,
  clientRequestIdFactory = createClientRequestId,
} = {}) {
  const normalizedUserId = requiredUserId(userId)
  const normalizedEntry = requiredEntry(entry)
  const sessionKey = `${normalizedUserId}\n${normalizedEntry}`
  if (sessions.has(sessionKey)) return sessions.get(sessionKey)

  const local = storage('localStorage')
  const tab = storage('sessionStorage')
  const keys = storageKeys(normalizedUserId, normalizedEntry)
  const initialActiveId = restoreConversationId(local, keys.active)
  const initialDraft = read(tab, keys.draft) ?? ''

  const activeConversationId = ref(initialActiveId)
  const draft = ref(initialDraft)
  const pendingClientRequestId = ref(
    restoreSubmission(tab, keys.submission, initialActiveId, initialDraft),
  )
  const isCreating = ref(false)
  const lastError = shallowRef(null)
  let createPromise = null

  const clearPendingSubmission = () => {
    pendingClientRequestId.value = null
    write(tab, keys.submission, null)
  }

  const persistSubmission = () => {
    if (!pendingClientRequestId.value) {
      write(tab, keys.submission, null)
      return
    }
    write(tab, keys.submission, JSON.stringify({
      conversationId: activeConversationId.value,
      draft: draft.value,
      clientRequestId: pendingClientRequestId.value,
    }))
  }

  const activateConversation = (conversationOrId) => {
    const conversationId = assertConversationId(
      typeof conversationOrId === 'string'
        ? conversationOrId
        : conversationOrId?.conversationId,
    )
    if (conversationId !== activeConversationId.value) clearPendingSubmission()
    activeConversationId.value = conversationId
    write(local, keys.active, conversationId)
    lastError.value = null
    return conversationId
  }

  const setDraft = (value) => {
    const nextDraft = value === null || value === undefined ? '' : String(value)
    if (nextDraft !== draft.value) clearPendingSubmission()
    draft.value = nextDraft
    write(tab, keys.draft, nextDraft)
    return nextDraft
  }

  const clearActiveConversation = ({ clearDraft = false } = {}) => {
    activeConversationId.value = null
    write(local, keys.active, null)
    clearPendingSubmission()
    lastError.value = null
    if (clearDraft) setDraft('')
  }

  const createAndActivate = (options = {}) => {
    if (createPromise) return createPromise
    isCreating.value = true
    lastError.value = null

    createPromise = Promise.resolve()
      .then(() => api.createConversation(options))
      .then((conversation) => {
        activateConversation(conversation)
        return conversation
      })
      .catch((error) => {
        const normalized = normalizeConversationError(error)
        lastError.value = normalized
        throw normalized
      })
      .finally(() => {
        isCreating.value = false
        createPromise = null
      })

    return createPromise
  }

  const beginSubmission = () => {
    if (!activeConversationId.value) {
      throw new ConversationApiError(
        CONVERSATION_ERROR.REQUEST_INVALID,
        'an active conversation is required before sending',
      )
    }
    if (!draft.value.trim()) {
      throw new ConversationApiError(
        CONVERSATION_ERROR.REQUEST_INVALID,
        'a non-empty draft is required before sending',
      )
    }
    if (!pendingClientRequestId.value) {
      pendingClientRequestId.value = assertClientRequestId(clientRequestIdFactory())
      persistSubmission()
    }
    return Object.freeze({
      conversationId: activeConversationId.value,
      clientRequestId: pendingClientRequestId.value,
      content: draft.value,
    })
  }

  const markSubmissionFailed = (error) => {
    lastError.value = normalizeConversationError(error)
    persistSubmission()
    return lastError.value
  }

  const markSubmissionSucceeded = (clientRequestId) => {
    const normalizedRequestId = assertClientRequestId(clientRequestId)
    if (normalizedRequestId !== pendingClientRequestId.value) {
      throw new ConversationApiError(
        CONVERSATION_ERROR.CONFLICT,
        'clientRequestId does not match the pending submission',
      )
    }
    clearPendingSubmission()
    setDraft('')
    lastError.value = null
  }

  const value = Object.freeze({
    userId: normalizedUserId,
    entry: normalizedEntry,
    activeConversationId: readonly(activeConversationId),
    draft: readonly(draft),
    pendingClientRequestId: readonly(pendingClientRequestId),
    isCreating: readonly(isCreating),
    lastError: readonly(lastError),
    activateConversation,
    clearActiveConversation,
    setDraft,
    createAndActivate,
    beginSubmission,
    markSubmissionFailed,
    markSubmissionSucceeded,
    cancelPendingSubmission: clearPendingSubmission,
  })
  sessions.set(sessionKey, value)
  return value
}
