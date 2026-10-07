import { defineConfig } from '@playwright/test'
export default defineConfig({
  testDir: './tests/browser',
  timeout: 30000,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['json', { outputFile: 'test-results/browser.json' }]],
  outputDir: 'test-results/browser-artifacts',
  use: { baseURL: 'http://127.0.0.1:15179', browserName: 'chromium', headless: true,
    viewport: { width: 1440, height: 1000 }, trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  webServer: { command: 'node tests/browser/server.mjs', url: 'http://127.0.0.1:15179',
    reuseExistingServer: false, timeout: 30000 },
})
