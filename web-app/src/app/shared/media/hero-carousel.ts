import { ChangeDetectionStrategy, Component, ViewEncapsulation, computed, input } from '@angular/core';
import { RowScrollDirective } from './row-scroll';

/**
 * Featured carousel (spec 3.4): project one `<li>` per
 * `app-hero-banner`. About 66px of the next banner peeks out, and the
 * pill dots under the row follow the scroll and jump to a slide.
 * Encapsulation is off for the projected `<li>`s; selectors are scoped
 * under `app-hero-carousel`.
 */
@Component({
  selector: 'app-hero-carousel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  imports: [RowScrollDirective],
  template: `
    <ul class="hero-row" appRowScroll #row="rowScroll" [attr.aria-label]="label()">
      <ng-content />
    </ul>
    @if (count() > 1) {
      <div class="hero-dots">
        @for (i of slides(); track i) {
          <button type="button" [attr.aria-label]="'Show slide ' + (i + 1)"
                  [attr.aria-current]="row.index() === i" (click)="row.scrollToIndex(i)"></button>
        }
      </div>
    }
  `,
  styles: `
    app-hero-carousel { display: block; min-width: 0; }
    app-hero-carousel > .hero-row {
      display: flex;
      gap: 16px;
      margin: 0;
      padding: 0;
      list-style: none;
      overflow-x: auto;
      overscroll-behavior-x: contain;
      scroll-snap-type: x mandatory;
      scrollbar-width: none;
    }
    app-hero-carousel > .hero-row::-webkit-scrollbar { display: none; }
    app-hero-carousel > .hero-row > li { flex: none; width: calc(100% - 66px); scroll-snap-align: start; }
    app-hero-carousel > .hero-dots { display: flex; justify-content: center; gap: 6px; margin-top: 12px; }
    app-hero-carousel > .hero-dots button {
      width: 8px; height: 8px; padding: 0; border: 0; border-radius: 50%;
      background: #ffffff4d; cursor: pointer;
    }
    app-hero-carousel > .hero-dots button[aria-current="true"] { width: 24px; border-radius: var(--r-pill, 9999px); background: #fff; }
    app-hero-carousel > .hero-dots button:focus-visible { outline: 2px solid #fff; outline-offset: 2px; }
    @media (max-width: 1023px) {
      app-hero-carousel > .hero-row > li { width: calc(100% - 70px); }
    }
    @media (max-width: 599px) {
      app-hero-carousel > .hero-row > li { width: calc(100% - 28px); }
    }
  `,
})
export class HeroCarouselComponent {
  readonly label = input('Featured');
  /** Number of projected slides, for the dots. */
  readonly count = input.required<number>();
  protected readonly slides = computed(() => Array.from({ length: this.count() }, (_, i) => i));
}
