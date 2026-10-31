import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
const api = vi.hoisted(() => ({ listFiles: vi.fn(), uploadFile: vi.fn() }))
vi.mock('../../api/reviewM0', () => ({ reviewM0Api: api }))
import Picker from './ReviewFilePicker.vue'
let wrapper
const metadata = (id = 'owned-1') => ({ fileId: id, originalFileName: `${id}.pdf` })
beforeEach(() => { vi.resetAllMocks(); api.listFiles.mockResolvedValue({ items: [metadata()], hasMore: false }) })
afterEach(() => wrapper?.unmount())
async function render() { wrapper = mount(Picker); await flushPromises() }
async function upload() { const input = wrapper.get('input[type=file]'); Object.defineProperty(input.element, 'files', { value: [new File(['synthetic'], 'new.pdf')], configurable: true }); await input.trigger('change'); await flushPromises() }
describe('owner-aware Review file selection', () => {
  it('reloads files from the server and clears an old selection when refreshing', async () => {
    await render(); await wrapper.get('select').setValue('owned-1'); expect(wrapper.emitted('selected').at(-1)[0]).toEqual({ id: 'owned-1', name: 'owned-1.pdf' })
    api.listFiles.mockResolvedValue({ items: [metadata('owned-2')], hasMore: true }); await wrapper.findAll('button').find(b => b.text() === '刷新文件库').trigger('click'); await flushPromises()
    expect(wrapper.emitted('selected').at(-1)[0]).toBe(null); expect(wrapper.text()).toContain('owned-2.pdf'); expect(wrapper.text()).not.toContain('owned-1.pdf')
    await wrapper.findAll('button').find(b => b.text() === '下一页文件').trigger('click'); await flushPromises(); expect(api.listFiles).toHaveBeenLastCalledWith(2)
  })
  it('selects a real owned upload and reloads page one without automatically attaching it', async () => {
    await render(); api.uploadFile.mockResolvedValue(metadata('new')); await upload()
    expect(api.uploadFile).toHaveBeenCalledOnce(); expect(api.listFiles).toHaveBeenLastCalledWith(1); expect(wrapper.emitted('selected').at(-1)[0]).toEqual({ id: 'new', name: 'new.pdf' })
  })
  it('failed upload clears prior selection and never fabricates success', async () => {
    await render(); await wrapper.get('select').setValue('owned-1'); api.uploadFile.mockRejectedValue(new Error('503')); await upload()
    expect(wrapper.emitted('selected').at(-1)[0]).toBe(null); expect(wrapper.get('[role=alert]').text()).toContain('上传未完成')
  })
  it('unmounted picker ignores late uploads and never selects a file in another case', async () => {
    const selected = vi.fn(); wrapper = mount(Picker, { props: { onSelected: selected } }); await flushPromises(); let resolve; api.uploadFile.mockReturnValue(new Promise(r => { resolve = r })); await upload(); const count = selected.mock.calls.length; wrapper.unmount(); resolve(metadata('late')); await flushPromises(); expect(selected).toHaveBeenCalledTimes(count)
  })
})
