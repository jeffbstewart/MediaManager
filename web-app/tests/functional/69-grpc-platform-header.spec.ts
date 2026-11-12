import { test, expect } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { CLIENT_PLATFORM, CLIENT_PLATFORM_HEADER } from '../../src/app/core/client-platform';

// Every gRPC call from the SPA names its platform. The server's legal
// gate reads `x-client-platform` to pick the terms-of-use document that
// applies; without it the gate fell back to the strictest platform's
// version, which a web user could never satisfy, and every non-admin
// saw "Failed to load home feed" (2026-11-10).

test.describe('gRPC platform header', () => {
  test('the home feed call carries x-client-platform: web', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    const feed = page.waitForRequest(r => r.url().endsWith('/mediamanager.CatalogService/HomeFeed'));
    await page.goto('/');
    const req = await feed;
    expect(req.headers()[CLIENT_PLATFORM_HEADER]).toBe(CLIENT_PLATFORM);
    expect(req.headers()['content-type']).toContain('grpc-web');
  });

  test('a detail call carries it too', async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    const detail = page.waitForRequest(r => r.url().endsWith('/mediamanager.CatalogService/GetTitleDetail'));
    await page.goto('/title/100');
    expect((await detail).headers()[CLIENT_PLATFORM_HEADER]).toBe(CLIENT_PLATFORM);
  });
});
