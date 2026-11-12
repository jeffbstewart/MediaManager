import { ChangeDetectionStrategy, Component, ViewEncapsulation, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { RowScrollDirective } from './row-scroll';

/**
 * Horizontal scroll-snap row (spec 3.1). Project `<li>` items:
 *
 *     <app-media-row label="Recently Added">
 *       @for (t of titles; track t.id) { <li><app-poster-card ... /></li> }
 *     </app-media-row>
 *
 * The row bleeds past the right edge of its page by `--row-bleed` so
 * the next card peeks out; a page whose content is inset by the
 * `--pad-x` layout token sets `--row-bleed: var(--pad-x)`. Arrows show
 * on hover for pointer devices at 1024px and up; elsewhere the row
 * scrolls by touch, wheel or keyboard (the cards are focusable).
 *
 * Encapsulation is off because the `<li>` children belong to the
 * parent's view; every selector is scoped under `app-media-row`.
 */
@Component({
  selector: 'app-media-row',
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  imports: [MatIconModule, RowScrollDirective],
  template: `
    <ul class="row carousel-row" appRowScroll #row="rowScroll" [attr.aria-label]="label()">
      <ng-content />
    </ul>
    <button class="row-arrow prev" type="button" aria-label="Scroll left"
            [hidden]="row.atStart()" (click)="row.page(-1)">
      <mat-icon>chevron_left</mat-icon>
    </button>
    <button class="row-arrow next" type="button" aria-label="Scroll right"
            [hidden]="row.atEnd()" (click)="row.page(1)">
      <mat-icon>chevron_right</mat-icon>
    </button>
  `,
  styles: `
    app-media-row {
      position: relative;
      display: block;
      min-width: 0;
    }
    app-media-row > .row {
      display: flex;
      gap: var(--row-gap);
      margin: 0 calc(-1 * var(--row-bleed, 0px)) 0 0;
      padding: 0 var(--row-bleed, 0px) 4px 0;
      list-style: none;
      overflow-x: auto;
      overscroll-behavior-x: contain;
      scroll-snap-type: x mandatory;
      scrollbar-width: none;
    }
    app-media-row > .row::-webkit-scrollbar { display: none; }
    app-media-row > .row > li {
      flex: none;
      scroll-snap-align: start;
    }
    app-media-row > .row-arrow {
      position: absolute;
      z-index: 3;
      top: 0;
      bottom: 0;
      width: max(var(--row-bleed, 0px), 48px);
      display: grid;
      place-items: center;
      padding: 0;
      border: 0;
      color: #fff;
      cursor: pointer;
      opacity: 0;
      transition: opacity 0.15s;
    }
    app-media-row > .row-arrow[hidden] { display: none; }
    app-media-row > .row-arrow.prev {
      left: calc(-1 * var(--row-bleed, 0px));
      background: linear-gradient(90deg, color-mix(in srgb, var(--mat-sys-surface) 90%, transparent), transparent);
    }
    app-media-row > .row-arrow.next {
      right: calc(-1 * var(--row-bleed, 0px));
      background: linear-gradient(270deg, color-mix(in srgb, var(--mat-sys-surface) 90%, transparent), transparent);
    }
    app-media-row > .row-arrow mat-icon { width: 28px; height: 28px; font-size: 28px; }
    app-media-row:hover > .row-arrow,
    app-media-row > .row-arrow:focus-visible { opacity: 1; }
    @media (hover: none), (max-width: 1023px) {
      app-media-row > .row-arrow { display: none; }
    }
    @media (prefers-reduced-motion: reduce) {
      app-media-row > .row-arrow { transition: none; }
    }
  `,
})
export class MediaRowComponent {
  /** Accessible name for the list; usually the section title. */
  readonly label = input.required<string>();
}
