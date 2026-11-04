import { defineConfig } from '@playwright/test';

const baseURL = process.env.BASE_URL || 'http://localhost:8080';
const target = new URL(baseURL);
if (target.protocol !== 'http:' || !['localhost', '127.0.0.1'].includes(target.hostname) || target.port !== '8080'
    || target.username || target.password || target.pathname !== '/' || target.search || target.hash) {
  throw new Error('Las pruebas de emisión solo pueden ejecutarse contra el loopback del sandbox web en Docker.');
}
const output = process.env.QA_OUTPUT_DIR || '/results';

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  outputDir: `${output}/playwright-results`,
  reporter: [['line'], ['json', { outputFile: `${output}/playwright-report.json` }]],
  use: {
    baseURL,
    timezoneId: 'UTC',
    locale: 'es-MX',
    acceptDownloads: true,
    trace: 'off',
    video: 'off',
    screenshot: 'off',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    launchOptions: {
      args: ['--no-sandbox']
    }
  },
  projects: [
    { name: 'desktop', use: { browserName: 'chromium', viewport: { width: 1440, height: 1000 } } },
    { name: 'mobile', use: { browserName: 'chromium', viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true } }
  ]
});
