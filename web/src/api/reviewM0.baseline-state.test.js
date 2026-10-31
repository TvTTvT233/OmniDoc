import { afterEach, describe, expect, it, vi } from 'vitest'
import axios from 'axios'
import { webcrypto } from 'node:crypto'
import { ElMessage } from 'element-plus'
vi.mock('../router', () => ({ default: { push: vi.fn() } }))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn() } }))
import request from '../utils/request'
import { reviewM0Api as api, reviewError } from './reviewM0'
const adapter = request.defaults.adapter
afterEach(() => { request.defaults.adapter = adapter; vi.restoreAllMocks(); vi.unstubAllGlobals(); localStorage.clear() })
describe('Review API public contract', () => {
  it('uses owned upload and paginated owned listing rather than legacy file APIs', async () => {
    const seen = []; localStorage.setItem('accessToken', 'test-token')
    request.defaults.adapter = async config => { seen.push(config); return { config, status: 200, headers: {}, data: config.method === 'post' ? { fileId: 'owned-1', originalFileName: 'proof.pdf' } : { items: [], hasMore: false } } }
    expect(await api.uploadFile(new File(['synthetic'], 'proof.pdf'))).toMatchObject({ fileId: 'owned-1' })
    expect(await api.listFiles(2)).toEqual({ items: [], hasMore: false })
    expect(seen.map(c => c.url)).toEqual(['/files/owned', '/files/owned']); expect(seen[1].params).toEqual({ page: 2, size: 20 }); expect(seen[0].headers.Authorization).toBe('Bearer test-token')
    expect(seen[0].data.get('file').name).toBe('proof.pdf')
  })
  it('unwraps direct resources, preserves auth and write idempotency', async () => {
    let seen; localStorage.setItem('accessToken', 'test-token')
    request.defaults.adapter = async config => { seen = config; return { config, status: 201, headers: {}, data: { caseId: 'c1', processingMode: 'LOCAL_ONLY' } } }
    expect(await api.create('title', 'key-one')).toEqual({ caseId: 'c1', processingMode: 'LOCAL_ONLY' })
    expect(seen.headers.Authorization).toBe('Bearer test-token'); expect(seen.headers['Idempotency-Key']).toBe('key-one')
    expect(JSON.parse(seen.data)).toEqual({ title: 'title', processingMode: 'LOCAL_ONLY' })
  })
  it('requests owner-scoped bounded cursor page without userId', async () => {
    let seen; request.defaults.adapter = async config => { seen = config; return { config, status: 200, headers: {}, data: { items: [], nextCursor: null } } }
    await api.list('opaque'); expect(seen.params).toEqual({ limit: 20, cursor: 'opaque' }); expect(seen.url).toBe('/review-m0/cases')
  })
  it('encodes resource IDs and sends optimistic version only in edit body', async () => {
    let seen; request.defaults.adapter = async config => { seen = config; return { config, status: 200, headers: {}, data: { materialId: 'm/1' } } }
    await api.rename('c/1', 'm/1', { expectedVersion: 3, submissionFileName: '中文.pdf' }, 'rename-key')
    expect(seen.url).toBe('/review-m0/cases/c%2F1/materials/m%2F1'); expect(JSON.parse(seen.data)).toEqual({ expectedVersion: 3, submissionFileName: '中文.pdf' })
  })
  it('only an absent draft is an empty state without a misleading error toast', async () => {
    ElMessage.error.mockClear()
    request.defaults.adapter = async config => { throw new axios.AxiosError('failed', 'ERR_BAD_RESPONSE', config, null, { status: 404, config, data: { errorCode: 'REVIEW_M0_RESOURCE_NOT_FOUND' } }) }
    expect(await api.draft('new-case')).toBe(null); expect(ElMessage.error).not.toHaveBeenCalled()
    await expect(api.case('missing')).rejects.toMatchObject({ response: { status: 404 } }); expect(ElMessage.error).toHaveBeenCalled()
  })
  it.each([401, 503])('draft HTTP %s remains a visible failure', async status => {
    ElMessage.error.mockClear()
    request.defaults.adapter = async config => { throw new axios.AxiosError('failed', 'ERR_BAD_RESPONSE', config, null, { status, config, data: { errorCode: 'REVIEW_M0_RESOURCE_NOT_FOUND' } }) }
    await expect(api.draft('c1')).rejects.toMatchObject({ response: { status } }); expect(ElMessage.error).toHaveBeenCalled()
  })
  it.each([401, 404, 409, 503])('preserves real HTTP %s failures as errors', async status => {
    request.defaults.adapter = async config => { throw new axios.AxiosError('failed', 'ERR_BAD_RESPONSE', config, null, { status, config, data: { errorCode: 'REVIEW_M0_VERSION_CONFLICT', requestId: 'request-1' } }) }
    try { await api.case('c1'); throw new Error('unexpected success') } catch (error) { expect(error.response.status).toBe(status); expect(reviewError(error)).toMatchObject({ code: 'REVIEW_M0_VERSION_CONFLICT', requestId: 'request-1' }) }
  })
  it('verifies downloaded bytes and uses UTF-8 server filename', async () => {
    vi.stubGlobal('crypto', webcrypto)
    const bytes = new Uint8Array([1, 2, 3]), hash = [...new Uint8Array(await webcrypto.subtle.digest('SHA-256', bytes))].map(v => v.toString(16).padStart(2, '0')).join('')
    const getter = vi.spyOn(axios, 'get').mockResolvedValue({ data: bytes.buffer, headers: { 'x-content-sha256': hash, 'content-disposition': "attachment; filename*=UTF-8''%E8%AF%81%E6%98%8E.pdf", 'content-type': 'application/pdf' } })
    const result = await api.download('c1', 'm1'); expect(result.name).toBe('证明.pdf'); expect(result.blob.size).toBe(3); expect(getter.mock.calls[0][0]).toBe('/api/review-m0/cases/c1/materials/m1/download')
  })
  it('rejects corrupt/incomplete download instead of saving a misleading copy', async () => {
    vi.stubGlobal('crypto', webcrypto)
    vi.spyOn(axios, 'get').mockResolvedValue({ data: new Uint8Array([1]).buffer, headers: { 'x-content-sha256': 'a'.repeat(64), 'content-disposition': "attachment; filename*=UTF-8''proof.pdf" } })
    await expect(api.download('c1', 'm1')).rejects.toThrow('DOWNLOAD_INTEGRITY_MISMATCH')
  })
})
