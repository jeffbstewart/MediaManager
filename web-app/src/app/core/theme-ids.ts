// Theme ids and the local cache key, with no Angular dependency so
// `main.ts` can read them before bootstrap (docs/plans/web-theming.md,
// decision 3). Ids are persisted per user on the server
// (`app_user.web_theme`, Kotlin `WebTheme`): never rename one without a
// migration.

// One theme ships today: classic was retired on 2026-11-10 (the server's
// V103 cleared stored pins). A cached or stored 'classic' is unknown here
// and falls back to the default. The type, the list, and the service stay
// so a second theme is a matter of adding an entry.
export type ThemeId = 'marquee';

/** Every theme the SPA ships. */
export const THEMES: ReadonlyArray<{ readonly id: ThemeId; readonly label: string }> = [
  { id: 'marquee', label: 'Marquee' },
];

/**
 * Compile-time copy of the server's built-in default (`WebTheme.DEFAULT`),
 * used only until the profile response says otherwise. `index.html`
 * carries the same value as its `data-theme` fallback; a server test
 * checks the two against `WebTheme.DEFAULT`.
 */
export const FALLBACK_THEME: ThemeId = 'marquee';

/** localStorage key holding the last effective theme, read before first paint. */
export const THEME_STORAGE_KEY = 'mm.theme.effective';

export function isThemeId(value: unknown): value is ThemeId {
  return typeof value === 'string' && THEMES.some(t => t.id === value);
}

/** The cached effective theme, or the fallback when storage is empty, blocked or stale. */
export function readCachedTheme(storage: Pick<Storage, 'getItem'> | null): ThemeId {
  try {
    const v = storage?.getItem(THEME_STORAGE_KEY);
    return isThemeId(v) ? v : FALLBACK_THEME;
  } catch {
    return FALLBACK_THEME;
  }
}

function safeLocalStorage(): Storage | null {
  try {
    return globalThis.localStorage ?? null;
  } catch {
    return null;
  }
}

/** Sets `data-theme` on `<html>` from the cache. Called by `main.ts` before bootstrap. */
export function applyCachedTheme(doc: Document): ThemeId {
  const theme = readCachedTheme(safeLocalStorage());
  doc.documentElement.dataset['theme'] = theme;
  return theme;
}
