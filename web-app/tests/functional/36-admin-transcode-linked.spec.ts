import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// /admin/transcodes/linked — TranscodeLinkedComponent. Mat-table of
// already-linked transcodes with search + format/type filter chips,
// per-row Re-transcode (window.confirm) and Unlink actions.

async function setup(page: Page) {
  await mockBackend(page, { features: 'admin' });
  await loginAs(page);
  await stubImages(page);
  await page.route('**/api/v2/admin/linked-transcodes/*/retranscode', r => r.fulfill({ status: 204 }));
  await page.route('**/api/v2/admin/linked-transcodes/*', r => {
    if (r.request().method() === 'DELETE') return r.fulfill({ status: 204 });
    return r.fallback();
  });
  await page.goto('/admin/transcodes/linked');
  await page.waitForSelector('app-transcode-linked table');
}

test.describe('admin transcode-linked — display', () => {
  test('renders both fixture rows with title + format', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-transcode-linked tbody tr')).toHaveCount(2);
    await expect(page.locator('app-transcode-linked')).toContainText('The Matrix');
    await expect(page.locator('app-transcode-linked')).toContainText('Blu-ray');
    await expect(page.locator('app-transcode-linked')).toContainText('Breaking Bad');
  });
});

test.describe('admin transcode-linked — filters', () => {
  test('search input fires GET with search=...', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.url().includes('/api/v2/admin/linked-transcodes') && /search=matrix/.test(r.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-transcode-linked input[type="text"]').first().fill('matrix');
    await req;
  });
});

test.describe('admin transcode-linked — actions', () => {
  test('Re-transcode prompts confirm + POSTs /retranscode on accept', async ({ page }) => {
    await setup(page);
    page.on('dialog', d => d.accept());
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/admin/linked-transcodes/1/retranscode'),
      { timeout: 3_000 },
    );
    // Re-transcode is an icon-only button with aria-label (only visible
    // on rows where retranscode_requested=false; The Matrix qualifies).
    await page.locator('app-transcode-linked tbody tr').first()
      .locator('button[aria-label="Request re-transcode"]').click();
    await req;
  });

  test('Unlink DELETEs /linked-transcodes/:id', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.method() === 'DELETE' && r.url().endsWith('/api/v2/admin/linked-transcodes/1'),
      { timeout: 3_000 },
    );
    // Unlink is an icon-only button with aria-label.
    await page.locator('app-transcode-linked tbody tr').first()
      .locator('button[aria-label="Unlink from title"]').click();
    await req;
  });
});
