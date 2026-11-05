// A generated patch is a proposal. Applying it is a separate, explicit UI operation.
export function checkFrontendAction(data, { documentId, version, content, originalContent, appliedIds }) {
  if (!data?.actionId || !data?.documentId || !Number.isInteger(data.expectedVersion)) return '动作缺少目标或版本信息，请重新生成。'
  if (String(data.documentId) !== String(documentId)) return '动作属于另一篇文档，不能应用到当前页面。'
  if (appliedIds.has(data.actionId)) return '此动作已经应用，已阻止重复写入。'
  if (data.expectedVersion !== version || content !== originalContent) return '文档已变化，请基于当前内容重新生成。'
  return null
}
