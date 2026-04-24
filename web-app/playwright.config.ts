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
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 2 : undefined,
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

  webServer: {
    command: 'npm start',
    url: 'http://localhost:4200',
    reuseExistingServer: !process.env.CI,
    // ng serve on a cold machine can take 30-60 s to produce the first
    // bundle. Bump the default timeout so CI doesn't fail on slow builds.
    timeout: 180_000,
  },
});
