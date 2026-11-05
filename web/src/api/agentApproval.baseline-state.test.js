import { it, expect, vi } from 'vitest'
import { completeAgentApproval } from './agentApproval'
const pending = { status: 'action_required', traceId: 'trace', task: 'delete synthetic', pendingApproval: { toolName: 'file-delete', params: { objectName: 'synthetic' } } }
function fixture() {
  const challenge = { ...pending, pendingApproval: { ...pending.pendingApproval, agentApprovalToken: 'fresh-token' } }
  return { api: { continueTask: vi.fn(async () => ({ data: challenge })), cancelApproval: vi.fn(async () => {}), confirmApproval: vi.fn(async () => ({ data: { status: 'success' } })) }, ask: vi.fn(async () => true) }
}
it('refreshes a challenge and asks about the resolved target before executing', async () => {
  const deps = fixture()
  const result = await completeAgentApproval(pending, deps)
  expect(deps.api.continueTask).toHaveBeenCalledOnce()
  expect(deps.ask).toHaveBeenCalledWith(expect.objectContaining({ params: { objectName: 'synthetic' } }))
  expect(deps.api.confirmApproval).toHaveBeenCalledWith({ approvalToken: 'fresh-token', context: {} })
  expect(result.status).toBe('success')
})
it('refusal cancels the challenge and never executes', async () => {
  const deps = fixture(); deps.ask.mockResolvedValue(false)
  expect((await completeAgentApproval(pending, deps)).status).toBe('cancelled')
  expect(deps.api.cancelApproval).toHaveBeenCalledWith('fresh-token')
  expect(deps.api.confirmApproval).not.toHaveBeenCalled()
})
it('unknown write outcomes cannot be resumed by the approval helper', async () => {
  const deps = fixture()
  await completeAgentApproval({ ...pending, recoveryReason: 'OUTCOME_UNKNOWN' }, deps)
  expect(deps.api.continueTask).not.toHaveBeenCalled(); expect(deps.ask).not.toHaveBeenCalled()
})
