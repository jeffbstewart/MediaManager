import '@fontsource/inter/400.css';
import '@fontsource/inter/600.css';
import '@fontsource/inter/700.css';
import '@fontsource/figtree/800.css';
import './styles.css';
import lib from './library.json';
import { icon } from './icons';

// ------------------------------------------------------------------ data
// library.json is a snapshot of mediamanager's /api/v2/catalog/home feed,
// title details, title lists and live-TV channels, pulled on 2026-11-07.

type Title = {
  title_id: number; title_name: string; media_type?: string; release_year?: number;
  content_rating?: string | null; description?: string | null; authors?: string[];
  artists?: { id: number; name: string }[]; track_count?: number; duration_min?: number;
  cast?: string[]; season_count?: number; page_count?: number;
};
type FeedItem = { title_id: number; title_name: string; release_year?: number; content_rating?: string | null; author_name?: string; artist_name?: string; track_count?: number; missing_seasons?: { season_number: number }[] };

const L = lib as unknown as {
  home: Record<string, FeedItem[]>;
  titles: Record<string, Title>;
  movies: FeedItem[]; tv: FeedItem[];
  channels: { id: number; guide_number: string; guide_name: string; network_affiliation?: string }[];
  tints: Record<string, string>; posters: number[]; headshots: number[]; backdrops: number[];
};
const H = L.home;
const posterSet = new Set(L.posters);
const backdropSet = new Set(L.backdrops);
const headshotSet = new Set(L.headshots);
const T = (id: number): Title => L.titles[String(id)] ?? ({ title_id: id, title_name: '' } as Title);

// SAMPLE progress. The account used for this mock-up has no watch/read/listen
// history, so these rows reproduce the owner's own rows from a screenshot of
// their main account (titles, minutes left, % read; bar lengths measured).
const fmtLeft = (min: number) => (min >= 60 ? `${Math.floor(min / 60)}h ${min % 60}m left` : `${min}m left`);
const SAMPLE_WATCHING = [
  { id: 518, min: 80, pct: 0.11 },   // Taken
  { id: 493, min: 10, pct: 0.92 },   // Star Trek Into Darkness
  { id: 1207, min: 52, pct: 0.56 },  // Independence Day: Resurgence
  { id: 519, min: 58, pct: 0.46 },   // Top Gun
  { id: 419, min: 36, pct: 0.7 },    // John Wick: Chapter 2
];
const SAMPLE_READING = [
  { id: 709, pct: 0.04 }, { id: 711, pct: 0.99 }, { id: 710, pct: 1 }, { id: 1042, pct: 0 }, { id: 1097, pct: 0.31 },
  { id: 1174, pct: 0 }, { id: 1132, pct: 0.21 }, { id: 1048, pct: 0 }, { id: 838, pct: 0 },
];
const SAMPLE_LISTENING = [
  { id: 1015, track: 'Material Girl', artist: 'Madonna', pct: 0.28 },
  { id: 765, track: "Don't Know Much", artist: 'Various Artists', pct: 0.98 },
  { id: 1007, track: 'Forever and a Day', artist: 'Nicki French', pct: 0.98 },
  { id: 764, track: 'Love Theme From “Blade Runner”', artist: 'Vangelis', pct: 0.98 },
  { id: 892, track: 'Dreams That I Carry', artist: 'Jon Secada', pct: 0.39 },
  { id: 1118, track: 'Respect Yourself', artist: 'Various Artists', pct: 0.98 },
  { id: 818, track: "Ain't No Fun (Waiting Round to Be a Millionaire)", artist: 'AC/DC', pct: 0.98 },
  { id: 794, track: 'Chains Around My Heart', artist: 'Various Artists', pct: 0.65 },
  { id: 757, track: 'Think Twice', artist: 'Céline Dion', pct: 0.5 },
];

// --------------------------------------------------------------- helpers
const esc = (s: unknown) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]!));
const poster = (id: number) => (posterSet.has(id) ? `/media/p${id}.jpg` : '');
const backdrop = (id: number) => (backdropSet.has(id) ? `/media/b${id}.jpg` : '');
const hue = (s: string) => [...s].reduce((h, c) => (h * 31 + c.charCodeAt(0)) % 360, 7);
const typeLabel = (t?: string) => ({ MOVIE: 'Movie', TV: 'TV Series', BOOK: 'Book', ALBUM: 'Album', PERSONAL: 'Home Video' }[t ?? ''] ?? '');
const titleHref = (id: number) => `#title-${id}`;

