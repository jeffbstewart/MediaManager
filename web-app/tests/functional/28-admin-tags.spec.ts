import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { fulfillProto, unframeGrpcWebRequest } from '../helpers/proto-fixture';
import { create, fromBinary } from '@bufbuild/protobuf';
import {
  CreateTagRequestSchema,
  CreateTagResponseSchema,
  CreateTagResult,
} from '../../src/app/proto-gen/admin_pb';

// /admin/tags — TagManagementComponent. Mat-table of tags + per-row
// Edit/Delete + a New Tag dialog with the Tailwind-700 color picker.

const AS = '/mediamanager.AdminService';

async function setup(page: Page) {
  await mockBackend(page, { features: 'admin' });
  await loginAs(page);
  // CreateTag now goes through gRPC; legacy REST mocks for the
  // PUT/DELETE rows stay as-is until those migrate too.
  await page.route(`**${AS}/CreateTag`, r =>
    fulfillProto(r, CreateTagResponseSchema, create(CreateTagResponseSchema, {
      result: CreateTagResult.CREATED,
      id: 99n,
    })));
  await page.route('**/api/v2/admin/tags/*', r => {
    const m = r.request().method();
    if (m === 'PUT') return r.fulfill({ json: { ok: true } });
    if (m === 'DELETE') return r.fulfill({ status: 204 });
    return r.fallback();
  });
  await page.goto('/admin/tags');
  await page.waitForSelector('app-tag-management table');
}

test.describe('admin tags — display', () => {
  test('renders all 4 fixture tags with name, source label, title count', async ({ page }) => {
    await setup(page);
    const rows = page.locator('app-tag-management table tbody tr');
    await expect(rows).toHaveCount(4);
    await expect(rows.nth(0).locator('.tag-badge')).toContainText('Family Movie Night');
    await expect(rows.nth(0)).toContainText('Manual');
    await expect(rows.nth(0)).toContainText('12');
    // TMDB-source tag has source_type "TMDB" → falls through default → "Manual".
    // (The component only knows GENRE / COLLECTION / EVENT_TYPE explicitly.)
    await expect(rows.nth(3).locator('.tag-badge')).toContainText('TMDB Genre: Comedy');
  });

  test('tag badge links to /tag/:id', async ({ page }) => {
    await setup(page);
    await expect(page.locator('app-tag-management .tag-badge').first())
      .toHaveAttribute('href', '/tag/1');
  });
});

test.describe('admin tags — New Tag dialog', () => {
  test('opens via New Tag button + Save disabled until name entered', async ({ page }) => {
    await setup(page);
    await page.locator('app-tag-management button', { hasText: 'New Tag' }).click();
    await expect(page.locator('app-tag-management .modal-overlay')).toBeVisible();
    const save = page.locator('app-tag-management .modal-overlay button', { hasText: 'Save' });
    await expect(save).toBeDisabled();
    await page.locator('app-tag-management #tag-dialog-name').fill('Anime');
    await expect(save).toBeEnabled();
  });

  test('save fires CreateTag with name + Color (default red Tailwind-700)', async ({ page }) => {
    await setup(page);
    await page.locator('app-tag-management button', { hasText: 'New Tag' }).click();
    await page.locator('app-tag-management #tag-dialog-name').fill('Anime');
    const req = page.waitForRequest(r =>
      r.url().endsWith(`${AS}/CreateTag`),
      { timeout: 3_000 },
    );
    await page.locator('app-tag-management .modal-overlay button', { hasText: 'Save' }).click();
    const got = await req;
    const decoded = fromBinary(
      CreateTagRequestSchema,
      unframeGrpcWebRequest(got.postDataBuffer()),
    );
    expect(decoded.name).toBe('Anime');
    expect(decoded.color?.hex).toBe('#B91C1C');
  });

  test('color swatch click updates the preview pill', async ({ page }) => {
    await setup(page);
    await page.locator('app-tag-management button', { hasText: 'New Tag' }).click();
    // Pick the Blue swatch (Tailwind-700 = #1D4ED8).
    await page.locator('app-tag-management .color-swatch[title="Blue"]').click();
    await expect(page.locator('app-tag-management .preview'))
      .toHaveAttribute('style', /background-color:\s*rgb\(29,\s*78,\s*216\)/i);
  });
});

test.describe('admin tags — Edit Tag', () => {
  test('Edit button opens dialog pre-filled with the row\'s name + color', async ({ page }) => {
    await setup(page);
    await page.locator('app-tag-management button[aria-label="Edit tag"]').first().click();
    await expect(page.locator('app-tag-management h3', { hasText: 'Edit Tag' })).toBeVisible();
    await expect(page.locator('app-tag-management #tag-dialog-name')).toHaveValue('Family Movie Night');
  });

  test('save PUTs to /api/v2/admin/tags/:id', async ({ page }) => {
    await setup(page);
    await page.locator('app-tag-management button[aria-label="Edit tag"]').first().click();
    await page.locator('app-tag-management #tag-dialog-name').fill('Family Movie Night ⭐');
    const req = page.waitForRequest(r =>
      r.method() === 'PUT' && r.url().endsWith('/api/v2/admin/tags/1'),
      { timeout: 3_000 },
    );
    await page.locator('app-tag-management .modal-overlay button', { hasText: 'Save' }).click();
    const got = await req;
    expect(got.postDataJSON().name).toBe('Family Movie Night ⭐');
  });
});

test.describe('admin tags — Delete Tag', () => {
  test('Delete button opens confirm with title-count caveat', async ({ page }) => {
    await setup(page);
    await page.locator('app-tag-management button[aria-label="Delete tag"]').first().click();
    await expect(page.locator('app-tag-management h3', { hasText: 'Delete Tag' })).toBeVisible();
    await expect(page.locator('app-tag-management .delete-message'))
      .toContainText('applied to 12 titles');
  });

  test('confirm DELETEs /api/v2/admin/tags/:id', async ({ page }) => {
    await setup(page);
    await page.locator('app-tag-management button[aria-label="Delete tag"]').first().click();
    const req = page.waitForRequest(r =>
      r.method() === 'DELETE' && r.url().endsWith('/api/v2/admin/tags/1'),
      { timeout: 3_000 },
    );
    await page.locator('app-tag-management .modal-overlay button', { hasText: 'Delete' }).click();
    await req;
  });
});
