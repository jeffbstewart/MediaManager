/**
 * Stable hue (0 to 359) for a name, so a title without artwork gets the
 * same placeholder colors on every render. Ported from the prototype's
 * `hue()` helper (docs/plans/marquee-reference/main.ts).
 */
export function artHue(name: string): number {
  let h = 7;
  for (const c of name) {
    h = (h * 31 + c.charCodeAt(0)) % 360;
  }
  return h;
}

/** First visible character of a name, upper-cased, or "?" when empty. */
export function artInitial(name: string): string {
  const first = name.trim()[0];
  return first ? first.toUpperCase() : '?';
}
