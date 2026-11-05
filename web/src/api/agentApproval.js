/** Fetch a fresh challenge when durable history contains only its hash; never infer approval from a retry. */
export async function completeAgentApproval(result, { api, ask }) {
  if (result?.status !== 'action_required' || !result.pendingApproval || result.recoveryReason === 'OUTCOME_UNKNOWN') return result
  let current = result
  if (!current.pendingApproval.agentApprovalToken) {
    const resumed = await api.continueTask(current.traceId, {
      task: current.task || '继续当前任务', clientRequestId: crypto.randomUUID(), context: {},
    })
    current = resumed.data
  }
  const approval = current.pendingApproval
  if (!approval?.agentApprovalToken) throw new Error('暂时无法获取操作确认，请到 Agent 工作台核对任务。')
  const accepted = await ask(approval)
  if (!accepted) {
    await api.cancelApproval(approval.agentApprovalToken)
    return { ...current, status: 'cancelled', pendingApproval: null }
  }
  const response = await api.confirmApproval({ approvalToken: approval.agentApprovalToken, context: {} })
  return response.data
}
