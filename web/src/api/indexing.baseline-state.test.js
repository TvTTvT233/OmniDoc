import { afterEach, describe, expect, it, vi } from 'vitest'
import axios from 'axios'

vi.mock('../router', () => ({ default: { push: vi.fn() } }))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn() } }))
import request from '../utils/request.js'
import { aiApi } from './ai.js'

const adapter = request.defaults.adapter
afterEach(() => { request.defaults.adapter = adapter; vi.restoreAllMocks() })

describe('indexing response through the real API wrapper and response interceptor', () => {
  const respond = (data) => {
    vi.spyOn(axios, 'post').mockResolvedValue({}) // metrics only
    request.defaults.adapter = async config => ({ data, status: 200, statusText: 'OK', headers: {}, config })
  }

  it('rejects an HTTP 200 response with a failed business code', async () => {
    respond({ code: 500, message: 'Indexing failed', data: null })
    await expect(aiApi.indexWithSegment('baseline-document', 'paragraph', 'CHAPTER')).rejects.toThrow('Indexing failed')
  })

  it('accepts the successful backend acknowledgement', async () => {
    respond({ code: 200, message: 'success', data: null })
    await expect(aiApi.indexWithSegment('baseline-document', 'paragraph', 'CHAPTER')).resolves.toMatchObject({ code: 200 })
  })

  it('preserves the explicit segment state returned by the backend', async () => {
    respond({ code: 200, data: { indexState: 'NOT_INDEXED', segments: [] } })
    await expect(aiApi.getDocumentSegments('baseline-document')).resolves.toMatchObject({ data: { indexState: 'NOT_INDEXED', segments: [] } })
  })

  it('does not turn an old HTTP 200 business-denial into an empty segment result', async () => {
    respond({ code: 403, message: '拒绝读取分段', data: null })
    await expect(aiApi.getDocumentSegments('baseline-document')).rejects.toThrow('拒绝读取分段')
  })

  it.each([401, 403, 404, 409, 503])('preserves HTTP %s rejection for segment-state rendering', async (status) => {
    vi.spyOn(axios, 'post').mockResolvedValue({})
    request.defaults.adapter = async config => {
      const response = { status, statusText: 'Error', headers: {}, config, data: { code: status, message: '分段读取失败', data: null } }
      throw new axios.AxiosError('Request failed', 'ERR_BAD_RESPONSE', config, null, response)
    }
    await expect(aiApi.getDocumentSegments('baseline-document')).rejects.toMatchObject({ response: { status } })
  })
})
