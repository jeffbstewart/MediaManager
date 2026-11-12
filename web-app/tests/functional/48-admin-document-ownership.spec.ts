import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';

// /admin/document-ownership — DocumentOwnershipComponent. Two phases:
// (1) scan/search a media item by UPC or text; (2) capture phase
// shows item + photos and allows uploading more proof-of-ownership
// shots.

async function setup(page: Page) {
  await mockBackend(page, { features: 'admin' });
  await loginAs(page);
  await stubImages(page);
  await page.route('**/api/v2/admin/ownership/lookup*', r =>
    r.fulfill({ json: {
      found: true, upc: '888574293321',
      media_item_id: 1, title_name: 'The Matrix',
      media_format: 'BLU_RAY', poster_url: '/posters/100.jpg',
      photos: [],
    } }));
  await page.route('**/api/v2/admin/ownership/search*', r =>
    r.fulfill({ json: { items: [
      { media_item_id: 1, upc: '888574293321', title_name: 'The Matrix', media_format: 'BLU_RAY', photo_count: 0 },
    ] } }));
  await page.goto('/admin/document-ownership');
  await page.waitForSelector('app-document-ownership .ownership-page');
}

test.describe('admin document-ownership — scan phase', () => {
  test('renders scan UI by default with UPC input + search box', async ({ page }) => {
    await setup(page);
    // UPC input + search input + camera button.
    await expect(page.locator('app-document-ownership input').first()).toBeVisible();
  });

  test('typing a UPC + Lookup fires GET /lookup with the upc param', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.url().includes('/api/v2/admin/ownership/lookup') && /upc=888574293321/.test(r.url()),
      { timeout: 3_000 },
    );
    await page.locator('app-document-ownership input').first().fill('888574293321');
    await page.locator('app-document-ownership input').first().press('Enter');
    await req;
  });

  test('typing in the search box fires GET /search with the q param', async ({ page }) => {
    await setup(page);
    const req = page.waitForRequest(r =>
      r.url().includes('/api/v2/admin/ownership/search') && /q=matrix/i.test(r.url()),
      { timeout: 3_000 },
    );
    // The second input is the search input.
    await page.locator('app-document-ownership input').nth(1).fill('matrix');
    await req;
  });
});

test.describe('admin document-ownership — capture phase', () => {
  test('successful UPC lookup transitions to the capture phase', async ({ page }) => {
    await setup(page);
    await page.locator('app-document-ownership input').first().fill('888574293321');
    await page.locator('app-document-ownership input').first().press('Enter');
    // Capture phase shows the title name + format.
    await expect(page.locator('app-document-ownership')).toContainText('The Matrix', { timeout: 4_000 });
  });
});
