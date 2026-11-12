export const SERVICE_CHECKS = {"document": ["parse-exact-text", "initial-index-ready", "old-generation-never-queryable-after-save", "historical-reference-exact", "new-version-published", "new-segments-version", "reference-owner-isolated"], "review": ["review-worker-success", "missing-material-detected", "dual-evidence-present", "old-report-outdated", "review-worker-success", "supplement-clears-findings", "report-owner-isolated"], "isolation": ["deterministic-agent-completed", "duplicate-same-run", "payload-conflict", "task-owner-isolated", "durable-jdbc-result"], "restart": ["approval-required", "approval-persisted-before-restart", "different-process-same-artifact", "same-approval-restored", "approval-cancelled", "cancelled-state-persisted", "late-confirm-no-write"]}
const check = ok => { if (!ok) throw new Error('INVALID_SERVICE_REPORT') }
const fields = (value, names) => check(value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).sort().join(' ') === names.split(' ').sort().join(' '))
export function parseServiceReport(raw) {
  check(typeof raw === 'string' && new TextEncoder().encode(raw).length <= 100000)
  const v = JSON.parse(raw)
  fields(v, 'schemaVersion mode runId createdAtUtc commit dirty contract modelBoundary usage cases gate')
  check(v.schemaVersion===1 && v.mode==='service-flows-v1' && /^\d{8}T\d{6}Z-[a-f0-9]{8}$/.test(v.runId))
  check(typeof v.createdAtUtc==='string' && v.createdAtUtc.length<40 && Number.isFinite(Date.parse(v.createdAtUtc)))
  check(/^[a-f0-9]{40}$/.test(v.commit) && typeof v.dirty==='boolean')
  fields(v.contract,'runnerOs digest'); check(['Windows','Linux','Darwin'].includes(v.contract.runnerOs) && /^[a-f0-9]{64}$/.test(v.contract.digest))
  check(v.modelBoundary==='DISABLED_WITH_SYNTHETIC_EMBEDDING' && v.usage===null && Array.isArray(v.cases) && v.cases.length===4)
  v.cases.forEach((c,i)=>{
    fields(c,'id title status reason durationMs checks productionRunIds runIdPolicy')
    check(c.id===Object.keys(SERVICE_CHECKS)[i] && typeof c.title==='string' && c.title.length<100)
    check(['PASS','FAIL','BLOCKED'].includes(c.status) && c.reason===({PASS:null,FAIL:'JOURNEY_FAILED',BLOCKED:'RUNTIME_UNAVAILABLE'})[c.status])
    check(typeof c.durationMs==='number' && Number.isFinite(c.durationMs) && c.durationMs>=0)
    check(Array.isArray(c.checks) && c.checks.length<=SERVICE_CHECKS[c.id].length)
    c.checks.forEach((x,index)=>{fields(x,'name passed');check(x.name===SERVICE_CHECKS[c.id][index] && typeof x.passed==='boolean')})
    check(c.runIdPolicy===(c.id==='document'?'NOT_APPLICABLE':'REQUIRED'))
    check(Array.isArray(c.productionRunIds) && c.productionRunIds.length<=2 && c.productionRunIds.every(x=>/^(agent|review):[a-zA-Z0-9_-]{1,100}$/.test(x)))
    if(c.status==='PASS')check(c.checks.length===SERVICE_CHECKS[c.id].length && c.checks.every(x=>x.passed) && c.productionRunIds.length===({document:0,review:2,isolation:1,restart:1})[c.id])
  })
  check(v.gate===(v.cases.every(c=>c.status==='PASS')?'PASS':'FAIL'))
  return v
}
