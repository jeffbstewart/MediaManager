import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { fulfillProto, unframeGrpcWebRequest } from '../helpers/proto-fixture';
import { create, fromBinary } from '@bufbuild/protobuf';
import {
  CreateTagRequestSchema,
  CreateTagResponseSchema,
  CreateTagResult,
} from '../../src/app/proto-gen/admin_pb';

// shared/tag-picker — modal that lists existing tags + "Create new
// tag" affordance. Mounted by the title-detail page (admin-only —
// "+ Tag" button under each title's existing tag chips).

const AS = '/mediamanager.AdminService';

async function setup(page: Page) {
  await mockBackend(page, { features: 'admin' });
  await loginAs(page);
  await stubImages(page);
  await page.route(`**${AS}/CreateTag`, r =>
    fulfillProto(r, CreateTagResponseSchema, create(CreateTagResponseSchema, {
      result: CreateTagResult.CREATED,
      id: 99n,
    })));
  // SetTitleTags via gRPC is no-op'd by mock-backend's default
  // dispatch; tests that want to capture the request register their
  // own override before this setup runs.
  await page.goto('/title/100');
  await page.waitForSelector('app-title-detail');
}

async function openTagPicker(page: Page) {
  await page.locator('app-title-detail button.tag-add-btn').first().click();
  await page.waitForSelector('app-tag-picker .picker');
}

test.describe('tag-picker — render', () => {
  test('opens with heading + tag list from /api/v2/catalog/tags fixture', async ({ page }) => {
    await setup(page);
    await openTagPicker(page);
    await expect(page.locator('app-tag-picker h2')).toContainText(/tag/i);
    // catalog/tags.list.json has 3 tags. The default title.movie.json
    // (id=100) already has Comfort Watch (id=1) attached, so the picker
    // filters it out via excludeTagIds → 2 visible.
    await expect(page.locator('app-tag-picker .picker-list li')).toHaveCount(2);
    await expect(page.locator('app-tag-picker .tag-chip').first()).toContainText('For Rainy Days');
  });

  test('Create-new affordance is visible', async ({ page }) => {
    await setup(page);
    await openTagPicker(page);
    await expect(page.locator('app-tag-picker .create-row'))
      .toContainText('Create new tag');
  });

  test('tag pills render with the fixture bg color', async ({ page }) => {
    await setup(page);
    await openTagPicker(page);
    // For Rainy Days bg = #0D47A1 → rgb(13, 71, 161).
    await expect(page.locator('app-tag-picker .tag-chip').first())
      .toHaveAttribute('style', /background-color:\s*rgb\(13,\s*71,\s*161\)/i);
  });

  test('Close button fires (cancelled)', async ({ page }) => {
    await setup(page);
    await openTagPicker(page);
    await page.locator('app-tag-picker button[aria-label="Close"]').click();
    await expect(page.locator('app-tag-picker .picker')).toHaveCount(0);
  });
});

test.describe('tag-picker — pick existing', () => {
  test('clicking a tag fires the parent\'s SetTitleTags RPC', async ({ page }) => {
    await setup(page);
    await openTagPicker(page);
    const req = page.waitForRequest(r =>
      r.method() === 'POST'
      && r.url().endsWith('/mediamanager.CatalogService/SetTitleTags'),
      { timeout: 3_000 },
    );
    await page.locator('app-tag-picker .picker-row', { hasText: 'For Rainy Days' }).click();
    await req;
    // Picker auto-closes once the parent processes (picked).
    await expect(page.locator('app-tag-picker .picker')).toHaveCount(0);
  });
});

test.describe('tag-picker — create new', () => {
  test('Create-new prompts, fires CreateTag, then refetches', async ({ page }) => {
    await setup(page);
    await openTagPicker(page);
    page.on('dialog', d => d.accept('Workout'));
    const created = page.waitForRequest(r =>
      r.url().endsWith(`${AS}/CreateTag`),
      { timeout: 3_000 },
    );
    await page.locator('app-tag-picker .create-row').click();
    const got = await created;
    const decoded = fromBinary(
      CreateTagRequestSchema,
      unframeGrpcWebRequest(got.postDataBuffer()),
    );
    expect(decoded.name).toBe('Workout');
    expect(decoded.color?.hex).toBe('#6B7280');
  });

  test('Create-new with a blank prompt is a no-op', async ({ page }) => {
    await setup(page);
    await openTagPicker(page);
    let fired = false;
    page.on('request', r => {
      if (r.url().endsWith(`${AS}/CreateTag`)) fired = true;
    });
    page.on('dialog', d => d.accept('   '));
    await page.locator('app-tag-picker .create-row').click();
    await page.waitForTimeout(200);
    expect(fired).toBe(false);
    await expect(page.locator('app-tag-picker .picker')).toBeVisible();
  });
});

test.describe('tag-picker — exclude already-attached', () => {
  test('tags listed in title.tags are filtered out of the picker list', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    // Base movie 100 typed fixture already attaches the Comfort Watch
    // tag (id=1), so the picker should drop it from the visible list.
    await page.goto('/title/100');
    await page.waitForSelector('app-title-detail');
    await openTagPicker(page);
    // catalog/tags.list.json has 3 tags; one is already attached → 2 visible.
    await expect(page.locator('app-tag-picker .picker-list li')).toHaveCount(2);
    await expect(page.locator('app-tag-picker .picker-list')).not.toContainText('Comfort Watch');
  });
});

test.describe('tag-picker — error state', () => {
  test('failed tag list shows the error message', async ({ page }) => {
    await mockBackend(page, { features: 'admin' });
    await loginAs(page);
    await stubImages(page);
    // ListTags now lands on gRPC; an HTTP 500 still maps to a Connect
    // RPC error and the picker's error branch.
    await page.route('**/mediamanager.CatalogService/ListTags', r =>
      r.fulfill({ status: 500 }));
    await page.goto('/title/100');
    await page.waitForSelector('app-title-detail');
    await openTagPicker(page);
    await expect(page.locator('app-tag-picker .picker-error'))
      .toContainText('Failed to load');
  });
});
