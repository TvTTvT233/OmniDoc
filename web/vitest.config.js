import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    include: ['src/**/*.memory-baseline.test.js'],
    clearMocks: true,
    restoreMocks: true,
    threads: false,
  },
})
