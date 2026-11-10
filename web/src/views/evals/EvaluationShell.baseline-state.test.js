import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
vi.mock('vue-router', () => ({ useRoute: () => ({ path: '/evaluations' }) }))
vi.mock('../components/GlobalAiAssistant.vue', () => ({ default: { template: '<div data-test="assistant" />' } }))
import App from '../../App.vue'

describe('public report shell', () => {
  it('does not mount the business AI assistant', () => {
    const wrapper = mount(App, { global: { stubs: { RouterView: true } } })
    expect(wrapper.find('[data-test="assistant"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
