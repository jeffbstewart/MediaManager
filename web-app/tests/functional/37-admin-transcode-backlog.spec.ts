import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// /admin/transcodes/backlog — TranscodeBacklogComponent. List of titles
// awaiting transcode, ranked by request count + popularity. Per-row
// "request" toggle hits /wishlist/transcode endpoints.

async function setup(page: Page) {
  await mockBackend(page, { features: 'admin' });
  await loginAs(page);
  await stubImages(page);
  await page.route('**/api/v2/wishlist/transcode/*', r => {
    if (r.request().method() === 'POST') return r.fulfill({ status: 204 });
    if (r.request().method() === 'DELETE') return r.fulfill({ status: 204 });
    return r.fallback();
  });
  // toggleWish on a wished row first GETs /wishlist to find the wish-id.
  await page.route('**/api/v2/wishlist', r =>
    r.fulfill({ json: {
      transcode_wishes: [{ id: 5, title_id: 100 }],
      media_wishes: [], book_wishes: [], album_wishes: [], has_any_media_wish: true,
    } }));
  await page.goto('/admin/transcodes/backlog');
  await page.waitForSelector('app-transcode-backlog table');
}

test.describe('admin transcode-backlog — display', () => {
  test('renders both fixture rows with title + request count', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-transcode-backlog tbody tr')).toHaveCount(2);
    await expect(page.locator('app-transcode-backlog')).toContainText('The Matrix');
    await expect(page.locator('app-transcode-backlog')).toContainText('Inception');
  });
});

test.describe('admin transcode-backlog — actions', () => {
  test('search input fires GET with search=...', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.url().includes('/api/v2/admin/transcode-backlog') && /search=matrix/.test(r.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-transcode-backlog input[type="text"]').first().fill('matrix');
    await req;
  });

  test('un-wished row → click POSTs /wishlist/transcode/:titleId', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.method() === 'POST' && r.url().endsWith('/api/v2/wishlist/transcode/200'),
      { timeout: 3_000 },
    );
    // Inception (row 1) has is_wished=false; click its request button.
    await page.locator('app-transcode-backlog tr', { hasText: 'Inception' })
      .locator('button').last().click();
    await req;
  });

  test('wished row → click DELETEs /wishlist/transcode/:wishId', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.method() === 'DELETE' && r.url().endsWith('/api/v2/wishlist/transcode/5'),
      { timeout: 3_000 },
    );
    await page.locator('app-transcode-backlog tr', { hasText: 'The Matrix' })
      .locator('button').last().click();
    await req;
  });
});
