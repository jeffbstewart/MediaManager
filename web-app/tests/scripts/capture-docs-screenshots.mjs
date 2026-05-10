#!/usr/bin/env node
// Capture documentation screenshots against a real running server
// (typically the App Store demo: appstoredemo.15mcmahon.net:8443).
//
// This is NOT part of the axe / functional Playwright suite — those
// run against ng serve with mocked /api/** routes. This script
// exercises the live SPA with a real backend so the screenshots
// reflect actual content (posters, episode lists, real session
// behavior).
//
// Inputs (env vars; lifecycle/capture-web-screenshots.sh sources
// app_store_demo_setup/secrets/.env and exports them):
//   DEMO_BASE_URL              required; server origin (no trailing slash)
//   DEMO_ADMIN_USER            required; admin username
//   DEMO_ADMIN_PASSWORD        required
//   DEMO_VIEWER_PASSWORD       required (username is "viewer")
//   DEMO_KID_PASSWORD          required (username is "kid")
//   DEMO_EMPTY_PASSWORD        required (username is "empty")
//
// Output: PNG files under docs/images/screenshots/ — direct overwrite
// of whatever was there before. Tier 1 manifest is below; tier 2
// will get added as a follow-up.
//
// Routing note: the SPA is mounted under /app/, so navigation URLs
// are baseUrl + "/app/<route>". Angular's internal router sees the
// path without the /app/ prefix.

import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

// ---------------------- config ----------------------

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..', '..', '..');
const outDir = resolve(repoRoot, 'docs', 'images', 'screenshots');

const need = (key) => {
  const v = process.env[key];
  if (!v) {
    console.error(`ERROR: env var ${key} is required (source app_store_demo_setup/secrets/.env first)`);
    process.exit(2);
  }
  return v;
};

const baseUrl = need('DEMO_BASE_URL').replace(/\/+$/, '');
const credentials = {
  admin:  { username: need('DEMO_ADMIN_USER'),   password: need('DEMO_ADMIN_PASSWORD') },
  viewer: { username: 'viewer', password: need('DEMO_VIEWER_PASSWORD') },
  kid:    { username: 'kid',    password: need('DEMO_KID_PASSWORD') },
  empty:  { username: 'empty',  password: need('DEMO_EMPTY_PASSWORD') },
};

