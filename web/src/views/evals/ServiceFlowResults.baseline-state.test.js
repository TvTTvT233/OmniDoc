import { describe, it, expect } from 'vitest'
import { SERVICE_CHECKS, parseServiceReport } from '../../utils/serviceReport.js'
function fixture() {
  return {schemaVersion:1,mode:'service-flows-v1',runId:'20261112T000000Z-abcdefab',createdAtUtc:'2026-11-12T00:00:00Z',commit:'a'.repeat(40),dirty:false,contract:{runnerOs:'Windows',digest:'b'.repeat(64)},modelBoundary:'DISABLED_WITH_SYNTHETIC_EMBEDDING',usage:null,gate:'PASS',cases:Object.keys(SERVICE_CHECKS).map(id=>({id,title:id,status:'PASS',reason:null,durationMs:1,checks:SERVICE_CHECKS[id].map(name=>({name,passed:true})),productionRunIds:id==='document'?[]:id==='review'?['review:aaaa','review:bbbb']:['agent:aaaa'],runIdPolicy:id==='document'?'NOT_APPLICABLE':'REQUIRED'}))}
}
describe('service report boundary',()=>{
 it('accepts complete independent flow evidence',()=>expect(parseServiceReport(JSON.stringify(fixture())).gate).toBe('PASS'))
 it.each([
   v=>v.cases[0].checks.pop(),
   v=>v.cases[0].checks[0].passed=false,
   v=>v.cases[1].productionRunIds=[],
   v=>v.raw='private',
   v=>v.mode='deterministic-components',
   v=>v.cases[0].durationMs=-1,
   v=>v.cases[0].checks[0].name='private'
 ])('rejects incomplete, forged or foreign content',modify=>{const v=fixture();modify(v);expect(()=>parseServiceReport(JSON.stringify(v))).toThrow()})
 it('keeps runtime unavailable nonpassing',()=>{const v=fixture();v.gate='FAIL';v.cases.forEach(c=>{c.status='BLOCKED';c.reason='RUNTIME_UNAVAILABLE';c.checks=[];c.productionRunIds=[]});expect(parseServiceReport(JSON.stringify(v)).gate).toBe('FAIL')})
})
