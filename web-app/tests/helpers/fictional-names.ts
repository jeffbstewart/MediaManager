/**
 * Invented stand-ins for the real titles, people, and works named in the
 * test fixtures, for screenshots that are published (docs/images/
 * screenshots, the README). The fixtures keep their real names so the
 * suites read naturally; `fictionalize` swaps them at the mock boundary
 * when MM_FICTIONAL_NAMES=1, which only tests/visual/docs.mjs sets.
 *
 * Every entry is matched as a whole string, so "Dune" and "Dune Messiah"
 * are separate rows and short names cannot rewrite parts of other words.
 * Add a row when a fixture gains a real-world name.
 */
export const FICTIONAL_NAMES: Readonly<Record<string, string>> = {
  // Film and TV
  'The Matrix': 'Harbor Lights',
  'The Matrix Reloaded': 'Harbor Lights: Undertow',
  'The Matrix Revolutions': 'Harbor Lights: Tides',
  'The Matrix Resurrections': 'Harbor Lights: Return',
  'The Matrix Collection': 'Harbor Lights Collection',
  'matrix.mkv': 'harbor-lights.mkv',
  'Breaking Bad': 'Northfield',
  'Breaking Bad — Season 1': 'Northfield — Season 1',
  'bb_s01e01.mkv': 'northfield_s01e01.mkv',
  "Cat's in the Bag…": 'Second Act',
  'Interstellar': 'Far Orbit',
  'Inception': 'Second Sleep',
  'Blade Runner 2049': 'Glass City',
  'John Wick': 'Quiet Hours',
  'The Dark Knight Trilogy': 'Night Watch Trilogy',
  'Star Wars': 'Starfall',
  'The Expanse': 'Outer Reach',
  'Severance': 'Split Shift',
  'Lord of the Rings Trilogy': 'Lantern Road Trilogy',
  'The Fellowship of the Ring': 'Lantern Road',
  'The Two Towers': 'Lantern Road: Two Towns',
  'Ratatouille': 'Little Kitchen',
  'The Shawshank Redemption': 'Stone Harbor',
  // People
  'Keanu Reeves': 'Daniel Okafor',
  'Laurence Fishburne': 'Marcus Hale',
  'Carrie-Anne Moss': 'Lena Marsh',
  'Neo': 'Cole',
  'Bryan Cranston': 'Peter Vance',
  'Aaron Paul': 'Jesse Kline',
  'Alice Stewart': 'Alice Rowan',
  'Bob Stewart': 'Bob Rowan',
  'Charlie Stewart': 'Charlie Rowan',
  // Music
  'Miles Davis': 'Theo Marlowe',
  'Bill Evans': 'Owen Reyes',
  'Thelonious Monk': 'Ellis Grant',
  'Radiohead': 'Paper Lanterns',
  'Björk': 'Vela',
  'Kind of Blue': 'Blue Hours',
  'Bitches Brew': 'Night Market',
  'So What': 'Open Door',
  'Freddie Freeloader': 'Side Street',
  'Seven Thirty-Seven': 'Seven Thirty',
  'Columbia': 'Meridian Records',
  'Seminal modal jazz album.': 'A late-night session in six takes.',
  // Books
  'Frank Herbert': 'R. L. Castellan',
  'Dune': 'Saltmarsh',
  'Dune Messiah': 'Saltmarsh Rising',
  'Ursula K. Le Guin': 'M. Hollis Vane',
  'The Left Hand of Darkness': 'The Long Winter',
  'Isaac Asimov': 'A. J. Pembroke',
  // Descriptions that paraphrase a real work
  'A chemistry teacher turns to manufacturing methamphetamine.':
    'A quiet town, a family business, and a secret nobody can keep.',
  'Set on the desert planet Arrakis, follows young Paul Atreides.':
    "A coastal family's heir inherits the marsh and what lies beneath it.",
  'A team of explorers travel through a wormhole in space.':
    'A crew leaves the solar system and finds the way home has moved.',
  'Morpheus': 'Rook',
  'Trinity': 'Wren',
  'Walter White': 'Henry Vale',
  'Jesse Pinkman': 'Danny Reese',
  'A computer hacker learns from mysterious rebels about the true nature of his reality and his role in the war against its controllers.':
    'A night-shift programmer is told the city she knows is a story someone else is telling, and goes looking for the author.',
  'A high school chemistry teacher turned methamphetamine manufacturer.':
    'A small-town pharmacist keeps the family store open by means the town would not approve of.',
  'A thief who steals corporate secrets through dream-sharing technology is given the inverse task of planting an idea.':
    'A sleep researcher is hired to make one patient forget a single afternoon, and finds it was never his to take.',
  "Frank Herbert's desert-planet saga.": 'The salt marsh saga, first volume.',
  // Broadcast call signs
  'WTTW HD': 'Channel 11 HD',
  'WLS HD': 'Channel 7 HD',
  'WGN HD': 'Channel 9 HD',
  'PBS': 'Public',
  'ABC': 'Metro',
};

/** True when the mock backend should serve invented names (set by tests/visual/docs.mjs). */
export const fictionalNamesEnabled = process.env['MM_FICTIONAL_NAMES'] === '1';

/**
 * Deep-copies `value`, replacing every string that is a key of
 * FICTIONAL_NAMES with its stand-in. Arrays and plain objects are
 * walked; protobuf-es messages are plain objects, so a fixture built
 * with `create()` comes back as the same shape with new strings.
 * Returns the input untouched when fictional names are off.
 */
export function fictionalize<T>(value: T): T {
  if (!fictionalNamesEnabled) return value;
  return walk(value) as T;
}

function walk(v: unknown): unknown {
  if (typeof v === 'string') return FICTIONAL_NAMES[v] ?? v;
  if (Array.isArray(v)) return v.map(walk);
  if (v && typeof v === 'object') {
    const out: Record<string, unknown> = {};
    for (const [k, x] of Object.entries(v as Record<string, unknown>)) out[k] = walk(x);
    return out;
  }
  return v;
}
