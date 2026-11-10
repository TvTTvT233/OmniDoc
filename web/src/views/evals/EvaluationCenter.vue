<template>
  <main class="evaluation-center">
    <header><div><p class="eyebrow">OMNIDOC · QUALITY</p><h1>评测中心</h1><p>每次修改，都有可比较的结果。</p></div><router-link to="/dashboard">返回工作台 →</router-link></header>
    <section class="boundary"><strong>确定性组件评测 · 外部模型禁用</strong><p>覆盖文档、Review 与 Agent 的固定场景。真实模型质量、token 和费用尚未测量；测试耗时不代表服务延迟。</p><p>此页仅查看安全报告，不触发任务。报告不随产品默认发布，不自动同步 CI 历史；导入仅保存在当前页面内存中。结构校验不证明来源真实性，请使用可信导出文件。</p></section>
    <div class="toolbar"><button @click="load" :disabled="busy">{{ busy ? '加载中…' : '刷新本地目录' }}</button><label class="import">导入 CI / 本地报告<input aria-label="导入评测目录" type="file" accept=".json,application/json" @change="importFile"></label><span>{{ source }}</span></div>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <section v-if="!catalog && !busy && !error" class="empty"><h2>还没有评测报告</h2><p>运行评测并导出安全目录后刷新，或导入 CI 下载的 catalog.json。</p></section>
    <template v-if="catalog">
      <p class="muted">目录生成：{{ catalog.generatedAtUtc }} · {{ catalog.runs.length }} 条记录<span v-if="catalog.omittedReports"> · {{ catalog.omittedReports }} 份报告未被纳入，请检查本地导出结果</span></p>
      <section class="panel"><div class="section-head"><h2>运行记录</h2><label>状态 <select v-model="filter" aria-label="状态筛选"><option value="ALL">全部</option><option value="PASS">通过</option><option value="FAIL">失败</option></select></label></div>
        <div class="table-scroll"><table><thead><tr><th>运行 / 基线</th><th>代码版本</th><th>通过用例</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="r in filtered" :key="r.key"><td><strong>{{ r.kind === 'baseline' ? '已接收基线' : '候选运行' }}</strong><small>{{ r.kind === 'baseline' ? r.key.slice(9) : r.runId }}</small></td><td>{{ r.code.commit.slice(0, 8) }}<small>{{ r.code.dirty ? '含工作区改动' : '干净工作区' }}</small></td><td>{{ r.summary.passed }} / {{ r.summary.total }}<small>重复 {{ r.repeats }} 次</small></td><td><span class="badge" :class="r.summary.gate">{{ r.summary.gate }}</span></td><td><button @click="selectedKey = r.key">查看详情</button></td></tr></tbody></table></div><p v-if="!filtered.length">当前筛选下没有运行记录。</p>
      </section>
      <section v-if="selected" class="panel detail"><h2>运行详情</h2><p>{{ selected.key }}</p><div class="metadata"><span>环境：{{ selected.runnerOs }}</span><span>总耗时：{{ selected.elapsedSeconds }} 秒</span><span>基础设施失败：{{ selected.infrastructureFailureCount }}</span></div><details><summary>查看可追溯版本</summary><p>源码：{{ selected.code.commit }}</p><p>工作树：{{ selected.code.treeDigest }}</p><p>数据集：{{ selected.dataset.digest }}</p><p>测试夹具：{{ selected.dataset.fixtureDigest }}</p><p>评分器：{{ selected.grader.version }} / {{ selected.grader.digest }}</p></details>
        <details v-for="c in selected.cases" :key="c.id" class="case"><summary><span :class="['badge', c.trials.every(t => t.status === 'PASSED') ? 'PASS' : 'FAIL']">{{ c.trials.every(t => t.status === 'PASSED') ? 'PASS' : 'FAIL' }}</span> {{ definition(c.id).title }} <small>{{ c.id }} · {{ definition(c.id).critical ? '关键场景' : '普通场景' }}</small></summary><p>{{ definition(c.id).task }}</p><p>预期：{{ definition(c.id).expectedOutcome }}</p><p>证据范围：{{ definition(c.id).evidenceLevel }}</p><ul><li v-for="t in c.trials" :key="t.trial">第 {{ t.trial }} 次：{{ t.status }} · {{ t.durationMs === null ? '耗时未知' : t.durationMs + ' ms' }}<span v-if="t.reason"> · {{ t.reason }}</span></li></ul></details>
      </section>
      <section class="panel"><h2>基线对比</h2><div class="compare-controls"><label>已接收基线<select v-model="baselineKey" aria-label="已接收基线"><option value="">请选择</option><option v-for="r in baselines" :value="r.key" :key="r.key">{{ r.key.slice(9) }}</option></select></label><label>候选运行<select v-model="candidateKey" aria-label="候选运行"><option value="">请选择</option><option v-for="r in candidates" :value="r.key" :key="r.key">{{ r.runId }}</option></select></label></div>
        <template v-if="comparison"><p><span class="badge" :class="comparison.gate">{{ comparison.gate }}</span> {{ comparison.gate === 'BLOCKED' ? '版本或环境不兼容，不能比较。' : comparison.gate === 'FAIL' ? '候选未通过，请查看失败与退化项。' : '当前固定场景未发现退化。' }}</p><p v-if="comparison.reason">{{ comparison.reason }}</p><div v-else class="table-scroll"><table><thead><tr><th>场景</th><th>基线 → 候选通过率</th><th>退化</th><th>平均耗时变化（参考）</th></tr></thead><tbody><tr v-for="c in comparison.changes" :key="c.id"><td>{{ definition(c.id).title }}</td><td>{{ percent(c.baselinePassRate) }} → {{ percent(c.candidatePassRate) }}</td><td>{{ c.regressed ? '是' : '否' }}</td><td>{{ c.meanDurationDeltaMs === null ? '未测量' : c.meanDurationDeltaMs.toFixed(2) + ' ms' }}</td></tr></tbody></table></div></template><p v-else class="muted">选择一个已接收基线和候选运行。不同评分器、数据集或操作系统不会给出可比结论。</p>
      </section>
    </template>
  </main>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { MAX_CATALOG_BYTES, parseCatalog } from '../../utils/evaluationCatalog.js'
