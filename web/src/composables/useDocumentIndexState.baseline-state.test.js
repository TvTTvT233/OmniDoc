import { mount, flushPromises } from '@vue/test-utils'
import { ref, defineComponent } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
vi.mock('../api/document', () => ({ docApi: {} }))
import { useDocumentIndexState } from './useDocumentIndexState'
let wrapper
afterEach(() => { wrapper?.unmount(); vi.useRealTimers() })
const ready = (version = 1) => ({ documentId: 'doc', currentVersion: version, indexedVersion: version, indexState: 'READY', queryable: true, activeGenerationId: `gen-${version}` })
function setup(api, version = ref(1)) {
  let state
  wrapper = mount(defineComponent({ setup() { state = useDocumentIndexState('doc', version, api); return () => null } }))
  return { state, version }
}
describe('document index state races and polling', () => {
  it('ignores a stale READY response arriving after a newer save/state refresh', async () => {
    let finish
    const api = { getIndexState: vi.fn().mockImplementationOnce(() => new Promise(resolve => { finish = resolve })).mockResolvedValueOnce({ data: { ...ready(2), indexState: 'FAILED', queryable: false, activeGenerationId: null } }) }
    const { state, version } = setup(api)
    const first = state.refresh()
    version.value = 2; state.invalidate(); await state.refresh()
    finish({ data: ready(1) }); await first
    expect(state.state.value.indexState).toBe('FAILED')
    expect(state.queryable.value).toBe(false)
  })
  it('does not advertise ready if the loaded editor version is older or the payload contradicts itself', async () => {
    const api = { getIndexState: vi.fn().mockResolvedValueOnce({ data: ready(2) }).mockResolvedValueOnce({ data: { ...ready(1), indexedVersion: 0 } }) }
    const { state } = setup(api)
    await state.refresh(); expect(state.queryable.value).toBe(false); expect(state.label.value).toContain('版本已变化')
    await state.refresh(); expect(state.state.value).toBe(null); expect(state.label.value).toContain('未知')
  })
  it('polls queued work until ready and stops after unmount', async () => {
    vi.useFakeTimers()
    const api = { getIndexState: vi.fn().mockResolvedValueOnce({ data: { ...ready(), indexState: 'QUEUED', indexedVersion: null, queryable: false, activeGenerationId: null } }).mockResolvedValue({ data: ready() }) }
    const { state } = setup(api)
    await state.refresh(); expect(state.queryable.value).toBe(false)
    await vi.advanceTimersByTimeAsync(3000); await flushPromises()
    expect(state.queryable.value).toBe(true); expect(api.getIndexState).toHaveBeenCalledTimes(2)
    wrapper.unmount(); await vi.advanceTimersByTimeAsync(6000)
    expect(api.getIndexState).toHaveBeenCalledTimes(2)
  })
})
