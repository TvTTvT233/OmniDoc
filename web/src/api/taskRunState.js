export const runStatusLabels = {
  QUEUED: '排队中', RUNNING: '执行中', WAITING_INPUT: '等待确认或补充',
  SUCCEEDED: '执行成功', FAILED: '执行失败', CANCELLED: '已取消', UNKNOWN: '状态未知',
}

// HTTP success alone does not imply that an Agent task succeeded.
export function agentOutcome(status) {
  if (status === 'success') return { type: 'success', message: '执行成功' }
  if (status === 'action_required') return { type: 'warning', message: '任务等待确认或补充信息' }
  if (status === 'planned') return { type: 'info', message: '计划已生成，尚未执行' }
  if (status === 'cancelled') return { type: 'info', message: '任务已取消' }
  if (['error', 'failed', 'blocked'].includes(status)) return { type: 'error', message: '任务执行失败，请查看结果' }
  return { type: 'warning', message: '任务状态尚未确认，请刷新查看' }
}
