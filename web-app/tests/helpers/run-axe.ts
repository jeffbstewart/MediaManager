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
  for (const scheme of SCHEMES) {
    await page.emulateMedia({ colorScheme: scheme });
    for (const vp of VIEWPORTS) {
      await page.setViewportSize(vp.size);
      // Give Material a frame to react to the viewport / scheme change.
      await page.waitForLoadState('networkidle');

      let builder = new AxeBuilder({ page });
      if (opts.include) builder = builder.include(opts.include as string);
      if (opts.disabledRules?.length) builder = builder.disableRules(opts.disabledRules);

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
