import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import EvaluationCenter from './EvaluationCenter.vue'
import { calculateComparison, parseCatalog, validateCatalog } from '../../utils/evaluationCatalog.js'

const fixture = () => {
  const id = 'agent-01'
  const run = { key: 'run:20261111T000000Z-aaaaaaaa', kind: 'run', runId: '20261111T000000Z-aaaaaaaa', createdAtUtc: '2026-11-11T00:00:00Z', code: { commit: 'a'.repeat(40), dirty: false, treeDigest: 'b'.repeat(64) }, dataset: { digest: 'c'.repeat(64), fixtureDigest: 'd'.repeat(64) }, grader: { version: 'deterministic-junit-v1', digest: 'e'.repeat(64) }, runnerOs: 'Windows', repeats: 1, elapsedSeconds: 5, summary: { total: 1, passed: 1, passRate: 1, criticalFailures: [], gate: 'PASS' }, infrastructureFailureCount: 0, cases: [{ id, trials: [{ trial: 1, status: 'PASSED', reason: null, durationMs: 10 }] }] }
  const baseline = JSON.parse(JSON.stringify(run)); baseline.kind = 'baseline'; baseline.key = 'baseline:approved'
  return { schemaVersion: 1, mode: 'deterministic-components', generatedAtUtc: '2026-11-11T00:00:00Z', omittedReports: 0, cases: [{ id, suiteId: 'agent', title: '恢复任务', task: '测试任务', expectedOutcome: '复用完成结果', critical: true, evidenceLevel: 'COMPONENT' }], runs: [baseline, run], comparisons: [calculateComparison(baseline, run)] }
}
const mountPage = () => mount(EvaluationCenter, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
const response = (value, status = 200) => ({ status, ok: status === 200, headers: new Headers(), text: async () => JSON.stringify(value) })
afterEach(() => vi.unstubAllGlobals())
describe('safe evaluation catalogue', () => {
  it('accepts a complete consistent catalogue', () => expect(validateCatalog(fixture()).runs).toHaveLength(2))
  it.each([
    ['unknown fields', v => { v.runs[0].rawLog = 'private' }],
    ['forged green summary', v => { v.runs[1].cases[0].trials[0] = { trial: 1, status: 'FAILED', reason: 'ASSERTION_FAILED', durationMs: 1 } }],
    ['duplicate run', v => { v.runs.push(v.runs[1]) }],
    ['unknown case', v => { v.runs[1].cases[0].id = 'other' }],
    ['missing comparisons', v => { v.comparisons = [] }],
    ['forged comparison', v => { v.comparisons[0].changes[0].candidatePassRate = 0 }],
    ['invalid duration', v => { v.runs[1].cases[0].trials[0].durationMs = -1 }],
    ['raw reason', v => { v.runs[1].cases[0].trials[0].reason = 'private message' }],
    ['failed baseline', v => { v.runs[0].infrastructureFailureCount = 1; v.runs[0].summary.gate = 'FAIL' }]
  ])('rejects %s', (_, modify) => { const v = fixture(); modify(v); expect(() => validateCatalog(v)).toThrow() })
  it('keeps skipped candidate failing and marks regression', () => {
    const v = fixture(), r = v.runs[1]; r.cases[0].trials[0].status = 'SKIPPED'; r.cases[0].trials[0].reason = 'TEST_SKIPPED'; r.summary = { total: 1, passed: 0, passRate: 0, criticalFailures: ['agent-01'], gate: 'FAIL' }; v.comparisons = [calculateComparison(v.runs[0], r)]
    expect(validateCatalog(v).comparisons[0].changes[0].regressed).toBe(true)
  })
  it('blocks incompatible environment', () => { const v = fixture(); v.runs[1].runnerOs = 'Linux'; v.comparisons = [calculateComparison(...v.runs)]; expect(validateCatalog(v).comparisons[0].gate).toBe('BLOCKED') })
  it('rejects oversized import and invalid JSON', () => { expect(() => parseCatalog(' '.repeat(5 * 1024 * 1024 + 1))).toThrow(); expect(() => parseCatalog('{')).toThrow() })
})
describe('evaluation center states', () => {
  it('shows 404 as empty without inventing results', async () => { vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(null, 404))); const w = mountPage(); await flushPromises(); expect(w.text()).toContain('还没有评测报告'); expect(w.find('[role="alert"]').exists()).toBe(false); w.unmount() })
  it('shows network error without raw response', async () => { vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('private'))); const w = mountPage(); await flushPromises(); expect(w.find('[role="alert"]').text()).toContain('报告加载或校验失败'); expect(w.text()).not.toContain('private'); w.unmount() })
  it('displays details, comparison and status filter', async () => { vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(fixture()))); const w = mountPage(); await flushPromises(); expect(w.text()).toContain('当前固定场景未发现退化'); expect(w.text()).toContain('恢复任务'); await w.get('[aria-label="状态筛选"]').setValue('FAIL'); expect(w.text()).toContain('当前筛选下没有运行记录'); expect(fetch).toHaveBeenCalledWith('/evals/catalog.json', { cache: 'no-store', credentials: 'omit' }); w.unmount() })
  it('renders imported text without HTML interpretation', async () => { const v = fixture(); v.cases[0].title = '<img src=x onerror=alert(1)>'; vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(v))); const w = mountPage(); await flushPromises(); expect(w.text()).toContain('<img src=x onerror=alert(1)>'); expect(w.find('img').exists()).toBe(false); w.unmount() })
  it('imports locally without a POST and rejects oversize', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(null, 404))); const w = mountPage(); await flushPromises(); const input = w.get('input[type=file]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [{ size: 100, text: async () => JSON.stringify(fixture()) }] }); await input.trigger('change'); await flushPromises(); expect(w.text()).toContain('来源：手工导入'); expect(fetch).toHaveBeenCalledTimes(1)
    Object.defineProperty(input.element, 'files', { configurable: true, value: [{ size: 6 * 1024 * 1024 }] }); await input.trigger('change'); await flushPromises(); expect(w.text()).toContain('导入失败'); expect(w.text()).not.toContain('当前固定场景未发现退化'); w.unmount()
  })
  it('ignores stale load after a newer import', async () => { let resolve; vi.stubGlobal('fetch', vi.fn(() => new Promise(r => { resolve = r }))); const w = mountPage(); const input = w.get('input[type=file]'); Object.defineProperty(input.element, 'files', { value: [{ size: 100, text: async () => JSON.stringify(fixture()) }] }); await input.trigger('change'); await flushPromises(); resolve(response(null, 404)); await flushPromises(); expect(w.text()).toContain('来源：手工导入'); w.unmount() })
})
