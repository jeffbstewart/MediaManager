import { test, expect, Page } from '../helpers/test-fixture';
import { mockBackend } from '../helpers/mock-backend';
import { loginAs } from '../helpers/login-as';
import { stubImages } from '../helpers/image-stub';
import { expectView } from '../helpers/expect-view';

// Artists in Your Library (spec 3.6, home composition row 10): one person
// card per artist behind the recently added albums, from the feed's
// recent_artists. Headshot when the server has one, else the first album's
// poster, else the initial. Fixture: Miles Davis (id 7, headshot, 1 album)
// and Bill Evans (id 8, no headshot, fallback album 301, 2 albums).

const row = (page: Page) => page.locator('app-home .carousel-section', { hasText: 'Artists in Your Library' });

test.describe('home artists row', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackend(page);
    await loginAs(page);
    await stubImages(page);
    await page.goto('/');
    await page.waitForSelector('app-home app-person-card');
  });

  test('shows a person card per recent artist with counts and a link to the artist', async ({ page }) => {
    const cards = row(page).locator('app-person-card');
    await expect(cards).toHaveCount(2);
    const first = cards.nth(0);
    await expect(first.locator('.n')).toHaveText('Miles Davis');
    await expect(first.locator('.m')).toHaveText(['1 Album']);
    await expect(first.locator('.pill')).toHaveText('View artist');
    await expect(first.locator('a.person')).toHaveAttribute('href', /\/artist\/7$/);
    await expect(first.locator('.person-img img')).toHaveAttribute('src', /\/artist-headshots\/7$/);
  });

  test('falls back to the first album poster when there is no headshot', async ({ page }) => {
    const second = row(page).locator('app-person-card').nth(1);
    await expect(second.locator('.n')).toHaveText('Bill Evans');
    await expect(second.locator('.m')).toHaveText(['2 Albums']);
    await expect(second.locator('.person-img img')).toHaveAttribute('src', /\/posters\/w185\/301$/);
  });

  test('sits between Continue Listening and Recently Added Albums, after the book rows', async ({ page }) => {
    const headings = await page.locator('app-home .home-container > section h2').evaluateAll(els =>
      els.map(el => el.textContent!.trim()));
    const i = headings.indexOf('Artists in Your Library');
    expect(headings[i - 1]).toBe('Continue Listening');
    expect(headings[i + 1]).toBe('Recently Added Albums');
    expect(headings.indexOf('Resume Reading')).toBeLessThan(headings.indexOf('Continue Listening'));
    expect(headings.indexOf('Recently Added Books')).toBeLessThan(headings.indexOf('Continue Listening'));
  });

  test('a card opens the artist page', async ({ page }) => {
    await row(page).locator('app-person-card a.person').first().click();
    await expectView(page, /\/artist\/7$/, 'app-artist');
  });
});
