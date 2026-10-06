import { defineConfig, mergeConfig } from 'vitest/config'
import viteConfig from './vite.config.js'

export default mergeConfig(viteConfig, defineConfig({
  test: {
    environment: 'jsdom',
    environmentOptions: { jsdom: { url: 'http://localhost/' } },
    include: ['tests/**/*.test.js'],
    setupFiles: ['tests/setup.js'],
    restoreMocks: true,
    clearMocks: true,
    unstubGlobals: true,
    testTimeout: 10000,
    reporters: ['default', 'junit', 'json'],
    outputFile: {
      junit: 'test-results/frontend.xml',
      json: 'test-results/frontend.json',
    },
  },
}))
