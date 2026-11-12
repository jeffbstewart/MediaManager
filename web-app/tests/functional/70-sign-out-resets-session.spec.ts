import type { Page } from '@playwright/test';
import { clone } from '@bufbuild/protobuf';
import { test, expect } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { loadFixture } from '../helpers/load-fixture';
import { fulfillProto } from '../helpers/proto-fixture';
import { expectView } from '../helpers/expect-view';
import { FeaturesSchema, HomeFeedResponseSchema } from '../../src/app/proto-gen/catalog_pb';
import { featuresAdmin, featuresViewer, homeFeedPopulated } from '../fixtures-typed/home-feed.fixture';

// Signing out must leave nothing of the previous user behind. On the
// NAS (2026-11-10) signing out as an admin and in as a viewer kept the
// admin's name in the account pill and the Manage section in the nav
// until the page was reloaded: the services that cache the profile and
// the feature flags are singletons that outlived the route change.
// Sign-out is now a full page load, so every cache starts over. The two
// tests swap roles in each direction: the viewer who follows an admin
// must see no admin functionality, and the admin who follows a viewer
// must get all of it.

type Role = 'admin' | 'viewer';

const profile = loadFixture('profile/profile.json') as Record<string, unknown>;

/**
 * Serve one user's profile and feature flags. Later routes win, so
 * calling this again swaps the user the backend answers for.
 */
async function serveUser(page: Page, username: string, role: Role): Promise<void> {
  const features = role === 'admin' ? featuresAdmin : featuresViewer;
  await page.route('**/api/v2/profile', r =>
    r.fulfill({ json: { ...profile, username, is_admin: role === 'admin' } }));
  // Feature flags arrive both on their own and inside the home feed.
  await page.route('**/mediamanager.CatalogService/GetFeatures', r =>
    fulfillProto(r, FeaturesSchema, features));
  const home = clone(HomeFeedResponseSchema, homeFeedPopulated);
  home.features = clone(FeaturesSchema, features);
  await page.route('**/mediamanager.CatalogService/HomeFeed', r =>
    fulfillProto(r, HomeFeedResponseSchema, home));
}

const pill = (page: Page) => page.locator('app-account-menu button').first();
const userMenu = (page: Page) => page.locator('dialog.sheet[aria-label="User menu"]');
const sideNav = (page: Page) => page.locator('aside.side-nav');
const adminLinks = (page: Page) => sideNav(page).locator('a[href*="/admin/"]');

/** The shell shows `username` with the affordances of `role` and none of the other role's. */
async function expectSignedInAs(page: Page, username: string, role: Role): Promise<void> {
  await expect(pill(page)).toContainText(username);
  await pill(page).click();
  await expect(userMenu(page).getByText(role === 'admin' ? 'Admin' : 'Viewer', { exact: true })).toBeVisible();
  await page.keyboard.press('Escape');
  const manage = sideNav(page).getByText('Manage', { exact: true });
  if (role === 'admin') {
    await expect(manage).toBeVisible();
    await expect(adminLinks(page).first()).toBeVisible();
  } else {
    await expect(manage).toHaveCount(0);
    await expect(adminLinks(page)).toHaveCount(0);
  }
}

async function signOut(page: Page): Promise<void> {
  // The server clears the session on logout, so the reload's refresh
  // attempt fails and the login page shows; the login POST that follows
  // answers with a fresh token (mockBackend's default).
  await page.route('**/api/v2/auth/refresh', r =>
    r.fulfill({ status: 401, json: { error: 'unauthenticated' } }));
  const logout = page.waitForRequest(r => r.method() === 'POST' && r.url().endsWith('/api/v2/auth/logout'));
  await pill(page).click();
  await userMenu(page).getByText('Sign Out').click();
  await logout;
  await expectView(page, /\/login$/, 'app-login');
}

async function signIn(page: Page, username: string): Promise<void> {
  await page.locator('app-login input[formcontrolname="username"]').fill(username);
  await page.locator('app-login input[formcontrolname="password"]').fill('hunter2');
  await page.locator('app-login button[type="submit"]').click();
  await expectView(page, /\/$/, 'app-home');
}

test('a viewer who signs in after an admin signs out gets no admin functionality', async ({ page }) => {
  await mockBackend(page, { features: 'admin' });
  await loginAs(page);
  await stubImages(page);
  await serveUser(page, 'firstadmin', 'admin');
  await page.goto('/');
  await expectSignedInAs(page, 'firstadmin', 'admin');

  await serveUser(page, 'secondviewer', 'viewer');
  await signOut(page);
  await signIn(page, 'secondviewer');
  await expectSignedInAs(page, 'secondviewer', 'viewer');
  await expect(pill(page)).not.toContainText('firstadmin');
});

test('an admin who signs in after a viewer signs out gets the admin functionality', async ({ page }) => {
  await mockBackend(page, { features: 'viewer' });
  await loginAs(page);
  await stubImages(page);
  await serveUser(page, 'firstviewer', 'viewer');
  await page.goto('/');
  await expectSignedInAs(page, 'firstviewer', 'viewer');

  await serveUser(page, 'secondadmin', 'admin');
  await signOut(page);
  await signIn(page, 'secondadmin');
  await expectSignedInAs(page, 'secondadmin', 'admin');
  // The admin pages open too, not only the links to them.
  await sideNav(page).getByText('Users', { exact: true }).click();
  await expectView(page, /\/admin\/users$/, 'app-users');
});
