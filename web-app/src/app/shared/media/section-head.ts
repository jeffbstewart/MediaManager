import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { CardLink } from './poster-card';

/**
 * Section header for a row (spec 3.1): an h2, an optional black tag
 * with an accent chevron, an optional "see all" link around the title,
 * and an optional one-line description below.
 */
@Component({
  selector: 'app-section-head',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatIconModule],
  template: `
    <div class="section-head">
      @if (tag(); as t) {
        <span class="tag"><mat-icon aria-hidden="true">chevron_right</mat-icon>{{ t }}</span>
      }
      @if (link(); as l) {
        <a class="see-all" [routerLink]="l">
          <h2 [attr.id]="headingId()">{{ heading() }}</h2>
          <mat-icon aria-hidden="true">chevron_right</mat-icon>
        </a>
      } @else {
        <h2 [attr.id]="headingId()">{{ heading() }}</h2>
      }
    </div>
    @if (sub(); as s) {
      <p class="section-sub">{{ s }}</p>
    }
  `,
  styles: `
    :host { display: block; }
    .section-head {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 12px;
    }
    h2 {
      margin: 0;
      font: 700 var(--fs-h2, 1.25rem)/27px var(--f-display, inherit);
      color: var(--c-text, var(--mat-sys-on-surface));
    }
    .see-all {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      color: var(--c-text, var(--mat-sys-on-surface));
      text-decoration: none;
      &:hover h2 { text-decoration: underline; }
      &:focus-visible {
        outline: 2px solid var(--c-accent, var(--mat-sys-primary));
        outline-offset: 2px;
        border-radius: var(--r-badge, 4px);
      }
    }
    .see-all mat-icon { width: 21px; height: 21px; font-size: 21px; }
    .section-sub {
      max-width: 600px;
      margin: -6px 0 12px;
      font-size: 14px;
      line-height: 18px;
      color: var(--c-text-2, var(--mat-sys-on-surface-variant));
    }
    .tag {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      height: 32px;
      padding: 0 8px;
      border-radius: var(--r-badge, 4px);
      background: #000;
      color: var(--c-text-2, #ffffffcc);
      font: 600 12px/1 var(--f-body, inherit);
    }
    .tag mat-icon {
      width: 12px;
      height: 12px;
      font-size: 12px;
      color: var(--c-accent, var(--mat-sys-primary));
    }
  `,
})
export class SectionHeadComponent {
  readonly heading = input.required<string>();
  readonly tag = input<string | null>(null);
  readonly link = input<CardLink | null>(null);
  readonly sub = input<string | null>(null);
  readonly headingId = input<string | null>(null);
}