// Tier 1 manifest. Shots that need stateful pre-population (player,
// wishlist with mixed statuses, purchase-wishes with mixed statuses)
// are captured separately via the Playwright MCP — they're flagged
// `interactive: true` here so this script skips them.
//
// `viewport` defaults to 1024x768. `fullPage: true` requires a
// custom viewport tall enough to capture the whole page; the title-
// detail pages use 1024x2000 per SCREENSHOT_PROCEDURES.md.
// Routes are relative to /app/ (the SPA mount point); login() and the
// page navigations both prefix /app to whatever's here.
const manifest = [
  // Pre-login
  { file: 'login.png', account: null, route: '/login' },

  // viewer account
  { file: 'home.png',                account: 'viewer', route: '/' },
  { file: 'catalog.png',             account: 'viewer', route: '/content/movies' },
  { file: 'catalog-tv.png',          account: 'viewer', route: '/content/tv' },
  { file: 'search.png',              account: 'viewer', route: '/search?q=sherlock' },
  { file: 'title-detail-movie.png',  account: 'viewer', route: '/title/30',  // The General
                                     viewport: { width: 1024, height: 2000 }, fullPage: true },
  { file: 'title-detail-tv.png',     account: 'viewer', route: '/title/33',  // Sherlock Holmes
                                     viewport: { width: 1024, height: 2000 }, fullPage: true },
  { file: 'wishlist.png',            account: 'viewer', route: '/wishlist',
                                     viewport: { width: 1024, height: 1400 }, fullPage: true,
    customAction: async (page) => {
      // The wish-list hero tiles fetch TMDB posters through the
      // server's image proxy. networkidle fires while those are
      // still in-flight for wishes that haven't yet been server-
      // side enriched, leaving empty placeholders on the screenshot.
      // Wait for every <img> on the page to complete (load or
      // error) before letting the screenshot fire.
      await page.evaluate(async () => {
        const imgs = Array.from(document.querySelectorAll('img'));
        await Promise.all(imgs.map(img =>
          (img.complete && img.naturalWidth > 0)
            ? Promise.resolve()
            : new Promise(resolve => {
                img.addEventListener('load', resolve, { once: true });
                img.addEventListener('error', resolve, { once: true });
                setTimeout(resolve, 5000); // failsafe
              })
        ));
      });
    },
  },
  { file: 'profile.png',             account: 'viewer', route: '/profile' },
  { file: 'player.png',              account: 'viewer', route: '/title/27',  // Night of the Living Dead
    customAction: async (page) => {
      // Click the first available "Watch" play link on the title's
      // Watch section, then wait for the in-browser <video> element
      // to be present in the player route.
      const playLink = page.locator('a.tc-play-link').first();
      await playLink.waitFor({ timeout: 10000 });
      await playLink.click();
      await page.waitForURL(/\/app\/play\//, { timeout: 10000 });
      const video = page.locator('video').first();
      await video.waitFor({ timeout: 15000 });
      // Give the video a moment to load the first frame.
      await page.waitForTimeout(2000);
      // Move the mouse over the video so the browser-native controls
      // bar (play/pause, scrubber, volume, fullscreen) fades in.
      // Without this the screenshot is just a raw video frame.
      const box = await video.boundingBox();
      if (box) {
        await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
        // Small jiggle keeps the controls visible while networkidle
        // and the post-action 750ms settle run.
        await page.mouse.move(box.x + box.width / 2 + 20, box.y + box.height / 2);
      }
    },
  },

  // empty account
  { file: 'home-empty.png',          account: 'empty',  route: '/' },

  // admin account (the username comes from DEMO_ADMIN_USER, NOT literally "admin")
  { file: 'settings.png',            account: 'admin',  route: '/admin/settings',
                                     viewport: { width: 1024, height: 2000 }, fullPage: true },
  { file: 'users.png',               account: 'admin',  route: '/admin/users' },
  { file: 'transcode-status.png',    account: 'admin',  route: '/admin/transcodes/status' },
  { file: 'purchase-wishes.png',     account: 'admin',  route: '/admin/purchase-wishes',
                                     viewport: { width: 1024, height: 1400 }, fullPage: true },
];

// ---------------------- helpers ----------------------

async function login(page, who) {
  if (who === null) return;
  const { username, password } = credentials[who];
  if (!username || !password) throw new Error(`Missing credentials for account: ${who}`);

  await page.goto(`${baseUrl}/app/login`, { waitUntil: 'networkidle' });
  await page.getByRole('textbox', { name: /username/i }).fill(username);
  await page.getByRole('textbox', { name: /password/i }).fill(password);

  try {
    await Promise.all([
      page.waitForURL(/\/app\/(?!login\b).*/, { timeout: 15000 }),
      page.getByRole('button', { name: /sign in|log in/i }).first().click(),
    ]);
  } catch (e) {
    // Login didn't navigate. Surface what the page is showing so we
    // can diagnose without exposing the password.
    const currentUrl = page.url();
    const errorMsg = await page.locator('.error-message').first().textContent({ timeout: 500 }).catch(() => null);
    const visibleHeading = await page.locator('h1, mat-card-title').first().textContent({ timeout: 500 }).catch(() => null);
    throw new Error(
      `login(${who}, username='${username}') stuck — url=${currentUrl} ` +
      `error='${errorMsg ?? '(none)'}' heading='${visibleHeading ?? '(none)'}'`
    );
  }

  // First-login flow for accounts seeded via the admin API: the SPA
  // routes to /app/terms when terms_of_use_accepted_at is NULL on the
  // user record. Tick the checkboxes (one per published version —
  // privacy + web terms) and submit "Accept & Continue". Then wait
  // for the redirect off /app/terms.
  if (page.url().includes('/app/terms')) {
    // Wait for the form to fully render — the discover-status fetch
    // that drives whether the privacy + terms checkboxes appear is
    // async, so the page can be interactive on /app/terms before the
    // checkboxes have been inserted.
    await page.waitForLoadState('networkidle').catch(() => {});
    await page.locator('mat-checkbox').first().waitFor({ timeout: 5000 }).catch(() => {});

    // mat-checkbox doesn't surface a useful accessible name for
    // getByRole('checkbox'), and a click on the host element doesn't
    // reliably bubble to the Angular reactive-form binding. Match the
    // mat-checkbox host by its visible label text, then drive the
    // inner native input via setChecked() — that fires the event
    // pattern Angular listens for.
    const privacyHost = page.locator('mat-checkbox').filter({ hasText: /privacy policy/i });
    const privacyVisible = await privacyHost.isVisible({ timeout: 1000 }).catch(() => false);
    if (privacyVisible) await privacyHost.locator('input[type="checkbox"]').setChecked(true);
    const termsHost = page.locator('mat-checkbox').filter({ hasText: /terms of use/i });
    const termsVisible = await termsHost.isVisible({ timeout: 1000 }).catch(() => false);
    if (termsVisible) await termsHost.locator('input[type="checkbox"]').setChecked(true);
    const submitBtn = page.getByRole('button', { name: /accept.*continue/i }).first();
    try {
      await Promise.all([
        page.waitForURL(/\/app\/(?!terms\b).*/, { timeout: 15000 }),
        submitBtn.click(),
      ]);
    } catch (e) {
      const currentUrl = page.url();
      const errorMsg = await page.locator('.error-message, .field-error').first().textContent({ timeout: 500 }).catch(() => null);
      const submitDisabled = await submitBtn.evaluate(b => b.disabled).catch(() => 'unknown');
      throw new Error(
        `ToS-accept(${who}) stuck — url=${currentUrl} ` +
        `privacyCb_visible=${privacyVisible} termsCb_visible=${termsVisible} ` +
        `submitDisabled=${submitDisabled} error='${errorMsg ?? '(none)'}'`
      );
    }
  }

  await page.waitForLoadState('networkidle').catch(() => {});
}

async function logout(page) {
  // Cleanest: hit the logout endpoint directly. Falls back to
  // clearing storage so the next login isn't carrying state.
  await page.context().clearCookies();
  await page.evaluate(() => {
    try { localStorage.clear(); sessionStorage.clear(); } catch {}
  });
}

async function captureOne(page, shot) {
  if (shot.interactive) {
    console.log(`  skip:    ${shot.file} (interactive — see note: ${shot.note})`);
    return { file: shot.file, status: 'skip' };
  }
  try {
    if (shot.route !== null) {
      await page.goto(`${baseUrl}/app${shot.route}`, { waitUntil: 'networkidle', timeout: 30000 });
    }
    if (shot.waitFor) {
      await page.locator(shot.waitFor).first().waitFor({ timeout: 10000 }).catch(() => {});
    }
    if (typeof shot.customAction === 'function') {
      await shot.customAction(page);
    }
    // Brief settle — SPA carousels and image lazy-loads finish after
    // networkidle in the demo's layout. 750ms covers it without being
    // unbearable across the manifest.
    await page.waitForTimeout(750);

    const dest = resolve(outDir, shot.file);
    await page.screenshot({ path: dest, fullPage: !!shot.fullPage });
    console.log(`  capture: ${shot.file}  (${shot.account ?? '(no auth)'})`);
    return { file: shot.file, status: 'ok' };
  } catch (e) {
    console.error(`  FAIL:    ${shot.file} — ${e.message}`);
    return { file: shot.file, status: 'fail', error: e.message };
  }
}

// ---------------------- main ----------------------

(async () => {
  await mkdir(outDir, { recursive: true });
  console.log(`Output dir: ${outDir}`);
  console.log(`Base URL:   ${baseUrl}`);
  console.log(`${manifest.length} shot(s) in manifest`);

  const browser = await chromium.launch({
    args: ['--autoplay-policy=no-user-gesture-required'],
  });

  // Group shots by account. One context (and one login) per account
  // keeps total logins low enough to stay under the per-IP rate
  // limiter (10/min). Shots with custom viewports get their own
  // context inside the group so we don't have to resize mid-session.
  const groups = new Map();  // account-key -> [shots]
  for (const shot of manifest) {
    const key = shot.account ?? '__nologin__';
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(shot);
  }

  const results = [];
  for (const [accountKey, shots] of groups) {
    const account = accountKey === '__nologin__' ? null : accountKey;
    // Default viewport is shared per group. Shots that override
    // viewport (e.g. full-page title detail at 1024x2000) get a
    // throwaway sub-context inside the group.
    const defaultViewport = { width: 1024, height: 768 };
    const ctx = await browser.newContext({ viewport: defaultViewport, ignoreHTTPSErrors: true });
    const page = await ctx.newPage();
    try {
      await login(page, account);
      for (const shot of shots) {
        if (shot.viewport && (shot.viewport.width !== defaultViewport.width || shot.viewport.height !== defaultViewport.height)) {
          // Swap viewport just for this shot.
          await page.setViewportSize(shot.viewport);
          results.push(await captureOne(page, shot));
          await page.setViewportSize(defaultViewport);
        } else {
          results.push(await captureOne(page, shot));
        }
      }
    } catch (e) {
      console.error(`  FAIL group ${accountKey}: ${e.message}`);
      for (const shot of shots) {
        results.push({ file: shot.file, status: 'fail', error: e.message });
      }
    } finally {
      await ctx.close();
    }
  }
  await browser.close();

  const ok = results.filter(r => r.status === 'ok').length;
  const skipped = results.filter(r => r.status === 'skip').length;
  const failed = results.filter(r => r.status === 'fail');
  console.log(`\n${ok} captured, ${skipped} interactive (skipped), ${failed.length} failed`);
  if (failed.length > 0) {
    for (const f of failed) console.log(`  - ${f.file}: ${f.error}`);
    process.exit(1);
  }
})();
