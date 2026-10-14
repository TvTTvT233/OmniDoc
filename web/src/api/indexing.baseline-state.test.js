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
})
