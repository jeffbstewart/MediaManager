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
  // Per-test budget. Playwright's default of 30 s is right at a desk; a
  // small CI box running several Chromium processes beside `ng serve`
  // needs more, so tests/ci-run.sh raises it through PW_TEST_TIMEOUT_MS.
  timeout: Number(process.env.PW_TEST_TIMEOUT_MS) || 30_000,
  // Same idea for individual expect() waits: 5 s at a desk, longer in CI
  // through PW_EXPECT_TIMEOUT_MS. Specs should not pass their own short
  // timeouts to expect(); a dialog that opens after 2.5 s on a loaded box
  // is not a failure.
  expect: { timeout: Number(process.env.PW_EXPECT_TIMEOUT_MS) || 5_000 },
  // Reporter list:
  //   - list: human-readable per-test progress (the harness captures
  //     this into raw/<spec>.stderr but it's also useful when running
  //     `npx playwright test` directly).
  //   - json: structured per-test results. The harness sets
  //     PLAYWRIGHT_JSON_FILE per spec so each subprocess writes its
  //     reporter output to a unique file the harness reads back.
  //     When the env var is unset (direct CLI runs), the json
  //     reporter writes to stdout per Playwright's default.
  //   - monocart-reporter: aggregates V8 JS coverage collected per-
  //     test by the autoCoverageFixture in tests/helpers/test-fixture.ts.
  //     Each per-spec run writes raw coverage into the shared cache
  //     (clean=false / cleanCache=false) so the next spec's report
  //     extends rather than replaces — the LAST spec's regenerate
  //     produces a report covering everything that ran.
  //
  // The Argos reporter for visual review is not listed here: the capture
  // script (tests/visual/capture.mjs) passes --reporter itself, which
  // replaces this list, and adds the Argos reporter when ARGOS_UPLOAD is set.
  //
  // sourceFilter narrows the coverage report to our app source.
  reporter: [
    ['list'],
    ['json', process.env.PLAYWRIGHT_JSON_FILE
      ? { outputFile: process.env.PLAYWRIGHT_JSON_FILE }
      : {}],
    ['monocart-reporter', {
      name: 'mediamanager web-app',
      outputFile: process.env.MCR_OUTPUT_DIR
        ? `${process.env.MCR_OUTPUT_DIR}/index.html`
        : 'tests/.last-run/coverage/index.html',
      coverage: {
        // Per-spec subprocess invocations write to a unique dir
        // (set by the harness via MCR_OUTPUT_DIR) so they don't
        // race each other clobbering a shared cache. The harness's
        // final step merges all per-spec raw dirs into one report
        // under tests/.last-run/coverage/.
        outputDir: process.env.MCR_OUTPUT_DIR || 'tests/.last-run/coverage',
        entryFilter: { '**/node_modules/**': false, '**/*': true },
        sourceFilter: { '**/node_modules/**': false, '**/src/app/**': true },
        // Per-spec runs only need the raw dump (cheap, mergeable);
        // the merged final pass produces v8 + json-summary.
        reports: process.env.MCR_OUTPUT_DIR
          ? [['raw']]
          : [['v8'], ['raw'], ['json-summary']],
      },
    }],
  ],

  use: {
    baseURL: 'http://localhost:4200',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },

  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        // The player integration test (15-player-playback) calls
        // video.play() programmatically via the page lifecycle.
        // Headless Chromium's default autoplay policy blocks that
        // until a user gesture, which the page never makes — the
        // test then stalls with paused video. Disable the policy
        // for the whole project; the cost in other specs is
        // nothing (they don't have <video> elements).
        launchOptions: {
          // The two rendering flags keep text identical between machines
          // so Argos diffs show layout changes, not font hinting. They
          // apply to every spec; axe does not care how glyphs are drawn.
          args: [
            '--autoplay-policy=no-user-gesture-required',
            '--disable-lcd-text',
            '--font-render-hinting=none',
          ],
          // Optional: run whatever Chromium the host already has instead
          // of the build this Playwright version pins. Sandboxes without
          // access to cdn.playwright.dev ship their own browsers under
          // PLAYWRIGHT_BROWSERS_PATH; tests/ci-run.sh finds the newest
          // and exports this variable. Unset, Playwright behaves as usual.
          ...(process.env.PW_CHROMIUM_EXECUTABLE
            ? { executablePath: process.env.PW_CHROMIUM_EXECUTABLE }
            : {}),
        },
      },
    },
  ],

  // Start ng serve manually before running tests; Playwright's
  // webServer auto-spawn triggered an intermittent test-loader race
  // on Windows. `npm run test:a11y` handles this (starts + waits).
});
