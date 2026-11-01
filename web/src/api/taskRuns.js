import request from '../utils/request'

export async function getTaskRun(runId, caseId) {
  const response = await request.get(`/ai/task-runs/${encodeURIComponent(runId)}`, {
    params: caseId ? { caseId } : {},
  })
  const run = response.data
  if (run?.protocolVersion !== 1 || run.runId !== runId) throw new Error('任务状态响应无效')
  return run
}
