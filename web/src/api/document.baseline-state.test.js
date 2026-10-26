import { afterEach, describe, expect, it, vi } from 'vitest'
import axios from 'axios'
vi.mock('../router', () => ({ default: { push: vi.fn() } }))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn() } }))
import request from '../utils/request'
import { docApi } from './document'
const originalAdapter = request.defaults.adapter
afterEach(() => { request.defaults.adapter = originalAdapter })
describe('document consistency API through the real response interceptor', () => {
  it('submits a version and strategy without document content and accepts HTTP 202 Result', async () => {
    const seen = []
    request.defaults.adapter = async config => {
      seen.push(config)
      return { config, status: 202, statusText: 'Accepted', headers: {}, data: { code: 200, data: { indexState: 'QUEUED', queryable: false } } }
    }
    await expect(docApi.buildIndex('doc-one', 2, 'CHAPTER')).resolves.toMatchObject({ data: { indexState: 'QUEUED' } })
    await docApi.retryIndex('doc-one', 2)
    await docApi.restoreVersion('doc-one', 1, 2)
    expect(seen.map(config => JSON.parse(config.data))).toEqual([{ expectedVersion: 2, strategy: 'CHAPTER' }, { expectedVersion: 2 }, { expectedVersion: 2 }])
    expect(seen.map(config => config.url)).toEqual(['/documents/doc-one/index', '/documents/doc-one/index/retry', '/documents/doc-one/restore/1'])
  })
  it.each([404, 409, 503])('preserves HTTP %s and the error code for protected snapshot/state reads', async status => {
    request.defaults.adapter = async config => {
      throw new axios.AxiosError('Request failed', 'ERR_BAD_RESPONSE', config, null, { config, status, data: { errorCode: 'DOCUMENT_REFERENCE_UNAVAILABLE', message: 'unavailable' } })
    }
    await expect(docApi.getReference('doc', 'reference')).rejects.toMatchObject({ response: { status, data: { errorCode: 'DOCUMENT_REFERENCE_UNAVAILABLE' } } })
  })
})
