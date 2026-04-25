import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright config for the a11y / UI test suite.
 *
 * These tests run against a local `ng serve` — the backend is mocked via
 * `page.route(...)` interception, so nothing in `/api/**` actually hits
 * the Kotlin server. This makes the suite hermetic and CI-friendly (no
 * H2 file, no Flyway, no seeded fixture data).
 *
 * `webServer` starts `ng serve` if it isn't already running; if a dev
 * server is already up on the port it's reused. See README for local use.
 */
export default defineConfig({
  testDir: './tests',
  testMatch: /.*\.spec\.ts$/,
  // Each test file gets its own worker process. Windows had a
  // cross-file loader corruption: when `workers: 1` made one worker
  // load multiple spec files sequentially, the second file onwards
  // tripped "test.describe called outside suite". Giving each file
  // its own worker via `fullyParallel: true` + generous `workers`
  // sidesteps the shared-state bug.
  fullyParallel: true,
  workers: 8,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',

  use: {
    baseURL: 'http://localhost:4200',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  // Start ng serve manually before running tests; Playwright's
  // webServer auto-spawn triggered an intermittent test-loader race
  // on Windows. `npm run test:a11y` handles this (starts + waits).
});