function art(id: number, name: string, lazy = true): string {
  const src = poster(id);
  if (src) return `<img src="${src}" alt="" ${lazy ? 'loading="lazy"' : ''} decoding="async">`;
  const h = hue(name);
  return `<div class="placeholder-art" style="background:linear-gradient(160deg,hsl(${h} 40% 32%),hsl(${(h + 40) % 360} 45% 16%))"><span class="initial">${esc(name.trim()[0] ?? '?')}</span><span>${esc(name)}</span></div>`;
}

function progress(pct: number) {
  return `<div class="progress" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${Math.round(pct * 100)}"><i style="width:${(pct * 100).toFixed(1)}%"></i></div>`;
}

function sectionHead(title: string, opts: { tag?: string; href?: string; id?: string; sub?: string } = {}) {
  const tag = opts.tag ? `<span class="tag">${icon('chev')}${esc(opts.tag)}</span>` : '';
  const h = `<h2${opts.id ? ` id="${opts.id}"` : ''}>${esc(title)}</h2>`;
  const inner = opts.href ? `<a href="${opts.href}">${h}${icon('right')}</a>` : h;
  return `<div class="section-head">${tag}${inner}</div>${opts.sub ? `<p class="section-sub">${esc(opts.sub)}</p>` : ''}`;
}

function row(items: string[], cls = '', label = '') {
  return `<div class="row-wrap"><ul class="row ${cls}"${label ? ` aria-label="${esc(label)}"` : ''}>${items.map((i) => `<li>${i}</li>`).join('')}</ul>
    <button class="row-arrow prev" type="button" aria-label="Scroll left" hidden>${icon('left')}</button>
    <button class="row-arrow next" type="button" aria-label="Scroll right">${icon('right')}</button></div>`;
}

function posterCard(id: number, name: string, lines: { t?: string; m?: string; accent?: boolean } = {}, extra = '', square = false) {
  return `<a class="poster${square ? ' square' : ''}" href="${titleHref(id)}" aria-label="${esc(name)}">
    <div class="art">${art(id, name)}${extra}</div>
    ${lines.t || lines.m ? `<div class="caption">${lines.t ? `<span class="t">${esc(lines.t)}</span>` : ''}${lines.m ? `<span class="m${lines.accent ? ' accent' : ''}">${esc(lines.m)}</span>` : ''}</div>` : ''}
  </a>`;
}

function wideCard(id: number, t: string, m: string, inner: string, accent = false) {
  return `<a class="wide" href="${titleHref(id)}" aria-label="${esc(t)}">
    <div class="art">${inner}</div>
    <div class="caption"><span class="t">${esc(t)}</span><span class="m${accent ? ' accent' : ''}">${esc(m)}</span></div></a>`;
}

function wideArt(id: number, name: string, extra = '') {
  const b = backdrop(id);
  const img = b ? `<img src="${b}" alt="" loading="lazy" decoding="async">` : art(id, name);
  return `${img}<div class="scrim"></div><span class="overlay-title">${esc(name)}</span>${extra}`;
}

function hero(id: number, kicker: string, opts: { full?: boolean; action?: string; eager?: boolean } = {}) {
  const t = T(id);
  const tint = L.tints[String(id)] ?? '#2a2a2a';
  const meta = [
    t.content_rating ? `<span class="rating">${esc(t.content_rating)}</span>` : '',
    t.release_year ? `<span>${t.release_year}</span>` : '',
    `<span>${esc([typeLabel(t.media_type), t.season_count ? `${t.season_count} Seasons` : '', ...(t.cast ?? []).slice(0, 2)].filter(Boolean).join(', '))}</span>`,
  ].join('');
  const long = t.title_name.length > 22 ? ' long' : '';
  return `<article class="hero${opts.full ? ' full' : ''}" style="--tint:${tint}" aria-label="${esc(t.title_name)}">
    <div class="hero-img"><img src="${backdrop(id)}" alt="" ${opts.eager ? 'fetchpriority="high"' : 'loading="lazy"'} decoding="async"></div>
    <div class="hero-body">
      <h3 class="hero-title${long}">${esc(t.title_name)}</h3>
      <div class="meta">${meta}</div>
      <p class="hero-kicker">${esc(kicker)}</p>
      <p class="hero-desc">${esc(t.description ?? '')}</p>
      <div class="hero-actions">
        <a class="btn" href="${titleHref(id)}">${icon('play')}${esc(opts.action ?? 'Play')}</a>
        <a class="btn ghost" href="${titleHref(id)}">${icon('add')}Wish List</a>
      </div>
    </div></article>`;
}

