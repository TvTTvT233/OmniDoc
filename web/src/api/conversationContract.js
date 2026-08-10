export const CONVERSATION_STATUS = Object.freeze({
  ACTIVE: 'ACTIVE',
  ARCHIVED: 'ARCHIVED',
})

export const CONVERSATION_ROLE = Object.freeze({
  USER: 'USER',
  ASSISTANT: 'ASSISTANT',
  TOOL: 'TOOL',
})

export const CONVERSATION_MESSAGE_TYPE = Object.freeze({
  TEXT: 'TEXT',
  EVENT: 'EVENT',
})

export const CONVERSATION_ERROR = Object.freeze({
  NOT_FOUND: 'CONVERSATION_NOT_FOUND',
  ARCHIVED: 'CONVERSATION_ARCHIVED',
  INVALID_CURSOR: 'INVALID_CONVERSATION_CURSOR',
  CONFLICT: 'CONVERSATION_CONFLICT',
  CONTEXT_TOO_LARGE: 'MEMORY_CONTEXT_TOO_LARGE',
  RUNTIME_UNAVAILABLE: 'RUNTIME_STATE_UNAVAILABLE',
  REQUEST_INVALID: 'CONVERSATION_REQUEST_INVALID',
  RESPONSE_INVALID: 'CONVERSATION_RESPONSE_INVALID',
  REQUEST_FAILED: 'CONVERSATION_REQUEST_FAILED',
})

const PUBLIC_ERROR_CODES = new Set([
  CONVERSATION_ERROR.NOT_FOUND,
  CONVERSATION_ERROR.ARCHIVED,
  CONVERSATION_ERROR.INVALID_CURSOR,
  CONVERSATION_ERROR.CONFLICT,
  CONVERSATION_ERROR.CONTEXT_TOO_LARGE,
  CONVERSATION_ERROR.RUNTIME_UNAVAILABLE,
])

const CONVERSATION_ID_PATTERN = /^[A-Za-z0-9_-]{1,64}$/
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

export class ConversationApiError extends Error {
  constructor(code, message, { status = null, retryable = false, cause = null } = {}) {
    super(message)
    this.name = 'ConversationApiError'
    this.code = code
    this.status = status
    this.retryable = retryable
    this.cause = cause
  }
}

function invalidResponse(message) {
  return new ConversationApiError(CONVERSATION_ERROR.RESPONSE_INVALID, message)
}

function requireRecord(value, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw invalidResponse(`${label} must be an object`)
  }
  return value
}

function requireString(value, label, { allowEmpty = false } = {}) {
  if (typeof value !== 'string' || (!allowEmpty && !value.trim())) {
    throw invalidResponse(`${label} must be a non-empty string`)
  }
  return value
}

function requirePositiveInteger(value, label) {
  if (!Number.isSafeInteger(value) || value < 1) {
    throw invalidResponse(`${label} must be a positive integer`)
  }
  return value
}

function optionalString(value, label) {
  if (value === null || value === undefined) return null
  return requireString(value, label, { allowEmpty: true })
}

function enumValue(value, allowed, label) {
  const normalized = typeof value === 'string' ? value.toUpperCase() : value
  if (!allowed.includes(normalized)) {
    throw invalidResponse(`${label} is not supported`)
  }
  return normalized
}

function responseConversationId(value, label) {
  try {
    return assertConversationId(value)
  } catch {
    throw invalidResponse(`${label} is invalid`)
  }
}

function normalizePublicMetadata(metadata) {
  if (!metadata || typeof metadata !== 'object' || Array.isArray(metadata)) return Object.freeze({})

  const allowed = {}
  for (const key of ['displayable', 'eventType', 'label', 'status', 'toolName']) {
    const value = metadata[key]
    if (typeof value === 'string' || typeof value === 'boolean') {
      allowed[key] = value
    }
  }
  return Object.freeze(allowed)
}

export function assertConversationId(value) {
  if (typeof value !== 'string'
      || !CONVERSATION_ID_PATTERN.test(value)
      || value === 'default_conv'
      || value.startsWith('temp_')) {
    throw new ConversationApiError(
      CONVERSATION_ERROR.REQUEST_INVALID,
      'conversationId is invalid',
    )
  }
  return value
}

export function assertClientRequestId(value) {
  if (typeof value !== 'string' || !UUID_PATTERN.test(value)) {
    throw new ConversationApiError(
      CONVERSATION_ERROR.REQUEST_INVALID,
      'clientRequestId must be a UUID',
    )
  }
  return value
}

