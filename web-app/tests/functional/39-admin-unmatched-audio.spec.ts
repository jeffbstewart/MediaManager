import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';

// /admin/music/unmatched — UnmatchedAudioComponent. Album-grouped
// view (one card per (album_artist, album)) with three actions per
// group: Find on MusicBrainz (opens native <dialog>), Create from
// file metadata, Ignore all.

async function setup(page: Page) {
  await mockBackend(page, { features: 'admin' });
  await loginAs(page);
  // Real endpoints (verified against unmatched-audio.ts):
  //   POST /api/v2/admin/unmatched-audio/link-album-manual    (createFromTags)
  //   POST /api/v2/admin/unmatched-audio/{file_id}/ignore     (ignoreGroup loops files)
  //   POST /api/v2/admin/unmatched-audio/musicbrainz-search   (mb dialog)
  //   POST /api/v2/admin/unmatched-audio/link-album-to-release
  await page.route('**/api/v2/admin/unmatched-audio/link-album-manual', r =>
    r.fulfill({ json: { title_id: 1, title_name: 'Some Album', linked: 12, failed: [] } }));
  await page.route('**/api/v2/admin/unmatched-audio/*/ignore', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/unmatched-audio/musicbrainz-search', r =>
    r.fulfill({ json: { candidates: [] } }));
  await page.goto('/admin/music/unmatched');
  await page.waitForSelector('app-unmatched-audio .group-list, app-unmatched-audio .empty');
}

test.describe('admin unmatched-audio — display', () => {
  test('renders one card per fixture group', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-unmatched-audio .group-card')).toHaveCount(1);
    await expect(page.locator('app-unmatched-audio')).toContainText('Some Album');
    await expect(page.locator('app-unmatched-audio')).toContainText('Some Artist');
  });

  test('total-files header reflects fixture count', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-unmatched-audio .status')).toContainText('12');
  });

  test('expand button reveals the file list', async ({ page }) => {
    await setup(page);
    await page.locator('app-unmatched-audio button', { hasText: /Show files|Expand/ }).first().click();
    await expect(page.locator('app-unmatched-audio .files')).toBeVisible();
  });
});

test.describe('admin unmatched-audio — actions', () => {
  test('Create from file metadata POSTs /link-album-manual', async ({ page }) => {
    await setup(page);
    // createFromTags wraps in window.confirm — accept it.
    page.on('dialog', d => d.accept());
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/unmatched-audio/link-album-manual'),
      { timeout: 3_000 },
    );
    await page.locator('app-unmatched-audio button', { hasText: 'Create from' }).first().click();
    await req;
  });

  test('Ignore all POSTs /unmatched-audio/{file_id}/ignore per file', async ({ page }) => {
    await setup(page);
    page.on('dialog', d => d.accept());
    // Component loops over each file in the group and POSTs an ignore
    // per file id — wait for the first POST to fire.
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && /\/unmatched-audio\/\d+\/ignore$/.test(r.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-unmatched-audio button', { hasText: 'Ignore' }).first().click();
    await req;
  });

  test('Find on MusicBrainz opens the native <dialog>', async ({ page }) => {
    await setup(page);
    await page.locator('app-unmatched-audio button', { hasText: /MusicBrainz/ }).click();
    await expect(page.locator('app-unmatched-audio dialog.mb-dialog')).toHaveAttribute('open', '');
  });
});
