import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
const mocks = vi.hoisted(() => ({ read: vi.fn() }))
vi.mock('../api/document', () => ({ docApi: { getDocDetail: mocks.read } }))
import { useCollaboration } from './useCollaboration'
let collaboration
beforeEach(() => { vi.useFakeTimers(); vi.resetAllMocks(); document.body.innerHTML = '<div class="paper"><p>original</p></div>'; mocks.read.mockResolvedValue({ data: { content: '<p>original</p>', version: 1 } }) })
afterEach(() => { collaboration?.stopCollaboration(); vi.useRealTimers(); vi.unstubAllGlobals(); document.body.innerHTML = '' })
describe('collaboration saves use editor CAS and truthful read states', () => {
  it('does not bypass the editor save callback or mark a failed save synced', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    const save = vi.fn().mockResolvedValue(false)
    collaboration = useCollaboration('doc', 'user', 'name', { saveDocument: save, acceptRemoteDocument: () => false })
    await collaboration.startCollaboration()
    const before = collaboration.lastSyncTime.value
    document.querySelector('.paper').innerHTML = '<p>draft</p>'
    document.querySelector('.paper').dispatchEvent(new Event('input'))
    await vi.advanceTimersByTimeAsync(1500)
    expect(save).toHaveBeenCalledWith('<p>draft</p>')
    expect(fetch).not.toHaveBeenCalled()
    expect(collaboration.lastSyncTime.value).toBe(before)
    expect(collaboration.connectionError.value).toContain('草稿已保留')
  })
  it('does not replace the body with an HTTP failure or a rejected remote revision', async () => {
    mocks.read.mockRejectedValueOnce({ response: { status: 503 } }).mockResolvedValue({ data: { content: '<p>remote</p>', version: 2 } })
    const accept = vi.fn().mockReturnValue(false)
    collaboration = useCollaboration('doc', 'user', 'name', { acceptRemoteDocument: accept })
    await collaboration.startCollaboration()
    await vi.advanceTimersByTimeAsync(2000)
    expect(document.querySelector('.paper').textContent).toBe('original')
    expect(collaboration.connectionError.value).toContain('读取协作文档失败')
    await vi.advanceTimersByTimeAsync(2000)
    expect(accept).toHaveBeenCalledWith({ content: '<p>remote</p>', version: 2 })
    expect(document.querySelector('.paper').textContent).toBe('original')
  })
})
