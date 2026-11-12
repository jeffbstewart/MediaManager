import { DOCUMENT, Injectable, computed, effect, inject, signal } from '@angular/core';
import { THEMES, THEME_STORAGE_KEY, ThemeId, isThemeId, readCachedTheme } from './theme-ids';

export { THEMES, type ThemeId } from './theme-ids';

/**
 * The active web theme (docs/plans/web-theming.md, decision 3).
 *
 * `pinned` is the user's explicit choice (null means "follow the
 * default"); `serverDefault` is the server's built-in default. Until
 * the profile loads, the cached effective theme stands in for the
 * default so a returning user keeps their look on the first paint.
 * The effect writes `effective()` to `<html data-theme>` and to
 * localStorage, which `main.ts` reads before the next bootstrap.
 *
 * Templates never branch on the theme; a theme is CSS tokens only.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly doc = inject(DOCUMENT);

  readonly themes = THEMES;
  readonly pinned = signal<ThemeId | null>(null);
  readonly serverDefault = signal<ThemeId>(readCachedTheme(this.storage()));
  readonly effective = computed<ThemeId>(() => this.pinned() ?? this.serverDefault());

  constructor() {
    effect(() => {
      const theme = this.effective();
      this.doc.documentElement.dataset['theme'] = theme;
      try {
        this.storage()?.setItem(THEME_STORAGE_KEY, theme);
      } catch {
        // Storage blocked: the attribute still applies for this page load.
      }
    });
  }

  /** Applies the profile's `web_theme` / `default_theme`; the server wins over the cache. */
  applyFromServer(webTheme: unknown, defaultTheme: unknown): void {
    // A response without a valid default keeps the current one.
    if (isThemeId(defaultTheme)) this.serverDefault.set(defaultTheme);
    this.pinned.set(isThemeId(webTheme) ? webTheme : null);
  }

  /** Pins a theme, or clears the pin with null. The caller persists it on the server. */
  pin(theme: ThemeId | null): void {
    this.pinned.set(theme);
  }

  labelFor(id: ThemeId): string {
    return THEMES.find(t => t.id === id)?.label ?? id;
  }

  private storage(): Storage | null {
    try {
      return this.doc.defaultView?.localStorage ?? null;
    } catch {
      return null;
    }
  }
}
