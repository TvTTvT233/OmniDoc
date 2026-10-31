import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createMutationKeys, reportReady, useReviewM0 } from './useReviewM0'
vi.mock('../api/reviewM0', () => ({ reviewM0Api: {}, reviewError: error => ({ code: error.code || 'FAILED', message: 'failed' }) }))
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const record = id => ({ caseId: id, checklistRevision: 0, materialSetRevision: 0, currentChecklistSnapshotId: null, currentReportId: null })
function makeApi() {
  return { case: vi.fn(async id => record(id)), bases: vi.fn(async () => ({ items: [] })), draft: vi.fn(), sources: vi.fn(async () => ({ items: [] })), materials: vi.fn(async () => ({ items: [] })), assignments: vi.fn(async () => ({ assignmentRevision: 0, assignments: [] })), snapshot: vi.fn(), report: vi.fn(), job: vi.fn(), review: vi.fn(), confirmUpload: vi.fn(), cancel: vi.fn() }
}
const wrappers = []
function harness(api, remember = vi.fn()) { let state; const wrapper = mount(defineComponent({ setup() { state = useReviewM0(api, remember); return () => null } })); wrappers.push(wrapper); return state }
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.useRealTimers() })
describe('Review M0 state boundaries', () => {
  it('zero published revision still loads manually added draft rules', async () => { const api = makeApi(), s = harness(api); api.draft.mockResolvedValue({ revision: 2, items: [{ displayName: 'Application' }] }); await s.open('a'); expect(s.current.value.checklistRevision).toBe(0); expect(s.loadFailed.value).toBe(false); expect(s.draft.value.items[0].displayName).toBe('Application') })
  it('late previous-case response cannot replace the active case', async () => { const api = makeApi(), d = deferred(); api.case.mockImplementation(id => id === 'old' ? d.promise : Promise.resolve(record(id))); const s = harness(api), old = s.open('old'); await s.open('new'); d.resolve(record('old')); await old; expect(s.current.value.caseId).toBe('new') })
  it('late earlier refresh cannot overwrite newer data', async () => { const api = makeApi(), s = harness(api); await s.open('a'); const d = deferred(); api.case.mockReturnValueOnce(d.promise).mockResolvedValueOnce({ ...record('a'), version: 2 }); const old = s.refresh(); await s.refresh(); d.resolve({ ...record('a'), version: 1 }); await old; expect(s.current.value.version).toBe(2) })
  it('server read failure disables mutation and does not imply Ready', async () => { const api = makeApi(), s = harness(api); await s.open('a'); s.report.value = { freshness: 'CURRENT', assessment: 'M0_READY', findings: [] }; api.case.mockRejectedValueOnce(new Error()); await s.refresh(); const op = vi.fn(); await s.mutate('x', {}, op); expect(s.loadFailed.value).toBe(true); expect(s.ready.value).toBe(false); expect(op).not.toHaveBeenCalled() })
  it('same failed payload retries with same idempotency key, changed payload uses a new key', async () => { const keys = createMutationKeys(); const key = keys.get('case:write', { a: 1 }); expect(keys.get('case:write', { a: 1 })).toBe(key); expect(keys.get('case:write', { a: 2 })).not.toBe(key); keys.complete('case:write', { a: 1 }); expect(keys.get('case:write', { a: 1 })).not.toBe(key) })
  it('does not allow overlapping mutations', async () => { const api = makeApi(), s = harness(api), d = deferred(); await s.open('a'); const first = s.mutate('x', {}, () => d.promise), second = vi.fn(); await s.mutate('y', {}, second); expect(second).not.toHaveBeenCalled(); d.resolve({ ok: true }); await first })
  it('old report resolves its own checklist snapshot, not latest rules or report', async () => { const api = makeApi(); api.case.mockResolvedValue({ ...record('a'), currentChecklistSnapshotId: 'new', currentReportId: 'r2' }); api.snapshot.mockImplementation(async (id, sid) => ({ checklistSnapshotId: sid, items: [{ displayName: sid }] })); api.report.mockResolvedValue({ reportId: 'r1', checklistSnapshotId: 'old', freshness: 'OUTDATED' }); const s = harness(api); await s.open('a', { report: 'r1' }); expect(api.report).toHaveBeenCalledWith('a', 'r1'); expect(s.snapshot.value.items[0].displayName).toBe('new'); expect(s.reportChecklist.value.items[0].displayName).toBe('old'); expect(s.ready.value).toBe(false) })
  it('material revision invalidates the locally held upload confirmation', async () => { const api = makeApi(), s = harness(api); await s.open('a'); s.uploadConfirmation.value = { checklistSnapshotId: null, materialSetRevision: 0 }; api.case.mockResolvedValue({ ...record('a'), materialSetRevision: 1 }); await s.refresh(); expect(s.uploadConfirmation.value).toBe(null) })
  it('explicit old report survives unrelated completed job; a new review and latest selection still advance', async () => {
    const api = makeApi(), remember = vi.fn(), s = harness(api, remember)
    api.case.mockResolvedValue({ ...record('a'), currentChecklistSnapshotId: 'new', currentReportId: 'r2' })
    api.snapshot.mockImplementation(async (_, id) => ({ checklistSnapshotId: id }))
    api.report.mockImplementation(async (_, id) => ({ reportId: id, checklistSnapshotId: id === 'r1' ? 'old' : 'new' }))
    api.job.mockResolvedValue({ jobId: 'j2', status: 'SUCCEEDED', resultRef: { resultType: 'REPORT', resultId: 'r2' } })
    await s.open('a', { report: 'r1', job: 'j2' })
    expect(s.report.value.reportId).toBe('r1'); expect(s.reportChecklist.value.checklistSnapshotId).toBe('old')
    await s.resumePoll(); expect(s.report.value.reportId).toBe('r1')
    s.uploadConfirmation.value = { uploadConfirmationId: 'u1', checklistSnapshotId: 'new', materialSetRevision: 0 }
    api.review.mockResolvedValue({ jobId: 'j3', status: 'QUEUED' })
    api.job.mockResolvedValue({ jobId: 'j3', status: 'SUCCEEDED', resultRef: { resultType: 'REPORT', resultId: 'r3' } })
    await s.startReview(); expect(s.report.value.reportId).toBe('r3')
    await s.showLatestReport(); expect(s.report.value.reportId).toBe('r2'); expect(s.job.value).toBe(null)
    expect(remember).toHaveBeenCalledWith({ job: undefined, report: undefined })
  })
  it('refresh can resume a real persisted job and stops polling on unmount', async () => { vi.useFakeTimers(); const api = makeApi(); api.job.mockResolvedValue({ jobId: 'j1', status: 'RUNNING' }); const s = harness(api); await s.open('a', { job: 'j1' }); expect(s.job.value.status).toBe('RUNNING'); expect(api.job).toHaveBeenCalledWith('a', 'j1'); wrappers.pop().unmount(); await vi.advanceTimersByTimeAsync(3000); expect(api.job).toHaveBeenCalledTimes(1) })
  it('failed job remains a failure and creates no fake report', async () => { const api = makeApi(); api.job.mockResolvedValue({ jobId: 'j1', status: 'FAILED', error: { errorCode: 'STORAGE_UNAVAILABLE' } }); const s = harness(api); await s.open('a', { job: 'j1' }); expect(s.job.value.status).toBe('FAILED'); expect(s.report.value).toBe(null) })
  it('resuming an active job does not pin the previous report into the URL before completion', async () => {
    vi.useFakeTimers(); const api = makeApi(), remember = vi.fn(), s = harness(api, remember)
    api.case.mockResolvedValue({ ...record('a'), currentReportId: 'old' }); api.report.mockImplementation(async (_, id) => ({ reportId: id }))
    api.job.mockResolvedValueOnce({ jobId: 'new-job', status: 'RUNNING' }).mockResolvedValue({ jobId: 'new-job', status: 'SUCCEEDED', resultRef: { resultType: 'REPORT', resultId: 'new' } })
    await s.open('a', { job: 'new-job' }); expect(remember).not.toHaveBeenCalled(); expect(s.report.value.reportId).toBe('old')
    await vi.advanceTimersByTimeAsync(1500); expect(s.report.value.reportId).toBe('new'); expect(remember).toHaveBeenCalledWith({ report: 'new' })
  })
  it('full review is not submitted without current upload confirmation', async () => { const api = makeApi(), s = harness(api); await s.open('a'); await s.startReview(); expect(api.review).not.toHaveBeenCalled() })
  it('ignored or outdated reports never render Ready', () => { expect(reportReady({ freshness: 'CURRENT', assessment: 'M0_READY', findings: [] })).toBe(true); expect(reportReady({ freshness: 'OUTDATED', assessment: 'M0_READY' })).toBe(false); expect(reportReady({ freshness: 'CURRENT', assessment: 'M0_READY', effectiveAssessment: 'M0_NOT_READY' })).toBe(false); expect(reportReady({ freshness: 'CURRENT', assessment: 'M0_READY', findings: [{ ignored: true }] })).toBe(false) })
})