function heroCarousel(ids: { id: number; kicker: string; action?: string }[]) {
  return `<div class="hero-carousel">${row(ids.map((h, i) => hero(h.id, h.kicker, { action: h.action, eager: i === 0 })), 'hero-row', 'Featured')}
    <div class="hero-dots" role="tablist">${ids.map((_, i) => `<button type="button" aria-label="Show slide ${i + 1}" ${i === 0 ? 'aria-current="true"' : ''}></button>`).join('')}</div></div>`;
}

// ------------------------------------------------------------- the shell
const NAV: { label: string; icon: string; href: string; page?: string }[] = [
  { label: 'Home', icon: 'home', href: '/', page: 'home' },
  { label: 'Books', icon: 'books', href: '/#books' },
  { label: 'Cameras', icon: 'camera', href: '#cameras' },
  { label: 'Collections', icon: 'collections', href: '#collections' },
  { label: 'Discover', icon: 'discover', href: '/discover/trending', page: 'trending' },
  { label: 'Family', icon: 'family', href: '#family' },
  { label: 'Live TV', icon: 'livetv', href: '/live-tv', page: 'live-tv' },
  { label: 'Movies', icon: 'movie', href: '/on-demand', page: 'on-demand' },
  { label: 'Music', icon: 'album', href: '/#albums' },
  { label: 'Playlists', icon: 'playlist', href: '#playlists' },
  { label: 'My Wish List', icon: 'heart', href: '#wishlist' },
  { label: 'Tags', icon: 'tag', href: '#tags' },
  { label: 'TV Shows', icon: 'tv', href: '/on-demand#tv' },
];
const TABS = [
  { label: 'Home', icon: 'home', href: '/', page: 'home' },
  { label: 'Live TV', icon: 'livetv', href: '/live-tv', page: 'live-tv' },
  { label: 'Movies & TV', icon: 'movie', href: '/on-demand', page: 'on-demand' },
  { label: 'Discover', icon: 'discover', href: '/discover/trending', page: 'trending' },
];

const FOOTER: [string, [string, string][]][] = [
  ['Library', [['Movies', '/on-demand'], ['TV Shows', '/on-demand#tv'], ['Books', '/#books'], ['Music', '/#albums'], ['Collections', '#collections']]],
  ['Watch', [['Live TV', '/live-tv'], ['Cameras', '#cameras'], ['Family', '#family'], ['Playlists', '#playlists'], ['Discover', '/discover/trending']]],
  ['Apps', [['Web', '/'], ['iPhone & iPad', '#ios'], ['Android TV', '#android-tv'], ['Roku', '#roku']]],
  ['Account', [['Profile', '#profile'], ['My Wish List', '#wishlist'], ['Help', '#help'], ['Report a Problem', '#report'], ['Sign Out', '#signout']]],
];

function navItems(page: string) {
  return NAV.map((n) => `<a class="nav-item" href="${n.href}"${n.page === page ? ' aria-current="page"' : ''}>${icon(n.icon)}<span>${n.label}</span></a>`).join('');
}

