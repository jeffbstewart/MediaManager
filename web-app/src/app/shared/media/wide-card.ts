import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CardLink } from './poster-card';
import { PlaceholderArtComponent } from './placeholder-art';

/**
 * Wide 16:9 card (spec 3.3) for Continue Watching, New Seasons and
 * Live TV: backdrop art with a bottom scrim, the title over the art,
 * optional progress pill and corner badge, and a caption below.
 * Width comes from `--card-w`, falling back to `--wide-w`. With a
 * `titleLink`, the caption title is its own link (the title page) and
 * the card itself still links to `link` (the player), as the poster
 * card does.
 */
@Component({
  selector: 'app-wide-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, PlaceholderArtComponent],
  host: { class: 'wide-card' },
  template: `
    <a class="card-link" [routerLink]="link()" [queryParams]="queryParams()">
      <div class="art">
        @if (imageUrl() && !imgFailed()) {
          <img [src]="imageUrl()" alt="" loading="lazy" decoding="async" (error)="imgFailed.set(true)" />
        } @else {
          <app-placeholder-art [name]="name()" [showName]="false" />
        }
        <div class="scrim"></div>
        <span class="overlay-title" aria-hidden="true">{{ name() }}</span>
        <ng-content select="[cardArt]" />
        @if (badge(); as b) {
          <span class="badge" [class.accent]="badgeTone() === 'accent'">{{ b }}</span>
        }
        @if (percent() > 0) {
          <div class="progress" role="progressbar" aria-label="Progress"
               aria-valuemin="0" aria-valuemax="100" [attr.aria-valuenow]="rounded()">
            <div class="progress-fill" [style.width.%]="percent()"></div>
          </div>
        }
      </div>
      @if (!titleLink()) {
        <span class="caption">
          <span class="t">{{ caption() ?? name() }}</span>
          @if (meta(); as m) { <span class="m" [class.accent]="metaAccent()">{{ m }}</span> }
        </span>
      }
    </a>
    @if (titleLink(); as tl) {
      <span class="caption">
        <a class="t wide-title" [routerLink]="tl">{{ caption() ?? name() }}</a>
        @if (meta(); as m) { <span class="m" [class.accent]="metaAccent()">{{ m }}</span> }
      </span>
    }
    <div class="card-actions"><ng-content select="[cardAction]" /></div>
  `,
  styles: `
    :host { position: relative; display: block; width: var(--card-w, var(--wide-w)); min-width: 0; }
    .card-link { display: flex; flex-direction: column; color: inherit; text-decoration: none; }
    .card-link:focus-visible {
      outline: 2px solid var(--c-accent, var(--mat-sys-primary));
      outline-offset: 2px;
      border-radius: var(--r-card, 8px);
    }
    .art {
      position: relative;
      aspect-ratio: 16 / 9;
      overflow: hidden;
      border-radius: var(--r-card, 8px);
      background: var(--c-grey-1, var(--mat-sys-surface-container));
      box-shadow: var(--sh-card, rgba(0, 0, 0, 0.2) 0 4px 4px 0);
    }
    .art::after {
      content: ""; position: absolute; inset: 0; border-radius: inherit;
      box-shadow: inset 0 0 0 1px #ffffff14; pointer-events: none;
    }
    img { display: block; width: 100%; height: 100%; object-fit: cover; transition: transform 0.25s; }
    .card-link:hover img, .card-link:focus-visible img { transform: scale(1.04); }
    .scrim { position: absolute; inset: auto 0 0 0; height: 50%; background: linear-gradient(0deg, rgba(0, 0, 0, 0.7), transparent); }
    .overlay-title {
      position: absolute; left: 12px; right: 12px; bottom: 16px;
      font: 700 18px/1.1 var(--f-display, inherit); color: #fff;
      text-shadow: 0 2px 8px rgba(0, 0, 0, 0.6);
      display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
    }
    .badge {
      position: absolute; top: 0; right: 0; padding: 6px 8px;
      border-radius: 0 0 0 var(--r-card, 8px);
      background: var(--c-live, #dc3004); color: #fff;
      font: 700 12px/1 var(--f-body, inherit); text-transform: uppercase;
      &.accent { background: var(--c-accent, var(--mat-sys-primary)); color: #000; }
    }
    .progress {
      position: absolute; left: 8px; right: 8px; bottom: 8px; height: 4px; overflow: hidden;
      border-radius: var(--r-pill, 9999px); background: #ffffff4d;
    }
    .progress-fill { height: 100%; border-radius: inherit; background: var(--c-accent, var(--mat-sys-primary)); }
    .caption { display: flex; flex-direction: column; min-width: 0; padding-top: 8px; }
    .t, .m { display: block; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    a.wide-title { color: inherit; text-decoration: none; }
    a.wide-title:hover { text-decoration: underline; }
    a.wide-title:focus-visible { outline: 2px solid var(--c-accent, var(--mat-sys-primary)); outline-offset: 2px; border-radius: 2px; }
    .t { font: 400 14px/18px var(--f-body, inherit); color: var(--c-text, var(--mat-sys-on-surface)); }
    .m { font: 600 12px/16px var(--f-body, inherit); color: var(--c-text-3, var(--mat-sys-on-surface-variant)); }
    .m.accent { color: var(--c-accent, var(--mat-sys-primary)); }
    .card-actions { position: absolute; top: 0; left: 0; width: 100%; height: 0; }
    @media (prefers-reduced-motion: reduce) {
      img { transition: none; }
      .card-link:hover img, .card-link:focus-visible img { transform: none; }
    }
  `,
})
export class WideCardComponent {
  readonly name = input.required<string>();
  readonly link = input.required<CardLink>();
  readonly queryParams = input<Record<string, string> | null>(null);
  /** When set, the caption title links here (the title page) while the card links to `link` (the player). */
  readonly titleLink = input<CardLink | null>(null);
  readonly imageUrl = input<string | null | undefined>(null);
  /** Caption title; defaults to `name` (e.g. "Show • S2 • E5"). */
  readonly caption = input<string | null>(null);
  readonly meta = input<string | null | undefined>(null);
  readonly metaAccent = input(false);
  readonly progress = input<number | null | undefined>(null);
  readonly badge = input<string | null>(null);
  readonly badgeTone = input<'live' | 'accent'>('live');

  /** Set when the art URL fails to load; the card then shows placeholder art instead of a broken image. */
  protected readonly imgFailed = signal(false);

  protected readonly percent = computed(() => {
    const p = this.progress();
    return p ? Math.max(0, Math.min(100, p * 100)) : 0;
  });
  protected readonly rounded = computed(() => Math.round(this.percent()));
}