const catalog = ref(null), error = ref(''), busy = ref(false), source = ref(''), selectedKey = ref(''), filter = ref('ALL'), baselineKey = ref(''), candidateKey = ref('')
let request = 0
const filtered = computed(() => (catalog.value?.runs || []).filter(r => filter.value === 'ALL' || r.summary.gate === filter.value))
const baselines = computed(() => catalog.value?.runs.filter(r => r.kind === 'baseline') || [])
const candidates = computed(() => catalog.value?.runs.filter(r => r.kind === 'run') || [])
const selected = computed(() => catalog.value?.runs.find(r => r.key === selectedKey.value))
const comparison = computed(() => catalog.value?.comparisons.find(c => c.baselineKey === baselineKey.value && c.candidateKey === candidateKey.value))
const definition = (id) => catalog.value.cases.find(c => c.id === id)
const percent = (n) => `${(n * 100).toFixed(1)}%`
function clear() { catalog.value = null; error.value = ''; source.value = ''; selectedKey.value = ''; baselineKey.value = ''; candidateKey.value = '' }
function accept(raw, label) { catalog.value = parseCatalog(raw); source.value = label; selectedKey.value = candidates.value[0]?.key || baselines.value[0]?.key || ''; baselineKey.value = baselines.value[0]?.key || ''; candidateKey.value = candidates.value[0]?.key || '' }
async function load() {
  const id = ++request; clear(); busy.value = true
  try { const response = await fetch('/evals/catalog.json', { cache: 'no-store', credentials: 'omit' }); if (id !== request) return; if (response.status === 404) return; if (!response.ok) throw new Error(); if (Number(response.headers.get('content-length')) > MAX_CATALOG_BYTES) throw new Error(); const raw = await response.text(); if (id === request) accept(raw, '来源：本地安全目录') }
  catch { if (id === request) error.value = '报告加载或校验失败。请检查目录文件，或重新导入安全导出结果。' }
  finally { if (id === request) busy.value = false }
}
async function importFile(event) {
  const file = event.target.files?.[0]; if (!file) return
  const id = ++request; clear(); busy.value = true
  try { if (file.size > MAX_CATALOG_BYTES) throw new Error(); const raw = await file.text(); if (id === request) accept(raw, '来源：手工导入（仅当前页面）') }
  catch { if (id === request) error.value = '导入失败：请选择不超过 5 MB 的有效安全目录 JSON。' }
  finally { if (id === request) busy.value = false; event.target.value = '' }
}
onMounted(load)
</script>

<style scoped>
.evaluation-center{max-width:1200px;margin:auto;padding:36px 28px 80px;color:#23364b;background:#f7f9fc;min-height:100vh;font-family:Inter,"Microsoft YaHei",sans-serif}header,.section-head,.toolbar,.metadata{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap}h1{font-size:34px;margin:8px 0}h2{font-size:21px}.eyebrow{font-size:12px;letter-spacing:2px;color:#55758b}header a{color:#2262a1}.boundary{background:#eaf2fa;border-left:4px solid #397ca9;padding:20px 24px;margin:28px 0;line-height:1.7}.boundary p{margin:7px 0;font-size:14px}.toolbar{justify-content:flex-start;margin-bottom:24px}button,.import,select{border:1px solid #b8cadd;background:white;border-radius:7px;padding:9px 14px;color:#234967;font:inherit}button,.import{cursor:pointer}button:disabled{opacity:.5}.import{position:relative;overflow:hidden}.import input{position:absolute;inset:0;opacity:0;width:100%;cursor:pointer}.panel,.empty{background:white;border:1px solid #dce5ef;border-radius:12px;padding:24px;margin:20px 0}.empty{text-align:center;padding:60px 20px}.muted,small,.toolbar span{color:#65768a;font-size:13px}small{display:block;margin-top:5px}.table-scroll{overflow:auto}table{width:100%;border-collapse:collapse;text-align:left}th,td{padding:14px 12px;border-bottom:1px solid #e7edf4}th{font-size:13px;color:#64758a;background:#f8fafc}.badge{display:inline-block;padding:5px 9px;border-radius:5px;font-size:12px;font-weight:700}.PASS{color:#136844;background:#e4f5eb}.FAIL,.error{color:#a62a32;background:#fff0f0}.BLOCKED{color:#8a5a0d;background:#fff3da}.error{padding:18px;border-radius:8px}.metadata{justify-content:flex-start;font-size:14px;margin:15px 0}.detail p{overflow-wrap:anywhere}details{margin:12px 0;padding:12px;background:#f8fafc;border-radius:6px}summary{cursor:pointer}.case summary small{display:inline-block;margin-left:12px}.case p,.case li{font-size:14px;line-height:1.7}.compare-controls{display:flex;gap:24px;flex-wrap:wrap}.compare-controls label{display:flex;flex-direction:column;gap:8px;max-width:100%}select{max-width:100%}@media(max-width:600px){.evaluation-center{padding:20px 12px}.panel{padding:15px}h1{font-size:28px}th,td{padding:10px}.compare-controls{display:block}.compare-controls label{margin:14px 0}}
</style>
