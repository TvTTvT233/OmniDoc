import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
const mocks = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('../../utils/request', () => ({ default: { get: mocks.get } }))
import TaskRunPanel from './TaskRunPanel.vue'
import { agentOutcome } from '../../api/taskRunState'
let wrapper
const view = (runId, status = 'SUCCEEDED', extra = {}) => ({ protocolVersion: 1, runId, status, links: [], steps: [], toolCalls: [], ...extra })
beforeEach(() => { vi.clearAllMocks() })
afterEach(() => { wrapper?.unmount(); vi.useRealTimers() })
it('shows review execution and negative/outdated assessment separately', async () => {
  mocks.get.mockResolvedValue({ data: view('review:j', 'SUCCEEDED', { businessStatus: 'M0_NOT_READY', result: { resourceId: 'historic', freshness: 'OUTDATED' } }) })
  wrapper = mount(TaskRunPanel, { props: { runId: 'review:j', caseId: 'c' } })
  await flushPromises()
  expect(mocks.get).toHaveBeenCalledWith('/ai/task-runs/review%3Aj', { params: { caseId: 'c' } })
  expect(wrapper.text()).toContain('执行成功')
  expect(wrapper.text()).toContain('仍有待处理事项')
  expect(wrapper.text()).toContain('此结果已过期')
  expect(wrapper.text()).toContain('historic')
})
it('query failure is unknown state rather than run failure and is retryable', async () => {
  mocks.get.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({ data: view('agent:t', 'WAITING_INPUT') })
  wrapper = mount(TaskRunPanel, { props: { runId: 'agent:t' } })
  await flushPromises()
  expect(wrapper.get('[role=alert]').text()).toContain('暂不能确认')
  expect(wrapper.text()).not.toContain('执行失败')
  await wrapper.get('button').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('等待确认或补充')
})
it('late responses cannot replace a newly selected run', async () => {
  let finishOld
  mocks.get.mockImplementationOnce(() => new Promise(resolve => { finishOld = resolve }))
    .mockResolvedValueOnce({ data: view('agent:new', 'FAILED') })
  wrapper = mount(TaskRunPanel, { props: { runId: 'agent:old' } })
  await wrapper.setProps({ runId: 'agent:new' }); await flushPromises()
  finishOld({ data: view('agent:old') }); await flushPromises()
  expect(wrapper.text()).toContain('agent:new')
  expect(wrapper.text()).not.toContain('agent:old')
  expect(wrapper.text()).toContain('执行失败')
})
it('polls active runs and stops after completion', async () => {
  vi.useFakeTimers()
  mocks.get.mockResolvedValueOnce({ data: view('async:j', 'RUNNING') })
    .mockResolvedValueOnce({ data: view('async:j', 'SUCCEEDED') })
  wrapper = mount(TaskRunPanel, { props: { runId: 'async:j' } }); await flushPromises()
  await vi.advanceTimersByTimeAsync(3000); await flushPromises()
  await vi.advanceTimersByTimeAsync(9000)
  expect(mocks.get).toHaveBeenCalledTimes(2)
})
it.each(['error', 'failed', 'action_required', 'planned', 'cancelled', undefined])('does not toast success for %s', status => {
  expect(agentOutcome(status).type).not.toBe('success')
})
