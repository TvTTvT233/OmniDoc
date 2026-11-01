<template>
  <section v-if="runId" class="task-run-panel" data-testid="task-run-panel" aria-live="polite">
    <div class="heading"><strong>任务运行状态</strong><button type="button" :disabled="loading" @click="load">刷新</button></div>
    <p v-if="loading && !run">正在读取任务状态…</p>
    <p v-if="error" role="alert">状态读取失败，暂不能确认任务结果。请重试。</p>
    <template v-if="run && !error">
      <p>{{ runStatusLabels[run.status] || '状态未知' }}<span v-if="run.cancellationRequested"> · 已请求取消</span></p>
      <p v-if="run.businessStatus === 'M0_READY'">审查结论：本轮齐全性与命名检查通过</p>
      <p v-if="run.businessStatus === 'M0_NOT_READY'">审查结论：本轮仍有待处理事项</p>
      <p v-if="run.result?.freshness === 'OUTDATED'">此结果已过期，请重新审查。</p>
      <p v-if="run.waitingReason === 'PLAN_CONFIRMATION'">计划已生成，尚未执行。</p>
      <details><summary>查看运行记录</summary>
        <p>运行标识：{{ run.runId }}</p>
        <p v-if="run.result">结果引用：{{ run.result.resourceId }}</p>
        <p v-for="link in run.links" :key="link.runId">关联任务：{{ link.runId }}</p>
        <p v-if="!run.steps.length">没有可展示的步骤记录。</p>
        <ol v-else><li v-for="step in run.steps" :key="step.stepId">{{ step.stepId }} · {{ runStatusLabels[step.status] || step.status }}</li></ol>
        <ul v-if="run.toolCalls.length"><li v-for="call in run.toolCalls" :key="call.toolCallId">{{ call.toolName }} · 第 {{ call.attempt }} 次尝试 · {{ runStatusLabels[call.status] || call.status }}</li></ul>
        <p>当前记录不代表完整执行日志；未记录的历史不会补造。</p>
      </details>
    </template>
  </section>
</template>
<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { getTaskRun } from '../../api/taskRuns'
import { runStatusLabels } from '../../api/taskRunState'
const props = defineProps({ runId: String, caseId: String, refreshKey: [String, Number] })
const run = ref(null)
const loading = ref(false)
const error = ref(false)
let generation = 0
let timer
async function load() {
  clearTimeout(timer)
  const current = ++generation
  if (!props.runId) { run.value = null; loading.value = false; return }
  loading.value = true
  error.value = false
  try {
    const next = await getTaskRun(props.runId, props.caseId)
    if (current !== generation) return
    run.value = next
    if (['QUEUED', 'RUNNING'].includes(next.status)) timer = setTimeout(load, 3000)
  } catch {
    if (current === generation) error.value = true
  } finally {
    if (current === generation) loading.value = false
  }
}
watch(() => [props.runId, props.caseId, props.refreshKey], () => { run.value = null; load() }, { immediate: true })
onBeforeUnmount(() => { generation++; clearTimeout(timer) })
</script>
<style scoped>
.task-run-panel { margin: 16px 0; padding: 16px; border: 1px solid #dce2ea; border-radius: 8px; background: #f8fafc; overflow-wrap: anywhere; }
.heading { display: flex; justify-content: space-between; align-items: center; }
p, li { font-size: 14px; line-height: 1.6; }
button { cursor: pointer; }
</style>
