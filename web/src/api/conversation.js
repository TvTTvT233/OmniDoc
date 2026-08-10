import request from '../utils/request'
import {
  CONVERSATION_ERROR,
  CONVERSATION_STATUS,
  ConversationApiError,
  assertConversationId,
  normalizeConversation,
  normalizeConversationError,
  normalizeConversationMessagePage,
  normalizeConversationPage,
  normalizeDeleteConversationResult,
} from './conversationContract.js'

const ROOT = '/ai/conversations'

function responseData(response) {
  return response && Object.prototype.hasOwnProperty.call(response, 'data')
    ? response.data
    : response
}

async function execute(operation, normalize) {
  try {
    return normalize(responseData(await operation()))
  } catch (error) {
    throw normalizeConversationError(error)
  }
}

function conversationPath(conversationId, suffix = '') {
  return `${ROOT}/${encodeURIComponent(assertConversationId(conversationId))}${suffix}`
}

function expectedConversation(value, conversationId) {
  const normalized = normalizeConversation(value)
  if (normalized.conversationId !== conversationId) {
    throw new ConversationApiError(
      CONVERSATION_ERROR.RESPONSE_INVALID,
      'conversation response does not match the requested id',
    )
  }
  return normalized
}

function expectedMessagePage(value, conversationId) {
  const normalized = normalizeConversationMessagePage(value)
  if (normalized.items.some((message) => message.conversationId !== conversationId)) {
    throw new ConversationApiError(
      CONVERSATION_ERROR.RESPONSE_INVALID,
      'conversation message response crosses the requested boundary',
    )
  }
  return normalized
}

function optionalLimit(value) {
  if (value === undefined || value === null) return undefined
  if (!Number.isSafeInteger(value) || value < 1) {
    throw new ConversationApiError(
      CONVERSATION_ERROR.REQUEST_INVALID,
      'limit must be a positive integer',
    )
  }
  return value
}

function optionalBeforeSequence(value) {
  if (value === undefined || value === null) return undefined
  if (!Number.isSafeInteger(value) || value < 1) {
    throw new ConversationApiError(
      CONVERSATION_ERROR.REQUEST_INVALID,
      'beforeSequence must be a positive integer',
    )
  }
  return value
}

function optionalStatus(value) {
  if (value === undefined || value === null || value === '') return undefined
  const status = String(value).toUpperCase()
  if (!Object.values(CONVERSATION_STATUS).includes(status)) {
    throw new ConversationApiError(
      CONVERSATION_ERROR.REQUEST_INVALID,
      'status must be ACTIVE or ARCHIVED',
    )
  }
  return status
}

function optionalCursor(value) {
  if (value === undefined || value === null || value === '') return undefined
  if (typeof value !== 'string') {
    throw new ConversationApiError(
      CONVERSATION_ERROR.REQUEST_INVALID,
      'cursor must be a string',
    )
  }
  return value
}

function createBody(options) {
  const title = options && typeof options.title === 'string' ? options.title.trim() : ''
  if (title.length > 255) {
    throw new ConversationApiError(
      CONVERSATION_ERROR.REQUEST_INVALID,
      'title exceeds 255 characters',
    )
  }
  return title ? { title } : {}
}

export const conversationApi = Object.freeze({
  async createConversation(options = {}) {
    return execute(
      () => request.post(ROOT, createBody(options)),
      normalizeConversation,
    )
  },

  async listConversations({ status, cursor, limit } = {}) {
    const params = {
      status: optionalStatus(status),
      cursor: optionalCursor(cursor),
      limit: optionalLimit(limit),
    }
    Object.keys(params).forEach((key) => params[key] === undefined && delete params[key])
    return execute(
      () => request.get(ROOT, { params }),
      normalizeConversationPage,
    )
  },

  async getConversation(conversationId) {
    const normalizedId = assertConversationId(conversationId)
    return execute(
      () => request.get(conversationPath(normalizedId)),
      (value) => expectedConversation(value, normalizedId),
    )
  },

  async listMessages(conversationId, { beforeSequence, limit } = {}) {
    const normalizedId = assertConversationId(conversationId)
    const params = {
      beforeSequence: optionalBeforeSequence(beforeSequence),
      limit: optionalLimit(limit),
    }
    Object.keys(params).forEach((key) => params[key] === undefined && delete params[key])
    return execute(
      () => request.get(conversationPath(normalizedId, '/messages'), { params }),
      (value) => expectedMessagePage(value, normalizedId),
    )
  },

  async archiveConversation(conversationId) {
    const normalizedId = assertConversationId(conversationId)
    return execute(
      () => request.post(conversationPath(normalizedId, '/archive')),
      (value) => expectedConversation(value, normalizedId),
    )
  },

  async restoreConversation(conversationId) {
    const normalizedId = assertConversationId(conversationId)
    return execute(
      () => request.post(conversationPath(normalizedId, '/restore')),
      (value) => expectedConversation(value, normalizedId),
    )
  },

  async deleteConversation(conversationId) {
    const normalizedId = assertConversationId(conversationId)
    return execute(
      () => request.delete(conversationPath(normalizedId)),
      (value) => normalizeDeleteConversationResult(value, normalizedId),
    )
  },
})
