import { describe, it, expect } from 'vitest'
import { checkFrontendAction } from './frontendAction'
describe('frontend action guard', () => {
  const action = { actionId: 'run:step', documentId: 1, expectedVersion: 2 }
  const current = () => ({ documentId: 1, version: 2, content: 'body', originalContent: 'body', appliedIds: new Set() })
  it('permits only a matching current proposal', () => expect(checkFrontendAction(action, current())).toBeNull())
  it('rejects another document', () => expect(checkFrontendAction(action, { ...current(), documentId: 3 })).toContain('另一篇'))
  it('rejects an already applied action', () => expect(checkFrontendAction(action, { ...current(), appliedIds: new Set(['run:step']) })).toContain('已经应用'))
  it('rejects saved and unsaved changes', () => {
    expect(checkFrontendAction(action, { ...current(), version: 3 })).toContain('已变化')
    expect(checkFrontendAction(action, { ...current(), content: 'new body' })).toContain('已变化')
  })
  it('does not silently apply legacy results', () => expect(checkFrontendAction({ documentId: 1 }, current())).toContain('缺少'))
})
