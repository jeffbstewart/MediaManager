import { expect, Page } from '@playwright/test';

/**
 * Asserts that a navigation landed: the URL matches and the view for it
 * is rendered, containing `text` when given.
 *
 * The URL alone is not enough. Angular reuses a view when only a route
 * parameter changes, so a page that never reloaded still shows the new
 * URL (the Similar Titles row did exactly that). When a test navigates
 * between two pages of the same kind, pass the text that identifies the
 * destination, usually its title.
 */
export async function expectView(page: Page, url: RegExp, view: string, text?: string | RegExp): Promise<void> {
  await expect(page).toHaveURL(url);
  const root = page.locator(view);
  await expect(root).toBeAttached();
  if (text !== undefined) await expect(root).toContainText(text);
}
