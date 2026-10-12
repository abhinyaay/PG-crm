import { defineConfig, devices } from '@playwright/test';
import { BASE_URL } from './e2e/support/env';

/**
 * End-to-end tests against an already running stack (default http://localhost:58088, override with E2E_BASE_URL).
 * Nothing is started from here: the suite drives the real SPA, API and database exactly as deployed.
 *
 * Runs serially (workers: 1): the tests share one demo database and the auth endpoints are rate limited, so
 * parallelism buys little and would make notification / bed counts race. See e2e/support/session-broker.ts for how
 * sessions are created once per role and reused safely.
 */
export default defineConfig({
  testDir: './e2e',
  outputDir: './e2e/test-results',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: !!process.env.CI,
  timeout: 90_000,
  expect: { timeout: 10_000 },
  reporter: process.env.CI
    ? [['list'], ['github'], ['html', { outputFolder: 'e2e/playwright-report', open: 'never' }]]
    : [['list'], ['html', { outputFolder: 'e2e/playwright-report', open: 'never' }]],
  use: {
    baseURL: BASE_URL,
    locale: 'en-IN',
    timezoneId: 'Asia/Kolkata',
    colorScheme: 'light',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    { name: 'setup', testMatch: /auth\.setup\.ts$/, use: { ...devices['Desktop Chrome'] } },
    {
      name: 'desktop',
      testMatch: /.*\.e2e\.ts$/,
      dependencies: ['setup'],
      use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 800 } },
    },
    {
      name: 'mobile',
      testMatch: /.*\.e2e\.ts$/,
      dependencies: ['setup'],
      use: {
        browserName: 'chromium',
        viewport: { width: 390, height: 844 },
        deviceScaleFactor: 3,
        isMobile: true,
        hasTouch: true,
        userAgent: devices['Pixel 7'].userAgent,
      },
    },
  ],
});
