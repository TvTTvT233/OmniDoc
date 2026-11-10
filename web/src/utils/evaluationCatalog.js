export const MAX_CATALOG_BYTES = 5 * 1024 * 1024
const check = (ok) => { if (!ok) throw new Error('报告格式或一致性校验失败，请重新导出安全目录。') }
const keys = (v, names) => check(v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).sort().join(',') === names.split(' ').sort().join(','))
const arr = (v, max = 200) => check(Array.isArray(v) && v.length <= max)
const num = (v) => check(typeof v === 'number' && Number.isFinite(v) && v >= 0)
const integer = (v) => { num(v); check(Number.isInteger(v)) }
const text = (v, max = 4000) => check(typeof v === 'string' && v.length > 0 && v.length <= max)
const hash = (v, n = 64) => check(typeof v === 'string' && new RegExp(`^[a-f0-9]{${n}}$`).test(v))
const date = (v) => { text(v, 40); check(/^\d{4}-\d\d-\d\dT/.test(v) && Number.isFinite(Date.parse(v))) }
const unique = (values) => check(new Set(values).size === values.length)
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b)
const reasons = { PASSED: [null], FAILED: ['ASSERTION_FAILED'], SKIPPED: ['TEST_SKIPPED'], MISSING: ['NO_FRESH_TEST_RESULT'], ERROR: ['TEST_EXECUTION_ERROR', 'MISSING_OR_DUPLICATE_TESTCASE', 'INVALID_TEST_XML'] }

export function calculateComparison(b, c) {
  let reason = null
  if (b.dataset.digest !== c.dataset.digest || b.dataset.fixtureDigest !== c.dataset.fixtureDigest) reason = 'INCOMPATIBLE_CONFIGURATION'
  else if (b.grader.version !== c.grader.version || b.grader.digest !== c.grader.digest) reason = 'INCOMPATIBLE_CONFIGURATION'
  else if (b.runnerOs !== c.runnerOs) reason = 'INCOMPATIBLE_CONFIGURATION'
  if (reason) return { baselineKey: b.key, candidateKey: c.key, gate: 'BLOCKED', reason, changes: [] }
  const rate = (trials) => trials.filter(t => t.status === 'PASSED').length / trials.length
  const mean = (trials) => { const ds = trials.filter(t => t.durationMs !== null); return ds.length ? ds.reduce((s, t) => s + t.durationMs, 0) / ds.length : null }
  const changes = c.cases.map(item => {
    const old = b.cases.find(x => x.id === item.id)
    const oldRate = rate(old.trials), newRate = rate(item.trials), before = mean(old.trials), after = mean(item.trials)
    return { id: item.id, baselinePassRate: oldRate, candidatePassRate: newRate, regressed: newRate < oldRate, meanDurationDeltaMs: before === null || after === null ? null : after - before }
  })
  return { baselineKey: b.key, candidateKey: c.key, gate: c.summary.gate === 'PASS' && !changes.some(x => x.regressed) ? 'PASS' : 'FAIL', reason: null, changes }
}

