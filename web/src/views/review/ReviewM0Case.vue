<template>
  <main class="review-page">
    <header class="review-header"><router-link to="/review-m0">← 我的审查案例</router-link><div class="inline"><span class="mode-badge">{{ current?.processingMode === 'REMOTE_MODEL' ? '远程模式案例 · 本页不发送' : '本地处理' }}</span><button :disabled="busy || loading" @click="refresh">刷新状态</button></div></header>
    <section class="review-hero"><p class="eyebrow">材料准备 · 提交前检查</p><h1>{{ current?.title || '审查案例' }}</h1><p>确认清单，关联材料，再查看有依据的检查结果。原文件始终保留。</p></section>
    <div v-if="error" class="notice error" role="alert">{{ error.message }}<small>{{ error.code }}<span v-if="error.requestId"> · 请求编号 {{ error.requestId }}</span></small></div>
    <p v-if="loading" role="status">正在读取最新状态…</p>
    <p v-if="!current && !loading" class="empty">案例尚未加载，请刷新重试。</p>
    <template v-if="current">
      <nav class="review-tabs" aria-label="审查步骤"><button v-for="item in tabs" :key="item.id" :class="{ active: tab === item.id }" @click="tab = item.id">{{ item.label }}</button></nav>
      <fieldset :disabled="disabled">
        <section v-if="tab === 'rules'" class="panel">
          <div class="section-title"><h2>1. 确认材料清单</h2><span class="tag">{{ snapshot ? '已有正式清单' : '等待发布' }}</span></div>
          <p class="muted">本轮由你手工录入并确认规则。系统会标记为用户规则，不冒充官方原文；自动候选提取尚未接通。</p>
          <div v-if="unprocessedBases" class="notice warn">当前官方依据尚未完成解析或存在诊断，不能发布。请在“官方依据”中移除这些引用后继续手工流程；文件库中的原件仍保留。</div>
          <form class="form-grid" data-testid="requirement-form" @submit.prevent="saveRequirement">
            <label class="wide">材料要求名称<input v-model="ruleForm.displayName" required placeholder="例如：成绩证明" data-testid="requirement-name"></label>
            <label>是否适用<select v-model="ruleForm.applicability" required data-testid="requirement-applicability"><option value="NEEDS_USER_DECISION">请选择是否适用</option><option value="APPLIES">适用</option><option value="DOES_NOT_APPLY">不适用</option></select></label>
            <label>最少份数<input v-model.number="ruleForm.minimumCount" type="number" min="1" step="1" required></label>
            <label>文件命名要求<select v-model="ruleForm.kind" data-testid="rule-kind"><option value="NONE">无项目级要求</option><option value="EXACT">完整文件名</option><option value="REGEX">完整匹配正则</option></select></label>
            <label v-if="ruleForm.kind !== 'NONE'">是否区分大小写<select v-model="ruleForm.caseSensitive" required data-testid="rule-case"><option :value="null" disabled>请选择</option><option :value="true">区分</option><option :value="false">不区分</option></select></label>
            <label v-if="ruleForm.kind !== 'NONE'" class="wide">命名规则（包含扩展名）<input v-model="ruleForm.pattern" required data-testid="rule-pattern" placeholder="例如：成绩证明.pdf"></label>
            <label class="check-line"><input v-model="ruleForm.enabled" type="checkbox">启用此项目</label>
            <div class="inline wide"><button class="primary" :disabled="!validRule">{{ editing ? '保存规则修改' : '新增规则' }}</button><button v-if="editing" type="button" @click="resetRule">取消编辑</button></div>
          </form>
          <div class="section-title" style="margin-top:28px"><h3>待确认项目</h3><small>逐项核对适用性、份数及命名要求</small></div>
          <p v-if="!draft?.items?.length" class="empty">还没有规则，请先新增材料要求。</p>
          <article v-for="item in draft?.items || []" :key="item.requirementId" class="item-row" :data-requirement-id="item.requirementId">
            <div class="section-title"><h3>{{ item.displayName }}</h3><span class="tag">{{ item.origin === 'USER_ADDED' ? '用户新增' : '官方提取' }}{{ item.authorship === 'USER_REWRITTEN' ? ' · 用户修订' : '' }}</span></div>
            <p>{{ item.enabled ? '已启用' : '已停用' }} · {{ applicability[item.applicability] }} · 至少 {{ item.minimumCount }} 份<br>{{ ruleText(item.fileNameRule) }}</p>
            <div v-for="(source, index) in item.sourceRefs" :key="index" class="source-refs">官方定位：{{ locationText(source.sourceLocator) }}<p>{{ source.quotedText }}</p></div>
            <div class="row-actions"><button @click="editRule(item)">修改规则</button><label v-if="item.enabled" class="check-line"><input v-model="acknowledged" type="checkbox" :value="item.requirementId" :disabled="item.applicability === 'NEEDS_USER_DECISION'">我已核对并确认此项</label></div>
          </article>
          <div class="inline" style="margin-top:20px"><button class="primary" data-testid="publish-checklist" :disabled="!canPublish" @click="publish">发布已确认清单</button><span class="muted">{{ draft?.lifecycle === 'CONFIRMED' ? '当前规则已发布。修改后会生成新草稿，再逐项确认。' : '规则修改后需重新确认；旧报告会过期。' }}</span></div>
        </section>

        <section v-if="tab === 'bases'" class="panel">
          <h2>官方依据</h2><div class="notice">本轮先使用手工规则。此处可以保留依据引用与分类，但自动提取尚未接通；未处理的依据会阻止发布正式清单。手工规则不会自动关联为官方原文。</div>
          <FilePicker :disabled="disabled" label="选择官方依据" @selected="basisFile = $event" />
          <div class="inline"><label>依据类型<select v-model="basisRole"><option value="APPLICATION_NOTICE">申报通知</option><option value="FILLING_GUIDE">填写说明</option><option value="OFFICIAL_TEMPLATE">官方模板</option></select></label><button :disabled="!basisFile" @click="attachBasis">附加依据引用</button></div>
          <article v-for="basis in bases" :key="basis.basisId" class="item-row"><h3>{{ basis.originalFileNameSnapshot }}</h3><p>{{ basisRoles[basis.role] }} · {{ statusLabels[basis.status] || basis.status }}</p><p v-for="(diagnostic, index) in basis.diagnostics" :key="index" class="notice warn">需人工确认：{{ diagnostic.cannotConfirmReason }}</p><div v-if="basis.status !== 'DETACHED'" class="row-actions"><select :value="basis.role" aria-label="更改依据分类" @change="classifyBasis(basis, $event.target.value)"><option v-for="(name, value) in basisRoles" :key="value" :value="value">{{ name }}</option></select><button class="danger" @click="detachBasis(basis)">移除案例引用（保留原件）</button></div></article>
        </section>

        <section v-if="tab === 'materials'" class="panel">
          <h2>2. 材料与用途确认</h2><p class="muted">支持 PDF、DOCX、XLSX、JPG、PNG、ZIP。ZIP 会展开为独立材料；系统不根据正文猜测用途。</p>
          <FilePicker :disabled="disabled" label="选择提交材料" @selected="selectMaterialFile" />
          <div class="inline"><label v-if="materialFile && !isZip">提交副本名称<input v-model="submissionName" data-testid="submission-name"></label><button data-testid="attach-material" :disabled="!materialFile || (!isZip && !submissionName.trim())" @click="attachMaterial">加入本次材料</button></div>
          <p class="muted">逻辑名称仅用于审查和副本下载，不改变原文件名与内容。</p>
          <details v-if="sources.length"><summary>查看源文件与处理诊断</summary><article v-for="source in sources" :key="source.sourceId" class="item-row"><strong>{{ source.originalFileNameSnapshot }}</strong> · {{ source.status === 'ATTACHED' ? '已加入本次材料' : statusLabels[source.status] || source.status }}<p v-for="(diagnostic, index) in source.diagnostics" :key="index" class="notice warn">需人工确认：{{ diagnostic.cannotConfirmReason }}</p><button v-if="source.status === 'ATTACHED'" class="danger" @click="removeSource(source)">移除本次材料引用</button></article></details>
          <p v-if="!materials.length" class="empty">还没有材料。确认上传完毕前，不会输出缺失结论。</p>
          <article v-for="material in materials" :key="material.materialId" class="item-row" :data-material-id="material.materialId">
            <h3>{{ material.submissionFileName }}</h3><span class="tag">{{ statusLabels[material.status] || material.status }} · {{ material.formalFileType }}</span><p v-if="material.entryLocator?.entryPath" class="muted">压缩包内：{{ material.entryLocator.entryPath }}</p><p v-if="material.cannotConfirmReason" class="notice warn">需人工确认：{{ material.cannotConfirmReason }}</p>
            <div v-if="material.status !== 'REMOVED'" class="row-actions"><input v-model="renames[material.materialId]" :aria-label="`${material.submissionFileName}的新名称`"><button :disabled="!renames[material.materialId]?.trim()" @click="renameMaterial(material)">保存提交名称</button><button @click="download(material)">下载副本</button></div>
            <div v-if="material.status !== 'REMOVED' && snapshot" class="assignment-grid"><strong>此材料用于满足哪些要求？</strong><label v-for="requirement in activeRequirements" :key="requirement.requirementId"><input v-model="selectedAssignments" type="checkbox" :value="assignmentKey(material.materialId, requirement.requirementId)">{{ requirement.displayName }}</label></div>
          </article>
          <div v-if="snapshot" class="inline"><button data-testid="save-assignments" @click="saveAssignments">确认并保存材料用途</button><span class="muted">可以明确分配给多个要求。未保存的勾选不参与审查。</span></div>
          <p v-else class="notice warn">请先发布正式清单，再确认材料用途。</p>
          <div class="notice" style="margin-top:24px"><label class="check-line"><input v-model="uploadAcknowledged" type="checkbox" :disabled="!snapshot || assignmentsDirty">我确认本轮材料已全部上传，并已保存材料用途</label><div class="row-actions"><button data-testid="confirm-upload" :disabled="!uploadAcknowledged || !snapshot || assignmentsDirty" @click="confirmUpload">确认本轮上传完毕</button><span v-if="uploadConfirmation" class="tag">当前材料上传已确认</span></div></div>
        </section>

        <section v-if="tab === 'report'" class="panel">
          <h2>3. 运行审查与整改</h2><p class="muted">只按已确认清单检查材料齐全性和命名。系统错误与“材料缺失”分别展示。</p>
          <button v-if="current.currentReportId && report?.reportId !== current.currentReportId" @click="showLatestReport">查看最新报告</button>
          <div v-if="!uploadConfirmation" class="notice warn">运行前请在“材料与分配”确认当前轮上传完毕。刷新页面后需重新确认，服务端会复用相同材料的确认记录。</div>
          <div class="inline"><button class="primary" data-testid="run-review" :disabled="!uploadConfirmation || assignmentsDirty || jobRunning" @click="startReview('FULL')">运行完整审查</button><select v-if="report?.reportId" v-model="changedMaterialId" aria-label="选择单文件复审材料"><option value="">选择本次修改的单份材料</option><option v-for="m in materials.filter(m => m.status !== 'REMOVED')" :key="m.materialId" :value="m.materialId">{{ m.submissionFileName }}</option></select><button v-if="report?.reportId" :disabled="!changedMaterialId || !uploadConfirmation || assignmentsDirty || jobRunning" @click="startReview('INCREMENTAL', [changedMaterialId])">单文件复审</button></div>
          <div v-if="job" class="notice" data-testid="job-state"><strong>任务：{{ jobLabels[job.status] || job.status }}</strong><p>已完成 {{ job.progress?.completedUnits || 0 }} / {{ job.progress?.totalUnits || 0 }} 项 · 第 {{ job.attempt }} 次尝试</p><p v-if="job.error" class="notice error">任务失败：{{ job.error.errorCode }}。未生成新的审查结论。</p><div class="inline"><button @click="resumePoll">刷新任务状态</button><button v-if="jobRunning && job.status !== 'CANCEL_REQUESTED'" @click="cancel">取消任务</button></div></div>
          <template v-if="report?.findings">
            <div :class="['notice', report.freshness === 'OUTDATED' ? 'warn' : ready ? 'success' : 'warn']" data-testid="report-assessment"><strong>{{ loadFailed ? '状态读取失败，暂无法确认报告是否仍有效' : report.freshness === 'OUTDATED' ? '历史报告已过期，请重新审查' : ready ? '本轮材料齐全性与命名检查通过' : '本轮仍有待处理事项' }}</strong><p>{{ formatTime(report.createdAt) }} · 材料版本 {{ report.materialSetRevision }}<span v-if="report.baseReportId"> · 单文件复审结果</span></p></div>
            <div class="report-summary"><span>缺失 {{ report.summary.missingCount }}</span><span>命名问题 {{ report.summary.fileNameNoncompliantCount }}</span><span>需人工确认 {{ report.summary.cannotConfirmCount }}</span><span>已忽略 {{ report.summary.ignoredCount }}</span></div>
            <article v-for="finding in report.findings" :key="finding.findingId" class="finding" :data-finding-id="finding.findingId">
              <div class="section-title"><h3>{{ findingLabels[finding.type] || finding.type }}<span v-if="finding.ignored" class="tag">已忽略 · 记录保留</span></h3><span>{{ reportRequirement(finding.requirementId)?.displayName || '' }}</span></div>
              <p>{{ findingDescriptions[finding.type] || finding.message }}</p><p v-if="finding.cannotConfirmReason">需人工确认原因：{{ finding.cannotConfirmReason }}</p>
              <p class="muted">{{ remediation[finding.type] }}</p>
              <div class="two-columns"><div><h3>规则依据</h3><p v-if="reportRequirement(finding.requirementId)" class="source-refs">{{ reportRequirement(finding.requirementId).displayName }} · 至少 {{ reportRequirement(finding.requirementId).minimumCount }} 份<br>{{ ruleText(reportRequirement(finding.requirementId).fileNameRule) }}<br>{{ reportRequirement(finding.requirementId).origin === 'USER_ADDED' ? '用户新增规则' : '源自官方提取' }}{{ reportRequirement(finding.requirementId).authorship === 'USER_REWRITTEN' ? '，已由用户修订' : '' }}</p><ReviewEvidence :items="finding.ruleEvidence" /></div><div><h3>材料证据</h3><ReviewEvidence :items="finding.materialEvidence" /></div></div>
              <div v-if="!finding.ignored && report.freshness === 'CURRENT'" class="inline"><input v-model="ignoreReasons[finding.findingId]" aria-label="忽略理由（可选）" placeholder="忽略理由（可选）"><button @click="ignore(finding)">记录忽略（仍不算通过）</button></div>
            </article>
            <p v-if="!report.findings.length" class="muted">本报告范围内未发现缺失、命名或无法确认的问题。不代表资格或内容审查通过。</p>
          </template>
          <p v-else class="empty">尚无可展示的审查报告。</p>
        </section>
      </fieldset>
    </template>
    <p class="scope-note">本阶段不检查资格、正文填写、跨材料事实或印章真伪；不会自动提交申请或覆盖原件。</p>
  </main>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { reviewM0Api as api, reviewError } from '../../api/reviewM0'