function shell(page: string, main: string) {
  return `<div class="page-glow" aria-hidden="true"></div>
  <header class="app-header"><div class="header-inner">
    <button class="icon-btn nav-toggle" type="button" aria-label="Open menu" aria-haspopup="dialog" data-open-sheet="nav">${icon('menu')}</button>
    <a class="brand" href="/" aria-label="Media Manager home"><span class="brand-word">media<b>/</b>manager</span></a>
    <a class="search-pill" href="/search"${page === 'search' ? ' aria-current="page"' : ''}>${icon('search')}<span>Search</span></a>
    <span class="header-spacer"></span>
    <a class="icon-btn header-search-icon" href="/search" aria-label="Search">${icon('search')}</a>
    <a class="header-link media" href="/#recent">${icon('bars')}<span>My Media</span></a>
    <span class="header-divider" aria-hidden="true"></span>
    <a class="header-link wish" href="#wishlist">${icon('bookmark')}<span>My Wish List</span></a>
    <button class="account-btn" type="button" aria-label="Open User Menu" aria-haspopup="dialog" data-open-sheet="user"><span class="avatar" aria-hidden="true">V</span><span class="label" aria-hidden="true">viewer</span>${icon('account', 'acct-icon')}</button>
  </div></header>
  <aside class="side-nav"><nav aria-label="Main">${navItems(page)}</nav></aside>
  <main class="app-main" id="main">${main}</main>
  <footer class="app-footer"><div class="footer-inner">
    <div class="footer-brand"><a class="brand" href="/" aria-label="Media Manager home"><span class="brand-word">media<b>/</b>manager</span></a></div>
    <div class="footer-cols">${FOOTER.map(([h, links]) => `<div class="footer-col"><h3>${h}</h3>${links.map(([l, href]) => `<a href="${href}">${l}</a>`).join('')}</div>`).join('')}</div>
    <p class="footer-note">Media Manager · self-hosted catalog · look &amp; feel study · library snapshot Nov 7, 2026</p>
  </div></footer>
  <nav class="tabbar" aria-label="Tabs">${TABS.map((t) => `<a href="${t.href}"${t.page === page ? ' aria-current="page"' : ''}>${icon(t.icon)}<span>${t.label}</span></a>`).join('')}</nav>
  <dialog class="sheet" id="sheet-user" aria-label="User menu">
    <button class="icon-btn sheet-close" type="button" aria-label="Close">${icon('close')}</button>
    <div class="who"><span class="avatar">V</span><b>viewer</b><span>Viewer account</span></div>
    <a class="btn" href="#profile">${icon('person')}Profile</a>
    <p class="sheet-label">Links</p>
    <a class="sheet-link" href="/#recent">${icon('bars')}My Media</a>
    <a class="sheet-link" href="#wishlist">${icon('heart')}My Wish List</a>
    <a class="sheet-link" href="#help">${icon('help')}Help</a>
    <a class="sheet-link" href="#report">${icon('flag')}Report a Problem</a>
    <a class="sheet-link" href="#signout">${icon('logout')}Sign Out</a>
  </dialog>
  <dialog class="sheet left" id="sheet-nav" aria-label="Menu">
    <button class="icon-btn sheet-close" type="button" aria-label="Close">${icon('close')}</button>
    <h3>Media Manager</h3>
    ${navItems(page)}
  </dialog>`;
}

// Plex-style FAQ accordion, filled with Media Manager questions.
const FAQ: [string, string][] = [
  ['What is Media Manager?', 'A self-hosted catalog and streaming server for your physical and ripped collection: movies, TV, music, books, live TV and security cameras, all in one place.'],
  ['Where can I watch my library?', 'Right here in the browser, and in the Media Manager apps for iPhone and iPad, Android TV and Roku.'],
  ['Does it remember where I left off?', 'Yes. Playback, reading and listening positions are saved for each person, and show up in Continue Watching, Resume Reading and Continue Listening.'],
  ['Where do the posters and artwork come from?', 'Movie and TV artwork comes from TMDB, book covers from Open Library and album art from the Cover Art Archive.'],
  ['How do I ask for something new?', 'Add it to My Wish List. When it joins the library it appears under Recently Added.'],
  ['Can I watch live TV?', 'Yes. Channels from your HDHomeRun tuner are listed under Live TV.'],
];
function faq() {
  return `<section class="faq" aria-labelledby="faq-h"><h2 id="faq-h">Frequently Asked Questions</h2><ul class="faq-list">${FAQ.map(([q, a], i) => `
    <li class="faq-item"><button class="faq-q" type="button" aria-expanded="false" aria-controls="faq-${i}"><span>${esc(q)}</span>${icon('add')}</button>
    <div class="faq-a" id="faq-${i}" role="region" hidden>${esc(a)}</div></li>`).join('')}</ul></section>`;
}

