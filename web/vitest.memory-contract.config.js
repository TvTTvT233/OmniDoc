import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    environment: 'jsdom',
    include: ['src/**/*.memory-contract.test.js'],
    clearMocks: true,
    restoreMocks: true,
    threads: false,
  },
})
