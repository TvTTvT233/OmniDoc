import { beforeEach, describe, expect, it, vi } from 'vitest'

const request = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  delete: vi.fn(),
}))

vi.mock('../utils/request', () => ({ default: request }))

import { conversationApi } from './conversation.js'
import {
  CONVERSATION_ERROR,
  ConversationApiError,
} from './conversationContract.js'

const conversation = (overrides = {}) => ({
  conversationId: 'conv-contract-1',
  title: '契约会话',
  status: 'ACTIVE',
  createdAt: '2026-08-11T00:00:00Z',
  updatedAt: '2026-08-11T00:01:00Z',
  ...overrides,
})

describe('structured conversation API contract', () => {
  beforeEach(() => {
    request.get.mockReset()
    request.post.mockReset()
    request.delete.mockReset()
  })

  it('creates without accepting client user ownership and normalizes the DTO', async () => {
    request.post.mockResolvedValue({ data: conversation() })

    const result = await conversationApi.createConversation({
      title: '  契约会话  ',
      userId: 'forged-user',
    })

    expect(request.post).toHaveBeenCalledWith('/ai/conversations', { title: '契约会话' })
    expect(result).toEqual(conversation())
    expect(result).not.toHaveProperty('userId')
  })

  it('lists conversations with stable cursor parameters and normalized page fields', async () => {
    request.get.mockResolvedValue({
      data: { items: [conversation()], nextCursor: 'cursor-2', hasMore: true },
    })

    const result = await conversationApi.listConversations({
      status: 'active',
      cursor: 'cursor-1',
      limit: 20,
      userId: 'forged-user',
    })

    expect(request.get).toHaveBeenCalledWith('/ai/conversations', {
      params: { status: 'ACTIVE', cursor: 'cursor-1', limit: 20 },
    })
    expect(result.nextCursor).toBe('cursor-2')
    expect(result.items).toHaveLength(1)
  })

  it('normalizes ordered public messages and strips non-display metadata', async () => {
    request.get.mockResolvedValue({
      data: {
        items: [
          {
            messageId: 'msg-1',
            conversationId: 'conv-contract-1',
            sequence: 1,
            role: 'USER',
            messageType: 'TEXT',
            content: 'hello',
            traceId: null,
            toolCallId: null,
            metadata: { internal: 'drop-me' },
            createdAt: '2026-08-11T00:00:01Z',
          },
          {
            messageId: 'msg-2',
            conversationId: 'conv-contract-1',
            sequence: 2,
            role: 'TOOL',
            messageType: 'EVENT',
            content: '检索完成',
            traceId: 'trace-1',
            toolCallId: 'tool-call-1',
            metadata: {
              displayable: true,
              eventType: 'tool-completed',
              approvalToken: 'must-not-cross-the-boundary',
            },
            createdAt: '2026-08-11T00:00:02Z',
          },
        ],
        nextBeforeSequence: 1,
        hasMore: true,
      },
    })

    const result = await conversationApi.listMessages('conv-contract-1', {
      beforeSequence: 30,
      limit: 10,
    })

    expect(request.get).toHaveBeenCalledWith('/ai/conversations/conv-contract-1/messages', {
      params: { beforeSequence: 30, limit: 10 },
    })
    expect(result.items.map((item) => item.sequence)).toEqual([1, 2])
    expect(result.items[1].metadata).toEqual({
      displayable: true,
      eventType: 'tool-completed',
    })
    expect(JSON.stringify(result)).not.toContain('approvalToken')
  })

  it('uses distinct detail, archive, restore and permanent delete endpoints', async () => {
    request.get.mockResolvedValueOnce({ data: conversation() })
    request.post
      .mockResolvedValueOnce({ data: conversation({ status: 'ARCHIVED' }) })
      .mockResolvedValueOnce({ data: conversation({ status: 'ACTIVE' }) })
    request.delete.mockResolvedValueOnce({ data: { deleted: true } })

    await conversationApi.getConversation('conv-contract-1')
    await conversationApi.archiveConversation('conv-contract-1')
    await conversationApi.restoreConversation('conv-contract-1')
    const deleted = await conversationApi.deleteConversation('conv-contract-1')

    expect(request.get).toHaveBeenCalledWith('/ai/conversations/conv-contract-1')
    expect(request.post).toHaveBeenNthCalledWith(1, '/ai/conversations/conv-contract-1/archive')
    expect(request.post).toHaveBeenNthCalledWith(2, '/ai/conversations/conv-contract-1/restore')
    expect(request.delete).toHaveBeenCalledWith('/ai/conversations/conv-contract-1')
    expect(deleted).toEqual({ conversationId: 'conv-contract-1', deleted: true })
  })

  it('normalizes stable backend errors without converting ownership into a separate code', async () => {
    request.get.mockRejectedValue({
      response: {
        status: 404,
        data: {
          code: 'CONVERSATION_NOT_FOUND',
          message: 'Conversation is unavailable',
        },
      },
    })

    await expect(conversationApi.getConversation('conv-contract-1')).rejects.toMatchObject({
      name: 'ConversationApiError',
      code: CONVERSATION_ERROR.NOT_FOUND,
      status: 404,
      retryable: false,
    })
  })

  it('rejects a response that crosses the requested conversation boundary', async () => {
    request.get.mockResolvedValue({
      data: conversation({ conversationId: 'conv-other' }),
    })

    await expect(conversationApi.getConversation('conv-contract-1')).rejects.toMatchObject({
      code: CONVERSATION_ERROR.RESPONSE_INVALID,
    })
  })

  it('rejects private/system messages and legacy fallback ids at the client boundary', async () => {
    request.get.mockResolvedValue({
      data: {
        items: [{
          messageId: 'msg-system',
          conversationId: 'conv-contract-1',
          sequence: 1,
          role: 'SYSTEM',
          messageType: 'TEXT',
          content: 'private prompt',
          createdAt: '2026-08-11T00:00:00Z',
        }],
        nextBeforeSequence: null,
        hasMore: false,
      },
    })

    await expect(conversationApi.listMessages('conv-contract-1')).rejects.toBeInstanceOf(ConversationApiError)
    await expect(conversationApi.getConversation('temp_123')).rejects.toMatchObject({
      code: CONVERSATION_ERROR.REQUEST_INVALID,
    })
    await expect(conversationApi.getConversation('default_conv')).rejects.toMatchObject({
      code: CONVERSATION_ERROR.REQUEST_INVALID,
    })
  })
})
