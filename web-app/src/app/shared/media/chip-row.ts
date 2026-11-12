import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MediaRowComponent } from './media-row';
import { CardLink } from './poster-card';

export interface ChipLink {
  readonly label: string;
  readonly link: CardLink;
  /** Material icon name. */
  readonly icon?: string;
}

/** A scrolling row of 40px link chips (spec 3.5). */
@Component({
  selector: 'app-chip-row',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatIconModule, MediaRowComponent],
  template: `
    <app-media-row class="chips" [label]="label()">
      @for (c of chips(); track c.label) {
        <li>
          <a class="chip" [routerLink]="c.link">
            @if (c.icon) { <mat-icon aria-hidden="true">{{ c.icon }}</mat-icon> }
            {{ c.label }}
          </a>
        </li>
      }
    </app-media-row>
  `,
  styles: `
    :host { display: block; }
    .chips { --row-gap: 16px; }
    .chip {
      display: inline-flex; align-items: center; gap: 8px; height: 40px; padding: 0 16px;
      border-radius: var(--r-pill, 9999px);
      background: var(--c-surface, #ffffff1a);
      color: var(--c-text, var(--mat-sys-on-surface));
      font: 600 14px/1 var(--f-body, inherit);
      text-decoration: none; white-space: nowrap;
    }
    .chip:hover { background: #ffffff33; }
    .chip:focus-visible { outline: 2px solid var(--c-accent, var(--mat-sys-primary)); outline-offset: 2px; }
    .chip mat-icon { width: 16px; height: 16px; font-size: 16px; color: var(--c-text-3, var(--mat-sys-on-surface-variant)); }
  `,
})
export class ChipRowComponent {
  readonly label = input.required<string>();
  readonly chips = input.required<readonly ChipLink[]>();
}
