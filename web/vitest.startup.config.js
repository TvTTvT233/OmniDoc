import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// 入口使用 createApp 而非 Test Utils mount；单独运行，隔离组件测试的全局 stub 钩子。
export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    include: ['src/main.startup.test.js'],
    clearMocks: true,
    restoreMocks: true,
    threads: false,
  },
})