export function normalizeConversation(value) {
  const source = requireRecord(value, 'conversation')
  const status = enumValue(
    source.status,
    Object.values(CONVERSATION_STATUS),
    'conversation.status',
  )

  return Object.freeze({
    conversationId: responseConversationId(source.conversationId, 'conversation.conversationId'),
    title: optionalString(source.title, 'conversation.title'),
    status,
    createdAt: requireString(source.createdAt, 'conversation.createdAt'),
    updatedAt: requireString(source.updatedAt, 'conversation.updatedAt'),
  })
}

export function normalizeConversationPage(value) {
  const source = requireRecord(value, 'conversation page')
  if (!Array.isArray(source.items) || typeof source.hasMore !== 'boolean') {
    throw invalidResponse('conversation page items/hasMore are invalid')
  }

  return Object.freeze({
    items: Object.freeze(source.items.map(normalizeConversation)),
    nextCursor: optionalString(source.nextCursor, 'conversation page nextCursor'),
    hasMore: source.hasMore,
  })
}

export function normalizeConversationMessage(value) {
  const source = requireRecord(value, 'conversation message')
  const role = enumValue(
    source.role,
    Object.values(CONVERSATION_ROLE),
    'conversation message role',
  )
  const messageType = enumValue(
    source.messageType,
    Object.values(CONVERSATION_MESSAGE_TYPE),
    'conversation message type',
  )
  const metadata = normalizePublicMetadata(source.metadata)

  if ((role === CONVERSATION_ROLE.USER || role === CONVERSATION_ROLE.ASSISTANT)
      && messageType !== CONVERSATION_MESSAGE_TYPE.TEXT) {
    throw invalidResponse('user and assistant messages must be public text')
  }
  if (role === CONVERSATION_ROLE.TOOL
      && (messageType !== CONVERSATION_MESSAGE_TYPE.EVENT || metadata.displayable !== true)) {
    throw invalidResponse('tool messages must be explicitly displayable events')
  }

  return Object.freeze({
    messageId: requireString(source.messageId, 'conversation message id'),
    conversationId: responseConversationId(
      source.conversationId,
      'conversation message conversationId',
    ),
    sequence: requirePositiveInteger(source.sequence, 'conversation message sequence'),
    role,
    messageType,
    content: requireString(source.content, 'conversation message content', { allowEmpty: true }),
    traceId: optionalString(source.traceId, 'conversation message traceId'),
    toolCallId: optionalString(source.toolCallId, 'conversation message toolCallId'),
    metadata,
    createdAt: requireString(source.createdAt, 'conversation message createdAt'),
  })
}

export function normalizeConversationMessagePage(value) {
  const source = requireRecord(value, 'conversation message page')
  if (!Array.isArray(source.items) || typeof source.hasMore !== 'boolean') {
    throw invalidResponse('conversation message page items/hasMore are invalid')
  }

  const items = source.items.map(normalizeConversationMessage)
  for (let index = 1; index < items.length; index += 1) {
    if (items[index - 1].sequence >= items[index].sequence) {
      throw invalidResponse('conversation messages must be strictly ordered by sequence')
    }
  }

  const nextBeforeSequence = source.nextBeforeSequence === null
      || source.nextBeforeSequence === undefined
    ? null
    : requirePositiveInteger(source.nextBeforeSequence, 'message page nextBeforeSequence')

  return Object.freeze({
    items: Object.freeze(items),
    nextBeforeSequence,
    hasMore: source.hasMore,
  })
}

export function normalizeDeleteConversationResult(value, conversationId) {
  const deleted = value === true
    || (value && typeof value === 'object' && value.deleted === true)

  if (!deleted) {
    throw invalidResponse('delete conversation response did not confirm deletion')
  }

  return Object.freeze({ conversationId: assertConversationId(conversationId), deleted: true })
}

export function normalizeConversationError(error) {
  if (error instanceof ConversationApiError) return error

  const status = Number.isInteger(error?.response?.status) ? error.response.status : null
  const responseData = error?.response?.data
  const candidateCode = responseData?.errorCode ?? responseData?.code ?? error?.code
  const code = PUBLIC_ERROR_CODES.has(candidateCode)
    ? candidateCode
    : CONVERSATION_ERROR.REQUEST_FAILED
  const message = typeof responseData?.message === 'string' && responseData.message.trim()
    ? responseData.message
    : (typeof error?.message === 'string' && error.message.trim()
      ? error.message
      : 'Conversation request failed')
  const retryable = status === null || status >= 500 || code === CONVERSATION_ERROR.RUNTIME_UNAVAILABLE

  return new ConversationApiError(code, message, { status, retryable, cause: error })
}