// ------------------------------------------------------------------ pages
function homePage() {
  const recent = H.recently_added ?? [];
  const books = H.recently_added_books ?? [];
  const albums = H.recently_added_albums ?? [];
  const seasons = H.missing_seasons ?? [];

  const mosaicIds = [...recent, ...seasons, ...L.movies].map((x) => x.title_id).filter((id) => posterSet.has(id));
  const mosaic = [...new Set(mosaicIds)].slice(0, 28).map((id) => `<img src="${poster(id)}" alt="" decoding="async">`).join('');

  const chips = [
    ['Movies', 'movie', '/on-demand'], ['TV Shows', 'tv', '/on-demand#tv'], ['Books', 'books', '#books'], ['Music', 'album', '#albums'],
    ['Collections', 'collections', '#collections'], ['Family', 'family', '#family'], ['Live TV', 'livetv', '/live-tv'],
    ['Cameras', 'camera', '#cameras'], ['Playlists', 'playlist', '#playlists'], ['Tags', 'tag', '#tags'], ['My Wish List', 'heart', '#wishlist'],
  ].map(([l, i, h]) => `<a class="chip" href="${h}">${icon(i)}${l}</a>`);

  // Artists: one card per distinct artist on recently added albums.
  const artistMap = new Map<number, { name: string; albums: number; tracks: number }>();
  for (const a of albums) for (const ar of T(a.title_id).artists ?? []) {
    const cur = artistMap.get(ar.id) ?? { name: ar.name, albums: 0, tracks: 0 };
    cur.albums++; cur.tracks += a.track_count ?? 0; artistMap.set(ar.id, cur);
  }
  const artists = [...artistMap.entries()].map(([id, a]) => `<a class="person" href="#artist-${id}">
      <div class="person-img">${headshotSet.has(id) ? `<img src="/media/a${id}.jpg" alt="" loading="lazy">` : `<span class="initial" style="background:hsl(${hue(a.name)} 35% 28%)">${esc(a.name.replace(/^\W+/, '')[0])}</span>`}</div>
      <div class="person-info"><span class="n">${esc(a.name)}</span><span class="m">${a.albums} ${a.albums === 1 ? 'Album' : 'Albums'}</span><span class="m">${a.tracks} Tracks</span>
      <span class="btn ghost">${icon('shuffle')}Shuffle</span></div></a>`);

  const counts = { movies: L.movies.length, shows: L.tv.length };

  return `
  <section class="welcome" aria-labelledby="welcome-h">
    <div class="welcome-card">
      <div class="mosaic" aria-hidden="true"><div class="mosaic-grid">${mosaic}</div></div>
      <div class="welcome-text">
        <h1 id="welcome-h">Welcome back, viewer!</h1>
        <p>Pick up where you left off, or explore what's new on your shelves: movies, TV, books and music from your own collection.</p>
      </div>
    </div>
  </section>

  <section aria-label="Featured">${heroCarousel([
    { id: 662, kicker: 'Just added to your library' },
    { id: 1208, kicker: 'Just added to your library' },
    { id: 678, kicker: 'Just added to your library' },
    { id: 1207, kicker: 'Just added to your library' },
  ])}</section>

  <section aria-label="Browse your library">${sectionHead('Browse Your Library', { tag: 'Library', href: '/on-demand' })}${row(chips, 'chips', 'Browse')}</section>

  <section>${sectionHead('Continue Watching', { href: '#continue' })}${row(
    SAMPLE_WATCHING.map((w) => wideCard(w.id, T(w.id).title_name, fmtLeft(w.min), wideArt(w.id, T(w.id).title_name, progress(w.pct)))),
    '', 'Continue Watching')}</section>

  <section id="recent">${sectionHead('Recently Added', { href: '/on-demand', id: 'recently-added' })}${row(
    recent.map((r) => posterCard(r.title_id, r.title_name, { m: [r.release_year, T(r.title_id).media_type === 'TV' ? `${T(r.title_id).season_count ?? ''} Seasons` : 'Movie'].filter(Boolean).join(' • ') })),
    '', 'Recently Added')}</section>

  <section aria-label="Spotlight">${hero(1142, 'New season available: Season 3 isn\'t on your shelf yet', { full: true, action: 'Play Season 1' })}</section>

  <section>${sectionHead('Continue Listening', { tag: 'Music', href: '#albums' })}${row(
    SAMPLE_LISTENING.map((l) => posterCard(l.id, T(l.id).title_name, { t: l.track, m: l.artist }, progress(l.pct), true)),
    '', 'Continue Listening')}</section>

  <section id="reading">${sectionHead('Resume Reading', { tag: 'Books', href: '#books' })}${row(
    SAMPLE_READING.map((r) => { const t = T(r.id); return posterCard(r.id, t.title_name, { t: t.title_name, m: `${Math.round(r.pct * 100)}% read`, accent: r.pct > 0 }, r.pct > 0 ? progress(r.pct) : ''); }),
    '', 'Resume Reading')}</section>

  <section id="books">${sectionHead('Recently Added Books', { href: '#books' })}${row(
    books.map((b) => posterCard(b.title_id, b.title_name, { t: b.title_name, m: b.author_name })), '', 'Recently Added Books')}</section>

  <section>${sectionHead('Artists in Your Library', { sub: 'From the albums you added most recently.' })}${row(artists, '', 'Artists')}</section>

  <section id="albums">${sectionHead('Recently Added Albums', { href: '#albums' })}${row(
    albums.map((a) => posterCard(a.title_id, a.title_name, { t: a.title_name, m: a.artist_name }, '', true)), '', 'Recently Added Albums')}</section>

  <section>${sectionHead('New Seasons Available', { tag: 'TV', href: '/on-demand#tv', sub: 'Shows on your shelf with newer seasons out that you don\'t own yet.' })}${row(
    seasons.map((s) => { const ss = (s.missing_seasons ?? []).map((x) => `S${x.season_number}`).join(', ');
      return wideCard(s.title_id, s.title_name, `Missing ${ss}`, wideArt(s.title_id, s.title_name, `<span class="badge amber">New ${esc(ss.split(', ')[0])}</span>`)); }),
    '', 'New Seasons Available')}</section>

  <section>${sectionHead('On Live TV', { href: '/live-tv' })}${row(
    L.channels.slice(0, 10).map((c, i) => `<a class="wide" href="/live-tv"><div class="art"><div class="channel-tile" style="background:linear-gradient(135deg,${['#1b90dc', '#823165', '#45934b', '#f37b38', '#f74366'][i % 5]},#282a2d)">${esc(c.guide_name)}<small>CH ${esc(c.guide_number)}</small></div><span class="badge">Live</span></div>
      <div class="caption"><span class="t">${esc(c.guide_name)}${c.network_affiliation ? ` • ${esc(c.network_affiliation)}` : ''}</span><span class="m">Channel ${esc(c.guide_number)}</span></div></a>`),
    '', 'On Live TV')}</section>

  <p class="empty inset-r">Your library: ${counts.movies}+ movies, ${counts.shows}+ shows, ${books.length}+ books and ${albums.length}+ albums shown here.</p>
  ${faq()}`;
}

