<template>
  <div class="evidence-list">
    <p v-if="!items?.length" class="notice warn">未提供可核对的证据，需人工确认。</p>
    <article v-for="(evidence, index) in items" :key="evidence.refId || index" class="evidence-item">
      <strong>{{ labels[evidence.kind] || '审查证据' }}</strong>
      <dl>
        <template v-for="row in describe(evidence)" :key="row[0]"><dt>{{ row[0] }}</dt><dd>{{ row[1] }}</dd></template>
      </dl>
      <details><summary>查看证据记录</summary><pre>{{ JSON.stringify(evidence, null, 2) }}</pre></details>
    </article>
  </div>
</template>
<script setup>
defineProps({ items: { type: Array, default: () => [] } })
const labels = { OFFICIAL_BASIS_LOCATION: '官方依据定位', USER_RULE_REVISION: '用户确认的规则', GLOBAL_M0_POLICY: '材料预审通用规则', MATERIAL_ITEM_SNAPSHOT: '审查时的材料记录', MATERIAL_SET_SNAPSHOT: '本轮已确认的材料集合' }
function describe(e) {
  const value = e.value || e.reference || e
  const rows = []
  if (value.origin) rows.push(['来源', value.origin === 'USER_ADDED' ? '用户新增' : '官方提取'])
  if (value.authorship === 'USER_REWRITTEN') rows.push(['修订', '用户改写或确认，不代表官方原文'])
  if (value.minimumCount != null) rows.push(['最少数量', value.minimumCount])
  if (value.expectedCount != null) rows.push(['要求数量', value.expectedCount])
  if (value.confirmedAvailableCount != null) rows.push(['已确认可用数量', value.confirmedAvailableCount])
  if (value.submissionFileName) rows.push(['当时的提交名称', value.submissionFileName])
  if (value.entryPath) rows.push(['压缩包内位置', value.entryPath])
  if (value.formalFileType) rows.push(['文件类型', value.formalFileType])
  if (value.fileNameRule) rows.push(['命名要求', value.fileNameRule.kind === 'NONE' ? '无项目级命名要求，仍检查安全命名' : `${value.fileNameRule.pattern}（${value.fileNameRule.caseSensitive ? '区分' : '不区分'}大小写）`])
  const locator = value.sourceLocator || value.officialSourceRef?.sourceLocator
  if (locator) rows.push(['原文位置', [locator.entryPath, locator.page && `第 ${locator.page} 页`, locator.sheet, locator.cellRange, locator.block].filter(Boolean).join(' · ') || '未提供'])
  if (value.materialSetRevision != null) rows.push(['材料集合版本', value.materialSetRevision])
  return rows
}
</script>
