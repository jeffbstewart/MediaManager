import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { CardLink } from './poster-card';

/**
 * Hero banner (spec 3.4): a backdrop on the right 62%, filled and
 * faded with `tint` (the backdrop's dark average color), title, meta
 * line, kicker, three-line description and up to two pill actions.
 * Below 1024px it stacks: 16:9 image on top fading into the tint, then
 * the text. Extra actions can be projected with `[heroAction]`.
 */
@Component({
  selector: 'app-hero-banner',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatIconModule],
  host: { class: 'hero-banner' },
  template: `
    <article class="hero" [style.--tint]="tint()" [attr.aria-label]="heading()">
      @if (imageUrl(); as src) {
        <div class="hero-img"><img [src]="src" alt="" [attr.loading]="eager() ? null : 'lazy'" decoding="async" /></div>
      }
      <div class="hero-body">
        @if (level() === 2) {
          <h2 class="hero-title" [class.long]="heading().length > 22">{{ heading() }}</h2>
        } @else {
          <h3 class="hero-title" [class.long]="heading().length > 22">{{ heading() }}</h3>
        }
        <div class="meta">
          @if (contentRating(); as r) { <span class="rating">{{ r }}</span> }
          @if (year(); as y) { <span>{{ y }}</span> }
          @if (metaText(); as m) { <span>{{ m }}</span> }
        </div>
        @if (kicker(); as k) { <p class="hero-kicker">{{ k }}</p> }
        @if (description(); as d) { <p class="hero-desc">{{ d }}</p> }
        <div class="hero-actions">
          @if (primaryLink(); as l) {
            <a class="btn" [routerLink]="l"><mat-icon aria-hidden="true">{{ primaryIcon() }}</mat-icon>{{ primaryLabel() }}</a>
          }
          @if (secondaryLink(); as l) {
            <a class="btn ghost" [routerLink]="l"><mat-icon aria-hidden="true">{{ secondaryIcon() }}</mat-icon>{{ secondaryLabel() }}</a>
          }
          <ng-content select="[heroAction]" />
        </div>
      </div>
    </article>
  `,
  styleUrl: './hero-banner.scss',
})
export class HeroBannerComponent {
  readonly heading = input.required<string>();
  readonly level = input<2 | 3>(3);
  readonly imageUrl = input<string | null | undefined>(null);
  /** `#rrggbb`; falls back to a neutral dark grey. */
  readonly tint = input<string | null | undefined>('#2a2a2a');
  readonly contentRating = input<string | null | undefined>(null);
  readonly year = input<number | null | undefined>(null);
  readonly metaText = input<string | null | undefined>(null);
  readonly kicker = input<string | null | undefined>(null);
  readonly description = input<string | null | undefined>(null);
  readonly primaryLabel = input('Play');
  readonly primaryIcon = input('play_arrow');
  readonly primaryLink = input<CardLink | null>(null);
  readonly secondaryLabel = input('Wish List');
  readonly secondaryIcon = input('add');
  readonly secondaryLink = input<CardLink | null>(null);
  /** The first banner on a page loads eagerly. */
  readonly eager = input(false);
}
