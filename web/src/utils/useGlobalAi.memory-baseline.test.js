import { beforeEach, describe, expect, it, vi } from 'vitest'

describe('useGlobalAi post-M06 boundary', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetModules()
  })

  it('owns only panel visibility and no longer stores conversation facts locally', async () => {
    const { useGlobalAi } = await import('./useGlobalAi.js')
    const globalAi = useGlobalAi()

    expect(Object.keys(globalAi).sort()).toEqual(['isAiPanelVisible', 'toggleAiPanel'])
    expect(globalAi.isAiPanelVisible.value).toBe(false)
    globalAi.toggleAiPanel()
    expect(globalAi.isAiPanelVisible.value).toBe(true)
  })

  it('does not read and removes the legacy global conversation pointer', async () => {
    localStorage.setItem('global_conv_id', 'temp_legacy')

    const { useGlobalAi } = await import('./useGlobalAi.js')
    const globalAi = useGlobalAi()

    expect(globalAi.currentConvId).toBeUndefined()
    expect(globalAi.chatHistory).toBeUndefined()
    expect(globalAi.saveConvId).toBeUndefined()
    expect(localStorage.getItem('global_conv_id')).toBeNull()
  })
})