export function validateCatalog(v) {
  keys(v, 'schemaVersion mode generatedAtUtc omittedReports cases runs comparisons')
  check(v.schemaVersion === 1 && v.mode === 'deterministic-components'); date(v.generatedAtUtc); integer(v.omittedReports)
  arr(v.cases); check(v.cases.length > 0); arr(v.runs); arr(v.comparisons, 40000)
  unique(v.cases.map(c => c.id)); unique(v.runs.map(r => r.key))
  for (const c of v.cases) {
    keys(c, 'id suiteId title task expectedOutcome critical evidenceLevel')
    check(/^[a-zA-Z0-9_-]{1,80}$/.test(c.id) && ['agent', 'document', 'review'].includes(c.suiteId) && typeof c.critical === 'boolean')
    for (const k of ['title', 'task', 'expectedOutcome', 'evidenceLevel']) text(c[k])
  }
  for (const r of v.runs) {
    keys(r, 'key kind runId createdAtUtc code dataset grader runnerOs repeats elapsedSeconds summary infrastructureFailureCount cases')
    check(/^\d{8}T\d{6}Z-[a-f0-9]{8}$/.test(r.runId)); date(r.createdAtUtc)
    check(r.kind === 'run' ? r.key === `run:${r.runId}` : r.kind === 'baseline' && /^baseline:[a-zA-Z0-9_-]{1,100}$/.test(r.key))
    keys(r.code, 'commit dirty treeDigest'); hash(r.code.commit, 40); hash(r.code.treeDigest); check(typeof r.code.dirty === 'boolean')
    keys(r.dataset, 'digest fixtureDigest'); hash(r.dataset.digest); hash(r.dataset.fixtureDigest)
    keys(r.grader, 'version digest'); check(r.grader.version === 'deterministic-junit-v1'); hash(r.grader.digest)
    check(['Windows', 'Linux', 'Darwin'].includes(r.runnerOs)); integer(r.repeats); check(r.repeats >= 1 && r.repeats <= 10)
    num(r.elapsedSeconds); integer(r.infrastructureFailureCount); arr(r.cases); check(r.cases.length === v.cases.length)
    unique(r.cases.map(c => c.id))
    for (const c of r.cases) {
      keys(c, 'id trials'); check(v.cases.some(d => d.id === c.id)); arr(c.trials, 10); check(c.trials.length === r.repeats)
      c.trials.forEach((t, i) => { keys(t, 'trial status reason durationMs'); check(t.trial === i + 1 && Object.hasOwn(reasons, t.status) && reasons[t.status].includes(t.reason)); if (t.durationMs !== null) num(t.durationMs) })
    }
    keys(r.summary, 'total passed passRate criticalFailures gate'); arr(r.summary.criticalFailures)
    const failed = r.cases.filter(c => c.trials.some(t => t.status !== 'PASSED'))
    const critical = failed.filter(c => v.cases.find(d => d.id === c.id).critical).map(c => c.id)
    const passed = r.cases.length - failed.length
    check(r.summary.total === r.cases.length && r.summary.passed === passed && r.summary.passRate === passed / r.cases.length)
    check(same([...r.summary.criticalFailures].sort(), critical.sort()) && r.summary.gate === (failed.length || r.infrastructureFailureCount ? 'FAIL' : 'PASS'))
    if (r.kind === 'baseline') check(r.summary.gate === 'PASS')
  }
  const pairs = []
  for (const c of v.comparisons) {
    keys(c, 'baselineKey candidateKey gate reason changes'); arr(c.changes)
    const b = v.runs.find(r => r.key === c.baselineKey && r.kind === 'baseline'), r = v.runs.find(r => r.key === c.candidateKey && r.kind === 'run')
    check(b && r); pairs.push(`${b.key}/${r.key}`)
    const expected = calculateComparison(b, r)
    check(c.gate === expected.gate && c.reason === expected.reason && c.changes.length === expected.changes.length)
    c.changes.forEach((d, i) => {
      keys(d, 'id baselinePassRate candidatePassRate regressed meanDurationDeltaMs')
      const e = expected.changes[i]
      check(d.id === e.id && d.baselinePassRate === e.baselinePassRate && d.candidatePassRate === e.candidatePassRate && d.regressed === e.regressed)
      check(e.meanDurationDeltaMs === null ? d.meanDurationDeltaMs === null : typeof d.meanDurationDeltaMs === 'number' && Math.abs(d.meanDurationDeltaMs - e.meanDurationDeltaMs) < 0.00001)
    })
  }
  unique(pairs)
  check(pairs.length === v.runs.filter(r => r.kind === 'baseline').length * v.runs.filter(r => r.kind === 'run').length)
  return v
}

export function parseCatalog(raw) {
  check(typeof raw === 'string' && new TextEncoder().encode(raw).length <= MAX_CATALOG_BYTES)
  return validateCatalog(JSON.parse(raw))
}
