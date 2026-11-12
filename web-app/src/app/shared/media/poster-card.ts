import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { RouterLink } from '@angular/router';
import { PlaceholderArtComponent } from './placeholder-art';

/** A route accepted by `routerLink`. */
export type CardLink = string | readonly (string | number)[];

/**
 * Poster card (spec 3.2): 2:3 artwork, or square for albums, with an
 * optional progress pill, corner badge and caption.
 *
 * The card is one of three things:
 * - a link (`link` set), optionally with a separate `titleLink` for the
 *   caption title (Continue Watching: art plays, title opens the title);
 * - a button (`asButton`), which emits `activate`;
 * - inert (neither), for titles you do not own.
 *
 * Content slots:
 * - `[cardArt]` is drawn over the artwork inside the link (decorative
 *   overlays, count badges);
 * - `[cardAction]` holds buttons placed over the top of the artwork but
 *   outside the link, so no control is nested in an anchor;
 * - anything else is appended to the caption.
 *
 * The host keeps the `poster-card` class the page specs select on.
 * Width comes from `--card-w`, falling back to the `--poster-w` layout
 * token; a grid sets `--card-w: 100%` on its container.
 */
@Component({
  selector: 'app-poster-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, NgTemplateOutlet, PlaceholderArtComponent],
  host: {
    class: 'poster-card',
    '[class.square]': 'square()',
    '[class.unowned]': "dim() !== 'none'",
    '[class.dim-card]': "dim() === 'card'",
    '[class.dim-art]': "dim() === 'art'",
  },
  templateUrl: './poster-card.html',
  styleUrl: './poster-card.scss',
})
export class PosterCardComponent {
  readonly name = input.required<string>();
  readonly imageUrl = input<string | null | undefined>(null);
  readonly link = input<CardLink | null>(null);
  readonly queryParams = input<Record<string, string> | null>(null);
  /** When set, the caption title is its own link and `link` covers the art only. */
  readonly titleLink = input<CardLink | null>(null);
  readonly asButton = input(false);
  /** Tooltip for button cards. */
  readonly hint = input<string | null>(null);

  readonly subtitle = input<string | null | undefined>(null);
  readonly credit = input<string | null | undefined>(null);
  readonly meta = input<string | number | null | undefined>(null);
  readonly metaAccent = input(false);

  readonly square = input(false);
  readonly fit = input<'cover' | 'contain'>('cover');
  readonly lazy = input(true);
  readonly placeholderIcon = input<string | null>(null);

  /** 0 to 1; null or 0 hides the progress pill. */
  readonly progress = input<number | null | undefined>(null);
  readonly playable = input(false);
  readonly badge = input<string | null>(null);
  readonly badgeTone = input<'live' | 'accent'>('live');
  /** Not-owned styling: fade the whole card, or the artwork only. */
  readonly dim = input<'none' | 'card' | 'art'>('none');

  readonly activate = output<void>();

  protected readonly percent = computed(() => {
    const p = this.progress();
    return p ? Math.max(0, Math.min(100, p * 100)) : 0;
  });
  protected readonly rounded = computed(() => Math.round(this.percent()));
  protected readonly mode = computed<'link' | 'split' | 'button' | 'static'>(() => {
    if (this.link() !== null) return this.titleLink() !== null ? 'split' : 'link';
    return this.asButton() ? 'button' : 'static';
  });
}
