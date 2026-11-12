import { DOCUMENT, inject } from '@angular/core';
import { NavigationError } from '@angular/router';

/**
 * Recovers from a lazy route chunk that no longer exists on the server.
 *
 * After a redeploy, a tab still running the previous build asks for a
 * route's chunk by its old hashed name. The server answers 404 (or, on
 * older servers, index.html as text/html), the browser's `import()`
 * rejects, and the navigation fails: the page stays where it was and
 * the click seems to do nothing. The only fix is the new build, so this
 * handler reloads the browser at the URL that was asked for. It does so
 * once per URL, remembered in sessionStorage, so a chunk that is really
 * broken cannot send the tab into a reload loop.
 */
export function recoverFromStaleChunk(error: NavigationError): void {
  if (!isChunkLoadError(error.error)) return;
  const win = inject(DOCUMENT).defaultView;
  if (!win) return;
  let storage: Storage | null = null;
  try {
    storage = win.sessionStorage;
  } catch {
    // Storage blocked: reload once anyway; a loop is still bounded by the user leaving.
  }
  const marker = `${RELOAD_KEY}:${error.url}`;
  try {
    if (storage?.getItem(marker)) return;
    storage?.setItem(marker, String(Date.now()));
  } catch {
    // Same as above.
  }
  win.location.assign(error.url);
}

/** Session key prefix; one entry per URL whose chunk failed to load. */
export const RELOAD_KEY = 'mm.stale-chunk-reload';

/** Matches the import() failures Chromium, Firefox, and WebKit raise for a chunk that is missing or not JavaScript. */
export function isChunkLoadError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : typeof error === 'string' ? error : '';
  return /dynamically imported module|module script|importing a module script|ChunkLoadError|Loading chunk/i.test(message);
}
