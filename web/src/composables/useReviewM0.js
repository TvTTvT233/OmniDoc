import { computed, onBeforeUnmount, ref } from 'vue'
import { reviewM0Api, reviewError } from '../api/reviewM0'

export const runningJob = status => ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED'].includes(status)
export const reportReady = report => report?.freshness === 'CURRENT'
  && (report.effectiveAssessment || report.assessment) === 'M0_READY'
  && !report.findings?.some(f => f.ignored)

// A failed write keeps its key for an explicit retry of the same payload.
export function createMutationKeys() {
  const keys = new Map()
  return {
    get(scope, body) { const fingerprint = JSON.stringify([scope, body]); if (!keys.has(fingerprint)) keys.set(fingerprint, crypto.randomUUID()); return keys.get(fingerprint) },
    complete(scope, body) { keys.delete(JSON.stringify([scope, body])) },
    clear() { keys.clear() },
  }
}

export function useReviewM0(api = reviewM0Api, remember = () => {}) {
  const current = ref(null), bases = ref([]), draft = ref(null), snapshot = ref(null)
  const sources = ref([]), materials = ref([]), assignments = ref({ assignmentRevision: 0, assignments: [] })
  const report = ref(null), reportChecklist = ref(null), job = ref(null), uploadConfirmation = ref(null)
  const busy = ref(false), loading = ref(false), error = ref(null), loadFailed = ref(false)
  const ready = computed(() => !loading.value && !loadFailed.value && reportReady(report.value))
  const keys = createMutationKeys()
  let epoch = 0, timer = null, caseId = null, pollSerial = 0, refreshSerial = 0, explicitReport = false, trackingJob = false
  const stopPolling = () => { clearTimeout(timer); timer = null; pollSerial++ }

  async function refresh() {
    if (!caseId) return
    const expected = epoch, serial = ++refreshSerial, target = caseId
    loading.value = true
    try {
      const caseRecord = await api.case(target)
      const values = await Promise.all([api.bases(target), api.draft(target), api.sources(target), api.materials(target), api.assignments(target),
        caseRecord.currentChecklistSnapshotId ? api.snapshot(target, caseRecord.currentChecklistSnapshotId) : null,
        report.value?.reportId || caseRecord.currentReportId ? api.report(target, report.value?.reportId || caseRecord.currentReportId) : null])
      const evidenceChecklist = values[6]?.checklistSnapshotId ? await api.snapshot(target, values[6].checklistSnapshotId) : null
      if (epoch !== expected || serial !== refreshSerial) return
      current.value = caseRecord
      ;[bases.value, draft.value, sources.value, materials.value, assignments.value, snapshot.value, report.value] =
        [values[0].items, values[1], values[2].items, values[3].items, values[4], values[5], values[6]]
      reportChecklist.value = evidenceChecklist
      if (uploadConfirmation.value && (uploadConfirmation.value.checklistSnapshotId !== caseRecord.currentChecklistSnapshotId || uploadConfirmation.value.materialSetRevision !== caseRecord.materialSetRevision)) uploadConfirmation.value = null
      if (report.value && !trackingJob) remember({ report: report.value.reportId })
      loadFailed.value = false
    } catch (failure) { if (epoch === expected && serial === refreshSerial) { error.value = reviewError(failure); loadFailed.value = true } }
    finally { if (epoch === expected && serial === refreshSerial) loading.value = false }
  }

  async function poll(jobId, expected = epoch, serial = ++pollSerial) {
    try {
      const value = await api.job(caseId, jobId)
      if (expected !== epoch || serial !== pollSerial) return
      job.value = value
      if (runningJob(value.status)) timer = setTimeout(() => poll(jobId, expected, serial), 1500)
      else {
        trackingJob = false
        if (!explicitReport && value.status === 'SUCCEEDED' && value.resultRef?.resultType === 'REPORT') report.value = { reportId: value.resultRef.resultId }
        await refresh()
      }
    } catch (failure) { if (expected === epoch && serial === pollSerial) error.value = reviewError(failure) }
  }

  async function open(id, pointers = {}) {
    epoch++; stopPolling(); caseId = id; explicitReport = Boolean(pointers.report); trackingJob = Boolean(pointers.job) && !explicitReport; keys.clear(); error.value = null; busy.value = false
    current.value = null; bases.value = []; draft.value = null; snapshot.value = null; sources.value = []; materials.value = []
    assignments.value = { assignmentRevision: 0, assignments: [] }; report.value = pointers.report ? { reportId: pointers.report } : null
    reportChecklist.value = null; job.value = null; uploadConfirmation.value = null
    await refresh()
    if (caseId === id && !loadFailed.value && pointers.job) await poll(pointers.job)
  }

  async function mutate(scope, body, operation) {
    if (busy.value || loading.value || loadFailed.value || !current.value) return null
    const expected = epoch
    busy.value = true; error.value = null
    try {
      const result = await operation(keys.get(`${caseId}:${scope}`, body))
      keys.complete(`${caseId}:${scope}`, body)
      if (expected !== epoch) return null
      await refresh()
      return result
    } catch (failure) { if (expected === epoch) error.value = reviewError(failure); return null }
    finally { if (expected === epoch) busy.value = false }
  }

  async function startReview(mode = 'FULL', changedMaterialIds = []) {
    if (!snapshot.value || !uploadConfirmation.value || runningJob(job.value?.status)) return
    const body = { mode, checklistSnapshotId: snapshot.value.checklistSnapshotId,
      uploadConfirmationId: uploadConfirmation.value.uploadConfirmationId,
      baseReportId: mode === 'INCREMENTAL' ? report.value?.reportId : null, changedMaterialIds }
    const result = await mutate('review', body, key => api.review(caseId, body, key))
    if (result) { explicitReport = false; trackingJob = true; job.value = result; remember({ job: result.jobId, report: undefined }); stopPolling(); await poll(result.jobId) }
  }

  async function confirmUpload() {
    if (!snapshot.value) return
    const body = { checklistSnapshotId: snapshot.value.checklistSnapshotId, materialSetRevision: current.value.materialSetRevision, acknowledgedUploadComplete: true }
    const result = await mutate('confirm-upload', body, key => api.confirmUpload(caseId, body, key))
    if (result) uploadConfirmation.value = result
  }

  onBeforeUnmount(() => { epoch++; stopPolling() })
  return { current, bases, draft, snapshot, sources, materials, assignments, report, reportChecklist, job, uploadConfirmation, busy, loading, loadFailed, error, ready,
    open, refresh, mutate, startReview, confirmUpload, showLatestReport: () => { stopPolling(); explicitReport = false; trackingJob = false; job.value = null; report.value = null; remember({ job: undefined, report: undefined }); return refresh() }, resumePoll: () => { if (job.value) { stopPolling(); return poll(job.value.jobId) } },
    async cancel() { if (!job.value) return; const value = await mutate('cancel', { jobId: job.value.jobId }, key => api.cancel(caseId, job.value.jobId, key)); if (value) { job.value = value; stopPolling(); await poll(value.jobId) } },
  }
}
