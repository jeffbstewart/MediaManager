import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { artHue, artInitial } from './art-hue';

/**
 * Stand-in artwork for a title, person or album with no image: a
 * gradient keyed off the name, a large faint initial, and the name at
 * the bottom (spec 3.2, "Missing artwork"). Pass `icon` to show a
 * Material icon in place of the initial.
 *
 * Purely decorative: the card around it already carries the name, so
 * the whole block is hidden from assistive technology. The initial is
 * drawn by a pseudo-element so it is not measured as low-contrast text.
 */
@Component({
  selector: 'app-placeholder-art',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule],
  host: {
    'aria-hidden': 'true',
    '[style.background]': 'background()',
    '[attr.data-initial]': 'icon() ? null : initial()',
  },
  template: `
    @if (icon(); as i) {
      <mat-icon class="ph-icon">{{ i }}</mat-icon>
    }
    @if (showName()) {
      <span class="ph-name">{{ name() }}</span>
    }
  `,
  styles: `
    :host {
      position: absolute;
      inset: 0;
      display: flex;
      align-items: flex-end;
      padding: 12px;
      overflow: hidden;
      color: #fff;
      font: 700 15px/1.2 var(--f-display, inherit);
    }
    :host::before {
      content: attr(data-initial);
      position: absolute;
      inset: 0;
      display: grid;
      place-items: center;
      font-size: 72px;
      color: #ffffff2e;
    }
    .ph-icon {
      position: absolute;
      inset: 0;
      margin: auto;
      width: 48px;
      height: 48px;
      font-size: 48px;
      color: #ffffff59;
    }
    .ph-name {
      position: relative;
      display: -webkit-box;
      -webkit-line-clamp: 3;
      -webkit-box-orient: vertical;
      overflow: hidden;
      text-shadow: 0 1px 4px rgba(0, 0, 0, 0.5);
    }
  `,
})
export class PlaceholderArtComponent {
  readonly name = input.required<string>();
  readonly icon = input<string | null>(null);
  /** Hide the name when the caption below already shows it prominently. */
  readonly showName = input(true);

  protected readonly initial = computed(() => artInitial(this.name()));
  protected readonly background = computed(() => {
    const h = artHue(this.name());
    return `linear-gradient(160deg, hsl(${h} 40% 32%), hsl(${(h + 40) % 360} 45% 16%))`;
  });
}