function onDemandPage() {
  const tiles = [
    ['Movies', '#1b90dc', '#0e5f99', '#movies'], ['TV Shows', '#45934b', '#2a6a30', '#tv'], ['Books', '#823165', '#561d42', '/#books'],
    ['Music', '#3f4245', '#282a2d', '/#albums'], ['Collections', '#f37b38', '#c4561c', '#collections'], ['Family', '#868c96', '#575b61', '#family'],
    ['Home Videos', '#f74366', '#b92844', '#personal'],
  ].map(([l, a, b, h]) => `<a class="cat-tile" href="${h}" style="background:linear-gradient(135deg,${a},${b})"><span>${l}</span></a>`);
  return `
  <section class="welcome on-demand-head"><div class="welcome-text" style="padding-top:32px">
    <h1>Movies &amp; TV Shows</h1>
    <p>Everything on your shelves, ready to play. Browse what's new, jump back into a series, or pick something at random.</p></div></section>
  <section aria-label="Featured">${heroCarousel([
    { id: 678, kicker: 'New in your library' }, { id: 1208, kicker: 'New in your library' }, { id: 289, kicker: 'From your TV shelf', action: 'Play S1 • E1' }, { id: 42, kicker: 'From your TV shelf', action: 'Play S1 • E1' },
  ])}</section>
  <section>${sectionHead('Categories', { href: '#categories' })}${row(tiles, '', 'Categories')}</section>
  <section id="movies">${sectionHead('Movies', { tag: 'Library', href: '#movies' })}${row(L.movies.map((m) => posterCard(m.title_id, m.title_name, { m: [m.content_rating, m.release_year].filter(Boolean).join(' • ') })), '', 'Movies')}</section>
  <section id="tv">${sectionHead('TV Shows', { tag: 'Library', href: '#tv' })}${row(L.tv.map((m) => posterCard(m.title_id, m.title_name, { m: [m.content_rating, m.release_year].filter(Boolean).join(' • ') })), '', 'TV Shows')}</section>
  <section>${sectionHead('Recently Added')}${row((H.recently_added ?? []).map((r) => posterCard(r.title_id, r.title_name, { m: String(r.release_year ?? '') })), '', 'Recently Added')}</section>
  ${faq()}`;
}

function allTitles() {
  const seen = new Map<number, FeedItem & { kind: string }>();
  const add = (x: FeedItem, kind: string) => { if (!seen.has(x.title_id)) seen.set(x.title_id, { ...x, kind }); };
  (H.recently_added ?? []).forEach((x) => add(x, typeLabel(T(x.title_id).media_type) || 'Movie'));
  (H.recently_added_books ?? []).forEach((x) => add(x, 'Book'));
  (H.recently_added_albums ?? []).forEach((x) => add(x, 'Album'));
  L.movies.forEach((x) => add(x, 'Movie'));
  L.tv.forEach((x) => add(x, 'TV Series'));
  return [...seen.values()];
}

