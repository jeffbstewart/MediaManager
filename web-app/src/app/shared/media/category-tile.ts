import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CardLink } from './poster-card';
import { artHue } from './art-hue';

/**
 * Category tile (spec 3.7): poster width by 116px, a saturated
 * two-stop gradient with soft highlights, and a centered label. The
 * gradient comes from `colors` or, when unset, from the label's hue.
 * Projected content sits under the label (a count line, say), and
 * `textColor` overrides the white label for light user-picked colors.
 */
@Component({
  selector: 'app-category-tile',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  template: `
    <a class="cat-tile" [routerLink]="link()" [style.background]="background()" [style.color]="textColor()">
      <span class="cat-label">{{ label() }}</span>
      <ng-content />
    </a>
  `,
  styles: `
    :host { display: block; }
    .cat-tile {
      position: relative; display: flex; flex-direction: column; gap: 4px;
      align-items: center; justify-content: center; overflow: hidden;
      width: var(--card-w, var(--poster-w)); height: 116px; padding: 0 12px; box-sizing: border-box;
      border-radius: var(--r-card, 8px);
      box-shadow: var(--sh-card, rgba(0, 0, 0, 0.2) 0 4px 4px 0);
      color: #fff; font: 400 16px/1.25 var(--f-body, inherit); text-align: center; text-decoration: none;
    }
    .cat-tile::after {
      content: ""; position: absolute; inset: 0; pointer-events: none;
      background:
        radial-gradient(120% 80% at 80% 110%, rgba(255, 255, 255, 0.18), transparent 60%),
        radial-gradient(90% 60% at 10% -10%, rgba(0, 0, 0, 0.18), transparent 60%);
    }
    .cat-tile > * { position: relative; z-index: 1; text-shadow: 0 1px 4px rgba(0, 0, 0, 0.5); }
    .cat-tile:focus-visible { outline: 2px solid #fff; outline-offset: 2px; }
    @media (max-width: 599px) { .cat-tile { height: 80px; } }
  `,
})
export class CategoryTileComponent {
  readonly label = input.required<string>();
  readonly link = input.required<CardLink>();
  /** Two gradient stops; derived from the label when null. */
  readonly colors = input<readonly [string, string] | null>(null);
  /** Label color; white when null. */
  readonly textColor = input<string | null>(null);

  protected readonly background = computed(() => {
    const c = this.colors();
    if (c) return `linear-gradient(135deg, ${c[0]}, ${c[1]})`;
    const h = artHue(this.label());
    return `linear-gradient(135deg, hsl(${h} 55% 30%), hsl(${(h + 50) % 360} 60% 20%))`;
  });
}
