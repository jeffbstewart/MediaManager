import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { BuildInfoService } from './build-info.service';

/**
 * The running build's label, shown under the title of the help page.
 * It used to sit in the lower-right corner of every page and fade out
 * after a few seconds; the fade made axe sample half-faded text on slow
 * pages, so the label now lives on one page, in normal flow, with no
 * animation. The help page is where a viewer goes to report a problem,
 * which is when the label matters.
 */
@Component({
  selector: 'app-build-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (buildInfo.label(); as label) {
      <p class="build-line">Build <span class="build-badge">{{ label }}</span></p>
    }
  `,
  styles: `
    .build-line {
      margin: 0 0 1rem;
      color: var(--mat-sys-on-surface-variant);
      font: 500 13px/20px var(--f-body, Inter, sans-serif);
    }

    .build-badge {
      display: inline-block;
      padding: 0 8px;
      border-radius: var(--r-badge, 4px);
      background: var(--mat-sys-surface-container);
      color: var(--mat-sys-on-surface);
      font: 600 11px/20px var(--f-body, Inter, sans-serif);
      letter-spacing: 0.02em;
      white-space: nowrap;
      user-select: all;
    }
  `,
})
export class BuildBadgeComponent {
  readonly buildInfo = inject(BuildInfoService);

  constructor() {
    void this.buildInfo.load();
  }
}