function searchPage() {
  return `
  <section class="search-wrap"><label class="search-box">${icon('search')}<input id="q" type="search" placeholder="Search movies, shows, books, music" aria-label="Search your library" autocomplete="off"></label></section>
  <section>${sectionHead('Recently Added', { id: 'results-h' })}<div class="grid" id="results"></div></section>`;
}

function renderResults(q: string) {
  const el = document.getElementById('results');
  const h = document.getElementById('results-h');
  if (!el || !h) return;
  const all = allTitles();
  const list = q ? all.filter((x) => `${x.title_name} ${x.author_name ?? ''} ${x.artist_name ?? ''}`.toLowerCase().includes(q.toLowerCase())) : all.slice(0, 30);
  h.textContent = q ? `Results for “${q}”` : 'Recently Added';
  el.innerHTML = list.length
    ? list.map((x) => posterCard(x.title_id, x.title_name, { m: x.author_name ?? x.artist_name ?? `${x.kind}${x.release_year ? ' • ' + x.release_year : ''}` })).join('')
    : `<p class="empty">Nothing in your library matches “${esc(q)}”.</p>`;
}

function liveTvPage() {
  const ch = L.channels;
  const first = ch[0];
  const groups = ['All Channels', ...new Set(ch.map((c) => c.network_affiliation).filter(Boolean) as string[]), 'Independent'];
  return `
  <section class="live-top">
    <div class="live-player"><div class="channel-tile" style="background:linear-gradient(135deg,#0e1c3a,#23125a 60%,#4b0f45)">${esc(first.guide_name)}<small>CH ${esc(first.guide_number)} · ${esc(first.network_affiliation ?? '')}</small></div><span class="badge">Live</span></div>
    <div class="live-info"><h1>${esc(first.guide_name)}</h1><h2>${esc(first.network_affiliation ?? 'Over-the-air')}</h2>
      <div class="meta"><span class="rating">HD</span><span>Channel ${esc(first.guide_number)}</span><span>Reception ★★★★</span></div>
      <div class="hero-actions"><a class="btn" href="#watch">${icon('play')}Watch Live</a><a class="btn ghost" href="#fav">${icon('heart')}Add to Favorites</a></div>
      <p>Live over-the-air TV from your HDHomeRun tuner. Programme guide data isn't part of this snapshot.</p></div>
  </section>
  <section>${row(groups.map((g, i) => `<button class="chip" type="button" aria-pressed="${i === 0}">${esc(g)}</button>`), 'chips guide-tabs', 'Channel groups')}</section>
  <section class="guide" aria-label="Channel guide">
    <div class="guide-times"><span class="today">Tonight</span><span>Now</span><span>Next</span></div>
    ${ch.map((c, i) => `<div class="guide-row"><div class="guide-ch${i === 0 ? ' on' : ''}">${esc(c.guide_name)}<small>${esc(c.guide_number)}</small></div>
      <a class="guide-prog" href="#ch-${c.id}" style="--elapsed:${20 + ((i * 37) % 60)}%"><span class="t">${esc(c.guide_name)}${c.network_affiliation ? ` · ${esc(c.network_affiliation)}` : ''}</span><span class="m">Live now · Channel ${esc(c.guide_number)}</span></a></div>`).join('')}
  </section>`;
}

function trendingPage() {
  const seasons = H.missing_seasons ?? [];
  return `
  <section aria-label="Featured">${hero(639, 'Your shelf is missing Seasons 2 and 3', { full: true, action: 'Play Season 1' })}</section>
  <section class="promo"><h1>Find Something New To Watch</h1>
    <p>Discover suggestions based on what's already in your collection: new seasons of shows you own, more from authors and artists you love, and titles from your wish list.</p>
    <a class="btn" href="#wishlist">Open My Wish List</a></section>
  <section>${sectionHead('New Seasons Out', { href: '#seasons' })}${row(seasons.map((s) => wideCard(s.title_id, s.title_name, `Season ${(s.missing_seasons ?? []).map((x) => x.season_number).join(', ')} available`, wideArt(s.title_id, s.title_name), true)), '', 'New Seasons Out')}</section>
  <section>${sectionHead('More From Authors You Read', { tag: 'Books' })}${row((H.recently_added_books ?? []).map((b) => posterCard(b.title_id, b.title_name, { t: b.author_name, m: String(b.release_year ?? '') })), '', 'More From Authors You Read')}</section>
  <section>${hero(502, 'Season 3 and Season 4 are out now', { full: true, action: 'Play Season 1' })}</section>
  <section>${sectionHead('Popular In Your Library', { href: '/on-demand' })}${row(L.movies.slice(0, 18).map((m) => posterCard(m.title_id, m.title_name)), '', 'Popular In Your Library')}</section>`;
}

