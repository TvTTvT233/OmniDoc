import { afterEach, describe, expect, it, vi } from 'vitest'

const state = vi.hoisted(() => ({ assistantSetups: 0, loginSetups: 0, router: null }))
vi.mock('./router', async () => {
  const { createRouter, createMemoryHistory } = await import('vue-router')
  const { h } = await import('vue')
  state.router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', redirect: '/login' },
      { path: '/login', component: { setup() { state.loginSetups++; return () => h('main', { class: 'login-test-view' }, 'Login') } } },
    ],
  })
  return { default: state.router }
})
vi.mock('./views/components/GlobalAiAssistant.vue', () => ({
  default: { setup() { state.assistantSetups++; throw new Error('Authenticated conversation scope is unavailable') } },
}))
vi.mock('element-plus', () => ({ default: { install() {} } }))
vi.mock('@element-plus/icons-vue', () => ({}))

afterEach(() => {
  document.querySelector('#app')?.__vue_app__?.unmount()
  document.body.innerHTML = ''
})

describe('real router startup', () => {
  it('waits for the initial redirect, mounting one login view and no authenticated assistant or injected panel', async () => {
    // 本用例由独立 Vitest 进程执行，禁止引入 shallowMount 的全局 VNode stub 钩子。
    document.body.innerHTML = '<div id="app"></div>'
    await import('./main.js')
    await state.router.isReady()
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(state.assistantSetups).toBe(0)
    expect(state.loginSetups).toBe(1)
    expect(document.querySelectorAll('.login-test-view')).toHaveLength(1)
    expect(document.querySelector('#app').__vue_app__).toBeTruthy()
    expect(document.querySelector('#__collaborate_panel__')).toBeNull()
  })
})
