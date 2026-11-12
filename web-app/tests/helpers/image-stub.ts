import type { Page, Route } from '@playwright/test';

/**
 * 1×1 grey PNG. Decoded lazily once and handed to Playwright's route
 * fulfill() as the body for every poster / headshot / album-art URL.
 * Axe doesn't inspect image pixels, only alt attributes + dimensions,
 * so a single tiny PNG covers every image endpoint the app queries.
 */
const PLACEHOLDER_PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4//8/AwAI/AL+XJ/PswAAAABJRU5ErkJggg==';

export interface StubImagesOptions {
  /**
   * Answer each image URL with deterministic abstract artwork at the
   * endpoint's natural aspect ratio instead of the 1×1 PNG (see artFor).
   * `true` prints the id from the URL faintly over the art, so a test
   * screenshot still says which title it shows; `{ label: false }` omits
   * it for pictures that are published. Used by the visual capture suite
   * and the docs screenshots so pages show real card geometry, shadows,
   * and overlays. Axe and functional specs keep the default, which is
   * cheaper and pixel-blind anyway.
   */
  generatedArt?: boolean | { label?: boolean };
}

/** Endpoint → width × height of the generated SVG. */
const ART_SHAPES: ReadonlyArray<readonly [RegExp, number, number]> = [
  [/\/backdrops\//, 1280, 720],
  [/\/cam\//, 1280, 720],
  [/\/(headshots|author-headshots|artist-headshots)\//, 400, 400],
  [/\/public\/album-art\//, 600, 600],
  // Cover Art Archive proxies (album covers) are square; the Open
  // Library and TMDB proxies fall through to the poster shape.
  [/\/proxy\/caa\//, 600, 600],
  // Posters, collection posters, local images, ownership photos.
  [/.*/, 400, 600],
];

/** FNV-1a over the path: a stable 32-bit seed for a URL. */
function seedFor(path: string): number {
  let h = 0x811c9dc5;
  for (const ch of path) {
    h ^= ch.charCodeAt(0);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h;
}

/** mulberry32: a small deterministic PRNG, uniform in [0, 1). */
function rng(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * Deterministic abstract artwork for an image URL: a two-stop gradient,
 * a handful of translucent circles, rings, polygons, and bands in hues
 * related to the base, and a dot-grain texture. The same URL always
 * draws the same picture, so Argos diffs stay quiet, and nothing in it
 * resembles a real poster, so the pictures can be published. The id in
 * the URL sets the base hue (and, when `label` is on, is printed large
 * and faint so a test screenshot still says which title it shows).
 */
export function artFor(url: string, label = true): string {
  const path = url.replace(/^https?:\/\/[^/]+/, '').split('?')[0];
  const [, w, h] = ART_SHAPES.find(([re]) => re.test(path))!;
  const idMatch = path.match(/(\d+)(?!.*\d)/);
  const id = idMatch ? idMatch[1] : null;
  const next = rng(seedFor(path));
  const hue = id ? (Number(id) * 47) % 360 : Math.floor(next() * 360);
  const pick = (lo: number, hi: number) => lo + next() * (hi - lo);
  const tone = (shift: number, s: number, l: number, a: number) =>
    `hsl(${(hue + shift + 360) % 360} ${s}% ${l}% / ${a})`;

  const angle = Math.floor(pick(0, 360));
  const parts: string[] = [];
  const count = 5 + Math.floor(next() * 4);
  for (let i = 0; i < count; i++) {
    const kind = next();
    const cx = pick(-0.1, 1.1) * w;
    const cy = pick(-0.1, 1.1) * h;
    const r = pick(0.12, 0.42) * Math.min(w, h);
    const shift = [0, 30, -30, 150, 180, 210][Math.floor(next() * 6)];
    const fill = tone(shift, Math.floor(pick(45, 70)), Math.floor(pick(35, 62)), pick(0.14, 0.34).toFixed(2) as unknown as number);
    if (kind < 0.3) {
      parts.push(`<circle cx="${cx.toFixed(1)}" cy="${cy.toFixed(1)}" r="${r.toFixed(1)}" fill="${fill}"/>`);
    } else if (kind < 0.5) {
      const sw = pick(0.05, 0.14) * r;
      parts.push(`<circle cx="${cx.toFixed(1)}" cy="${cy.toFixed(1)}" r="${r.toFixed(1)}" fill="none" stroke="${fill}" stroke-width="${sw.toFixed(1)}"/>`);
    } else if (kind < 0.8) {
      const sides = 3 + Math.floor(next() * 4);
      const rot = pick(0, Math.PI * 2);
      const pts: string[] = [];
      for (let k = 0; k < sides; k++) {
        const a = rot + (k / sides) * Math.PI * 2;
        pts.push(`${(cx + Math.cos(a) * r).toFixed(1)},${(cy + Math.sin(a) * r).toFixed(1)}`);
      }
      parts.push(`<polygon points="${pts.join(' ')}" fill="${fill}"/>`);
    } else {
      const bw = pick(0.06, 0.18) * Math.max(w, h);
      const rot = Math.floor(pick(-60, 60));
      parts.push(`<rect x="${(cx - w).toFixed(1)}" y="${(cy - bw / 2).toFixed(1)}" width="${(w * 2).toFixed(1)}" height="${bw.toFixed(1)}" fill="${fill}" transform="rotate(${rot} ${cx.toFixed(1)} ${cy.toFixed(1)})"/>`);
    }
  }

  const fontSize = Math.round(Math.min(w, h) * 0.28);
  const text = label && id
    ? `<text x="50%" y="50%" font-family="Inter, sans-serif" font-size="${fontSize}" font-weight="700" fill="rgba(255,255,255,0.18)" text-anchor="middle" dominant-baseline="central">${id}</text>`
    : '';
  const dot = Math.max(2, Math.round(Math.min(w, h) / 120));
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">
  <defs>
    <linearGradient id="g" gradientTransform="rotate(${angle} 0.5 0.5)">
      <stop offset="0" stop-color="${tone(0, 42, 34, 1)}"/>
      <stop offset="1" stop-color="${tone(40, 48, 15, 1)}"/>
    </linearGradient>
    <pattern id="p" width="${dot * 3}" height="${dot * 3}" patternUnits="userSpaceOnUse">
      <circle cx="${dot}" cy="${dot}" r="${(dot / 2).toFixed(1)}" fill="rgba(255,255,255,0.05)"/>
    </pattern>
    <clipPath id="c"><rect width="${w}" height="${h}"/></clipPath>
  </defs>
  <rect width="${w}" height="${h}" fill="url(#g)"/>
  <g clip-path="url(#c)">${parts.join('')}</g>
  <rect width="${w}" height="${h}" fill="url(#p)"/>
  <rect x="0.5" y="0.5" width="${w - 1}" height="${h - 1}" fill="none" stroke="rgba(255,255,255,0.12)"/>
  ${text}
</svg>`;
}

/**
 * Register catch-all handlers for every image endpoint that the web-app
 * may request. Each returns a shared 1×1 PNG, or generated artwork when
 * `generatedArt` is set. Registered on the page before navigation so the
 * first render finds them already in place.
 */
export async function stubImages(page: Page, opts: StubImagesOptions = {}): Promise<void> {
  const art = opts.generatedArt;
  const label = art === true || (typeof art === 'object' && art.label !== false);
  const handler = art
    ? (r: Route) =>
        r.fulfill({
          status: 200,
          headers: { 'Cache-Control': 'no-store', 'Content-Type': 'image/svg+xml' },
          body: artFor(r.request().url(), label),
        })
    : (r: Route) =>
        r.fulfill({
          status: 200,
          headers: { 'Cache-Control': 'no-store', 'Content-Type': 'image/png' },
          body: Buffer.from(PLACEHOLDER_PNG_BASE64, 'base64'),
        });

  const globs = [
    '**/posters/**',
    '**/headshots/**',
    '**/author-headshots/**',
    '**/artist-headshots/**',
    '**/backdrops/**',
    '**/collection-posters/**',
    '**/local-images/**',
    '**/ownership-photos/**',
    '**/public/album-art/**',
    '**/cam/**',
    // Server-side proxies for third-party art: Open Library covers,
    // Cover Art Archive covers, TMDB posters of unowned titles.
    '**/proxy/**',
    '**/tmdb-poster/**',
    'https://image.tmdb.org/**',
  ];
  for (const g of globs) {
    await page.route(g, handler);
  }
}