// ------------------------------------------------------------ behaviour
function wireRows(root: ParentNode) {
  root.querySelectorAll<HTMLElement>('.row-wrap').forEach((wrap) => {
    const r = wrap.querySelector<HTMLElement>('.row')!;
    const prev = wrap.querySelector<HTMLButtonElement>('.row-arrow.prev')!;
    const next = wrap.querySelector<HTMLButtonElement>('.row-arrow.next')!;
    const sync = () => {
      prev.hidden = r.scrollLeft < 4;
      next.hidden = r.scrollLeft + r.clientWidth >= r.scrollWidth - 4;
      const dots = wrap.parentElement?.querySelectorAll<HTMLButtonElement>('.hero-dots button');
      if (dots?.length) {
        const w = (r.firstElementChild as HTMLElement)?.offsetWidth || 1;
        const i = Math.round(r.scrollLeft / (w + 16));
        dots.forEach((d, j) => d.setAttribute('aria-current', String(j === i)));
      }
    };
    const page = (dir: number) => r.scrollBy({ left: dir * r.clientWidth * 0.85, behavior: 'smooth' });
    prev.addEventListener('click', () => page(-1));
    next.addEventListener('click', () => page(1));
    r.addEventListener('scroll', sync, { passive: true });
    wrap.parentElement?.querySelectorAll<HTMLButtonElement>('.hero-dots button').forEach((d, i) => d.addEventListener('click', () => {
      const li = r.children[i] as HTMLElement; r.scrollTo({ left: li.offsetLeft - r.offsetLeft, behavior: 'smooth' });
    }));
    requestAnimationFrame(sync);
  });
}

// Side sheets are native <dialog>s: showModal() gives focus trapping, Esc and a ::backdrop.
function wireSheets() {
  document.querySelectorAll<HTMLElement>('[data-open-sheet]').forEach((b) => b.addEventListener('click', () => {
    const d = document.getElementById(`sheet-${b.dataset.openSheet}`) as HTMLDialogElement;
    d.showModal();
    b.setAttribute('aria-expanded', 'true');
    d.addEventListener('close', () => b.setAttribute('aria-expanded', 'false'), { once: true });
  }));
  document.querySelectorAll<HTMLDialogElement>('dialog.sheet').forEach((d) => {
    d.querySelector('.sheet-close')?.addEventListener('click', () => d.close());
    d.querySelectorAll('a').forEach((a) => a.addEventListener('click', () => d.close()));
    // click on the backdrop (outside the panel) closes
    d.addEventListener('click', (e) => {
      const r = d.getBoundingClientRect();
      if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) d.close();
    });
  });
}

function wireFaq() {
  document.querySelectorAll<HTMLButtonElement>('.faq-q').forEach((q) => q.addEventListener('click', () => {
    const open = q.getAttribute('aria-expanded') !== 'true';
    q.setAttribute('aria-expanded', String(open));
    q.innerHTML = q.innerHTML.replace(/<svg[\s\S]*<\/svg>/, icon(open ? 'remove' : 'add'));
    document.getElementById(q.getAttribute('aria-controls')!)!.hidden = !open;
  }));
}

function wireHeader() {
  const h = document.querySelector('.app-header')!;
  const on = () => h.classList.toggle('is-scrolled', window.scrollY > 8);
  window.addEventListener('scroll', on, { passive: true }); on();
}

const PAGES: Record<string, () => string> = {
  home: homePage, search: searchPage, 'live-tv': liveTvPage, 'on-demand': onDemandPage, trending: trendingPage,
};
const page = document.body.dataset.page ?? 'home';
document.getElementById('app')!.innerHTML = shell(page, (PAGES[page] ?? homePage)());
wireRows(document);
wireSheets();
wireHeader();
wireFaq();

if (page === 'search') {
  const q = document.getElementById('q') as HTMLInputElement;
  renderResults('');
  q.addEventListener('input', () => renderResults(q.value.trim()));
}
if (page === 'live-tv') {
  document.querySelectorAll<HTMLButtonElement>('.guide-tabs .chip').forEach((b, _, all) => b.addEventListener('click', () => {
    all.forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
    const g = b.textContent ?? '';
    document.querySelectorAll<HTMLElement>('.guide-row').forEach((r, i) => {
      const c = L.channels[i];
      r.hidden = !(g === 'All Channels' || (g === 'Independent' ? !c.network_affiliation : c.network_affiliation === g));
    });
  }));
}
