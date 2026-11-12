<template>
  <section class="service-results">
    <h2>真实服务流程评测</h2>
    <p>独立于组件评测：真实 HTTP 与隔离存储，Embedding 使用测试替身，外部模型禁用。报告不会自动同步；导入只保存在本页内存中，不能证明来源真实性。</p>
    <label>导入服务流程报告 <input aria-label="导入服务流程报告" type="file" accept="application/json,.json" @change="importReport"></label>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="report">
      <p>{{ report.runId }} · {{ report.gate }} · {{ report.commit.slice(0,8) }}{{ report.dirty ? '（含工作区改动）' : '' }}</p>
      <details v-for="c in report.cases" :key="c.id"><summary>{{ c.title }} · {{ c.status }}</summary>
        <p>{{ c.reason || '流程检查通过' }} · {{ c.durationMs.toFixed(0) }} ms</p>
        <p>业务任务：{{ c.productionRunIds.join('、') || (c.runIdPolicy === 'NOT_APPLICABLE' ? '不适用：文档版本流程' : '未取得任务编号') }}</p>
        <ul><li v-for="(check,index) in c.checks" :key="index">{{ check.name }}：{{ check.passed ? '通过' : '失败' }}</li></ul>
      </details>
    </template><p v-else>尚未导入服务流程报告。费用与真实模型质量未测量。</p>
  </section>
</template>
<script setup>
import { ref } from 'vue'
import { parseServiceReport } from '../../utils/serviceReport.js'
const report=ref(null), error=ref('')
let request=0
async function importReport(event) {
  const file=event.target.files?.[0]; if(!file)return
  const id=++request; report.value=null; error.value=''
  try { if(file.size>100000)throw new Error(); const value=parseServiceReport(await file.text()); if(id===request)report.value=value }
  catch { if(id===request)error.value='报告格式或流程结果校验失败，请导入可信的服务流程 report.json。' }
  finally { event.target.value='' }
}
</script>
<style scoped>
.service-results{background:white;border:1px solid #dce5ef;border-radius:12px;padding:24px;margin:20px 0}p,li{line-height:1.7;overflow-wrap:anywhere}details{padding:12px;background:#f8fafc;margin:12px 0}summary{cursor:pointer}[role=alert]{color:#a62a32}
</style>
