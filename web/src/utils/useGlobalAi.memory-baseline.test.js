import { beforeEach, describe, expect, it, vi } from 'vitest'

describe('useGlobalAi current memory baseline', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetModules()
  })

  it('restores only the conversation id after a module reload, not messages', async () => {
    const firstModule = await import('./useGlobalAi.js')
    const firstSession = firstModule.useGlobalAi()

    firstSession.saveConvId('conv-refresh-baseline')
    firstSession.appendMessage({ role: 'user', text: '这条消息只存在内存中' })

    expect(firstSession.currentConvId.value).toBe('conv-refresh-baseline')
    expect(firstSession.chatHistory.value).toHaveLength(1)

    vi.resetModules()

    const refreshedModule = await import('./useGlobalAi.js')
    const refreshedSession = refreshedModule.useGlobalAi()

    expect(refreshedSession.currentConvId.value).toBe('conv-refresh-baseline')
    expect(refreshedSession.chatHistory.value).toEqual([])
  })

  it('resetConversation only clears the local singleton and storage pointer', async () => {
    const { useGlobalAi } = await import('./useGlobalAi.js')
    const session = useGlobalAi()

    session.saveConvId('conv-reset-baseline')
    session.appendMessage({ role: 'ai', text: '已有回答' })
    session.resetConversation()

    expect(session.currentConvId.value).toBeNull()
    expect(session.chatHistory.value).toEqual([])
    expect(localStorage.getItem('global_conv_id')).toBeNull()
  })
})
