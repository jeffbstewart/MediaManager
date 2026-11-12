/**
 * Top-level sections of the app, as the bottom tab bar and the page
 * glow see them. Derived from the router URL alone so the shell and
 * the tab bar agree without sharing state.
 */
export type PageSection = 'home' | 'live-tv' | 'movies-tv' | 'discover';

function under(path: string, prefix: string): boolean {
  return path === prefix || path.startsWith(prefix + '/');
}

/** The section a URL belongs to, or null for every other page. */
export function sectionForUrl(url: string): PageSection | null {
  const path = url.split(/[?#]/, 1)[0] || '/';
  if (path === '/') return 'home';
  if (under(path, '/live-tv')) return 'live-tv';
  if (under(path, '/content/movies') || under(path, '/content/tv')) return 'movies-tv';
  if (under(path, '/discover')) return 'discover';
  return null;
}