import { runningJob, useReviewM0 } from '../../composables/useReviewM0'
import FilePicker from './ReviewFilePicker.vue'
import ReviewEvidence from './ReviewEvidence.vue'
import './reviewM0.css'
const route = useRoute(), router = useRouter()
const state = useReviewM0(api, values => router.replace({ query: { ...route.query, ...values } }))
const { current, bases, draft, snapshot, sources, materials, assignments, report, reportChecklist, job, uploadConfirmation, busy, loading, loadFailed, error, ready, refresh, mutate, startReview, confirmUpload, showLatestReport, resumePoll, cancel } = state
const tabs = [{ id: 'rules', label: '1 规则确认' }, { id: 'materials', label: '2 材料与分配' }, { id: 'report', label: '3 审查结果' }, { id: 'bases', label: '官方依据' }]
const tab = ref('rules'), acknowledged = ref([]), uploadAcknowledged = ref(false), selectedAssignments = ref([])
const basisFile = ref(null), materialFile = ref(null), basisRole = ref('APPLICATION_NOTICE'), submissionName = ref(''), editing = ref(null), changedMaterialId = ref('')
const renames = reactive({}), ignoreReasons = reactive({})
const ruleForm = reactive({ displayName: '', enabled: true, applicability: 'NEEDS_USER_DECISION', minimumCount: 1, kind: 'NONE', pattern: '', caseSensitive: null })
const disabled = computed(() => busy.value || loading.value || loadFailed.value)
const unprocessedBases = computed(() => bases.value.some(b => !['READY', 'DETACHED'].includes(b.status)))
const activeRequirements = computed(() => snapshot.value?.items?.filter(i => i.enabled && i.applicability === 'APPLIES') || [])
const enabledRequirements = computed(() => draft.value?.items?.filter(i => i.enabled) || [])
const canPublish = computed(() => draft.value?.lifecycle === 'CANDIDATE_DRAFT' && !unprocessedBases.value && enabledRequirements.value.length > 0 && enabledRequirements.value.every(i => i.applicability !== 'NEEDS_USER_DECISION' && acknowledged.value.includes(i.requirementId)))
const validRule = computed(() => ruleForm.displayName.trim() && Number.isInteger(ruleForm.minimumCount) && ruleForm.minimumCount > 0 && (ruleForm.kind === 'NONE' || (ruleForm.pattern && typeof ruleForm.caseSensitive === 'boolean')))
const isZip = computed(() => /\.zip$/i.test(materialFile.value?.name || ''))
const jobRunning = computed(() => runningJob(job.value?.status))
const assignmentKey = (materialId, requirementId) => JSON.stringify([materialId, requirementId])
const savedAssignmentKeys = computed(() => assignments.value.assignments.filter(a => a.status === 'USER_CONFIRMED').map(a => assignmentKey(a.materialId, a.requirementId)).sort())
const assignmentsDirty = computed(() => JSON.stringify([...selectedAssignments.value].sort()) !== JSON.stringify(savedAssignmentKeys.value))
const basisRoles = { APPLICATION_NOTICE: '申报通知', FILLING_GUIDE: '填写说明', OFFICIAL_TEMPLATE: '官方模板' }
const applicability = { APPLIES: '适用', DOES_NOT_APPLY: '不适用', NEEDS_USER_DECISION: '待明确适用性' }
const statusLabels = { ATTACHED: '已附加，尚未处理', READY: '已处理', DETACHED: '已移除引用', AVAILABLE: '可用', CANNOT_CONFIRM: '需人工确认', REMOVED: '已移除' }
const jobLabels = { QUEUED: '排队中', RUNNING: '执行中', CANCEL_REQUESTED: '正在取消', CANCELLED: '已取消', SUCCEEDED: '已完成', FAILED: '失败' }
const findingLabels = { MISSING: '材料缺失', FILE_NAME_NONCOMPLIANT: '命名不符合要求', CANNOT_CONFIRM: '需人工确认' }
const findingDescriptions = { MISSING: '已确认用途的可用材料数量不足，请对照本项规则和材料记录核查。', FILE_NAME_NONCOMPLIANT: '当前提交副本名称未满足已确认的命名要求，请对照下方记录修改。', CANNOT_CONFIRM: '现有依据或材料不足以形成确定结论，请先核对诊断。' }
const remediation = { MISSING: '补充对应材料，明确分配用途并重新确认上传后复审。', FILE_NAME_NONCOMPLIANT: '核对规则后修改提交副本名称，再进行复审；原件保持不变。', CANNOT_CONFIRM: '核对输入诊断，替换有问题的源文件或移除本次引用后重新检查。' }
const formatTime = value => value ? new Date(value).toLocaleString() : '—'
const ruleText = rule => rule?.kind === 'NONE' ? '无项目级命名要求（仍检查安全命名与扩展名）' : `${rule?.kind === 'EXACT' ? '完整文件名' : '完整匹配正则'}：${rule?.pattern}（${rule?.caseSensitive ? '区分' : '不区分'}大小写）`
const locationText = loc => loc ? [loc.entryPath, loc.page && `第 ${loc.page} 页`, loc.sheet, loc.cellRange, loc.block].filter(Boolean).join(' · ') : '未提供'
const reportRequirement = id => reportChecklist.value?.items?.find(i => i.requirementId === id)
function resetRule() { editing.value = null; Object.assign(ruleForm, { displayName: '', enabled: true, applicability: 'NEEDS_USER_DECISION', minimumCount: 1, kind: 'NONE', pattern: '', caseSensitive: null }) }
function editRule(item) { editing.value = item; Object.assign(ruleForm, { displayName: item.displayName, enabled: item.enabled, applicability: item.applicability, minimumCount: item.minimumCount, kind: item.fileNameRule.kind, pattern: item.fileNameRule.pattern || '', caseSensitive: item.fileNameRule.caseSensitive }) }
async function saveRequirement() {
  if (!validRule.value) return
  const body = { displayName: ruleForm.displayName.trim(), enabled: ruleForm.enabled, applicability: ruleForm.applicability, minimumCount: ruleForm.minimumCount, fileNameRule: { kind: ruleForm.kind, pattern: ruleForm.kind === 'NONE' ? null : ruleForm.pattern, caseSensitive: ruleForm.kind === 'NONE' ? null : ruleForm.caseSensitive } }
  const item = editing.value
  if (item) body.expectedVersion = item.itemVersion
  const result = await mutate(item ? `edit:${item.requirementId}` : 'add-rule', body, key => item ? api.editRequirement(current.value.caseId, item.requirementId, body, key) : api.addRequirement(current.value.caseId, body, key))
  if (result) resetRule()
}
async function publish() { const body = { expectedChecklistRevision: draft.value.revision, acknowledgedRequirementIds: [...acknowledged.value].sort() }; await mutate('publish', body, key => api.publish(current.value.caseId, body, key)) }
async function attachBasis() { const body = { fileId: basisFile.value.id, role: basisRole.value }; await mutate('attach-basis', body, key => api.attachBasis(current.value.caseId, body, key)) }
async function classifyBasis(basis, role) { const body = { expectedVersion: basis.version, role }; await mutate(`classify:${basis.basisId}`, body, key => api.classifyBasis(current.value.caseId, basis.basisId, body, key)) }
async function detachBasis(basis) { await mutate(`detach:${basis.basisId}`, { expectedVersion: basis.version }, key => api.detachBasis(current.value.caseId, basis.basisId, basis.version, key)) }
function selectMaterialFile(file) { materialFile.value = file; submissionName.value = file?.name || '' }
async function attachMaterial() { const body = { fileId: materialFile.value.id, submissionFileName: isZip.value ? null : submissionName.value.trim() }; await mutate('attach-material', body, key => api.attachSource(current.value.caseId, body, key)) }
async function removeSource(source) { await mutate(`remove:${source.sourceId}`, { expectedVersion: source.version }, key => api.removeSource(current.value.caseId, source.sourceId, source.version, key)) }
async function renameMaterial(material) { const body = { expectedVersion: material.version, submissionFileName: renames[material.materialId].trim() }; const result = await mutate(`rename:${material.materialId}`, body, key => api.rename(current.value.caseId, material.materialId, body, key)); if (result) changedMaterialId.value = material.materialId }
async function saveAssignments() { const body = { expectedAssignmentRevision: assignments.value.assignmentRevision, assignments: selectedAssignments.value.map(value => { const [materialId, requirementId] = JSON.parse(value); return { materialId, requirementId, status: 'USER_CONFIRMED' } }) }; await mutate('assignments', body, key => api.saveAssignments(current.value.caseId, body, key)) }
async function ignore(finding) { const body = { reportId: report.value.reportId, findingId: finding.findingId, reason: ignoreReasons[finding.findingId] || null }; await mutate('ignore', body, key => api.ignore(current.value.caseId, body.reportId, body.findingId, body.reason, key)) }
async function download(material) { try { const value = await api.download(current.value.caseId, material.materialId); const url = URL.createObjectURL(value.blob); const a = document.createElement('a'); a.href = url; a.download = value.name; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000) } catch (failure) { error.value = reviewError(failure) } }
watch(() => route.params.caseId, async id => { resetRule(); basisFile.value = null; materialFile.value = null; acknowledged.value = []; selectedAssignments.value = []; uploadAcknowledged.value = false; Object.keys(renames).forEach(key => delete renames[key]); Object.keys(ignoreReasons).forEach(key => delete ignoreReasons[key]); tab.value = route.query.report || route.query.job ? 'report' : 'rules'; await state.open(id, { report: typeof route.query.report === 'string' ? route.query.report : null, job: typeof route.query.job === 'string' ? route.query.job : null }) }, { immediate: true })
watch(() => draft.value?.revision, () => { acknowledged.value = [] })
watch(() => assignments.value.assignmentRevision, () => { selectedAssignments.value = [...savedAssignmentKeys.value] })
watch(() => current.value?.materialSetRevision, () => { uploadAcknowledged.value = false })
watch(() => current.value?.currentChecklistSnapshotId, () => { uploadAcknowledged.value = false })
</script>
