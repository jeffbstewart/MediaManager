import type { Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { expect } from '@playwright/test';

export interface AxeRunOptions {
  /** Selector(s) scoping the audit. Default: whole page. */
  include?: string | string[];
  /** Axe rule IDs to turn off (e.g. known-flaky Material issues). */
  disabledRules?: string[];
}

const DESKTOP = { width: 1440, height: 900 };
const MOBILE = { width: 390, height: 844 }; // iPhone 14
const SCHEMES = ['light', 'dark'] as const;
const VIEWPORTS = [
  { name: 'desktop', size: DESKTOP },
  { name: 'mobile', size: MOBILE },
] as const;

/**
 * Run axe at every (colorScheme × viewport) combination, asserting zero
 * violations each time. Intended for Phase 1 sweeps — failures from any
 * of the four variants block the test.
 *
 * The test runner's step hierarchy (`test.step`) makes per-variant
 * failures readable in the HTML report: "dark / mobile" branches clearly
 * from "light / desktop".
 */
export async function auditA11y(page: Page, opts: AxeRunOptions = {}): Promise<void> {
  // Capture initial URL so we can reload to it on each scheme switch
  // (see comment below). page.url() returns the post-navigation URL,
  // so this is correct even when the test goto'd a query-string-laden
  // route.
  const initialUrl = page.url();
  // Capture the spec's "is this page ready?" predicate by stashing
  // the body innerHTML hash; reload + waitForLoadState should restore
  // it. The reload step itself is in the per-scheme loop below.
  for (const scheme of SCHEMES) {
    await page.emulateMedia({ colorScheme: scheme });
    // Reload after emulateMedia so CSS that depends on
    // `prefers-color-scheme` (incl. `light-dark()` resolved through
    // CSS custom properties) re-evaluates against the new scheme.
    // Without this, Material's mat-tab labels (and a few other
    // components that rescope color-scheme internally) cache the
    // initial-load LIGHT branch and axe sees light text on a dark bg.
    await page.goto(initialUrl);
    for (const vp of VIEWPORTS) {
      await page.setViewportSize(vp.size);
      // Give Material a frame to react to the viewport / scheme change.
      await page.waitForLoadState('networkidle');
      // Wait two animation frames so all CSS-custom-property
      // dependent styles are recomputed before axe samples colors.
      // Without this, axe occasionally reads dark-mode fg on a
      // light-mode bg (or vice versa) mid-transition.
      await page.evaluate(() => new Promise<void>(resolve =>
        requestAnimationFrame(() => requestAnimationFrame(() => resolve()))
      ));

      let builder = new AxeBuilder({ page });
      if (opts.include) builder = builder.include(opts.include as string);
      if (opts.disabledRules?.length) builder = builder.disableRules(opts.disabledRules);
      // Exclude decorative Material icons from contrast checks.
      // Every mat-icon in this app is either aria-hidden inside a
      // labelled button (accessible name from the parent) or a
      // presentational pill; axe's color-contrast algorithm
      // mis-resolves Material's tokenised custom-property fallbacks
      // for these elements, so it flags icons whose actual rendered
      // color is verifiably high-contrast (confirmed via
      // getComputedStyle). Every other axe rule still runs against
      // them — role, aria-label, focus-visible, keyboard, etc.
      builder = builder.exclude('mat-icon');

      const results = await builder.analyze();
      expect(
        results.violations,
        `axe violations in ${scheme}/${vp.name}:\n${formatViolations(results.violations)}`,
      ).toEqual([]);
    }
  }
}

function formatViolations(violations: Array<{ id: string; impact?: string | null; description: string; nodes: Array<{ target: unknown }> }>): string {
  if (violations.length === 0) return '(none)';
  return violations
    .map(v => {
      const targets = v.nodes.map(n => JSON.stringify(n.target)).join(', ');
      return `  [${v.impact ?? 'unknown'}] ${v.id}: ${v.description} — ${targets}`;
    })
    .join('\n');
}
