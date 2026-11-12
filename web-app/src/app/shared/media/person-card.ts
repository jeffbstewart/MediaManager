import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { RouterLink } from '@angular/router';
import { CardLink } from './poster-card';
import { artInitial } from './art-hue';

/**
 * Person or artist card (spec 3.6): a round photo, the name, up to two
 * meta lines and a small ghost pill. The whole card is one link.
 *
 * With `withActions` the card instead holds its own controls: buttons
 * projected with the `cardAction` attribute sit where the ghost pill
 * goes, the card itself is not a link, and the name links to `link`
 * when one is given. No control is nested in an anchor.
 */
@Component({
  selector: 'app-person-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, NgTemplateOutlet],
  host: { class: 'person-card' },
  template: `
    <ng-template #photo>
      <span class="person-img">
        @if (imageUrl(); as src) {
          <img [src]="src" alt="" loading="lazy" decoding="async" />
        } @else {
          <span class="initial" aria-hidden="true">{{ initial() }}</span>
        }
      </span>
    </ng-template>
    @if (withActions()) {
      <div class="person">
        <ng-container [ngTemplateOutlet]="photo" />
        <span class="person-info">
          @if (link(); as l) {
            <a class="n" [routerLink]="l">{{ name() }}</a>
          } @else {
            <span class="n">{{ name() }}</span>
          }
          @for (line of lines(); track $index) { <span class="m">{{ line }}</span> }
          <span class="person-actions"><ng-content select="[cardAction]" /></span>
        </span>
      </div>
    } @else {
      <a class="person" [routerLink]="link()">
        <ng-container [ngTemplateOutlet]="photo" />
        <span class="person-info">
          <span class="n">{{ name() }}</span>
          @for (line of lines(); track $index) { <span class="m">{{ line }}</span> }
          @if (action(); as a) { <span class="pill">{{ a }}</span> }
        </span>
      </a>
    }
  `,
  styles: `
    :host { display: block; }
    .person {
      display: flex; align-items: center; gap: 16px; box-sizing: border-box;
      width: var(--person-w, 350px); height: 160px; padding: 16px;
      border-radius: var(--r-card, 8px);
      background: var(--c-surface, var(--mat-sys-surface-container));
      color: inherit; text-decoration: none;
    }
    .person:focus-visible { outline: 2px solid var(--c-accent, var(--mat-sys-primary)); outline-offset: 2px; }
    .person-img {
      flex: none; width: 128px; height: 128px; overflow: hidden; border-radius: 50%;
      background: var(--c-grey-2, var(--mat-sys-surface-container-high));
      box-shadow: var(--sh-card, rgba(0, 0, 0, 0.2) 0 4px 4px 0);
    }
    img { display: block; width: 100%; height: 100%; object-fit: cover; }
    a.n { text-decoration: none; }
    a.n:hover { text-decoration: underline; }
    a.n:focus-visible { outline: 2px solid var(--c-accent, var(--mat-sys-primary)); outline-offset: 2px; border-radius: var(--r-badge, 4px); }
    .person-actions { display: flex; align-items: center; gap: 8px; margin-top: 12px; }
    div.person .person-info { flex: 1; }
    div.person .m { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .initial {
      display: grid; place-items: center; width: 100%; height: 100%;
      font: 700 48px/1 var(--f-display, inherit); color: #fff;
    }
    .person-info { display: flex; flex-direction: column; align-items: flex-start; min-width: 0; }
    .n {
      max-width: 170px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
      font: 400 16px/20px var(--f-body, inherit); color: var(--c-text, var(--mat-sys-on-surface));
    }
    .m { font: 600 12px/16px var(--f-body, inherit); color: var(--c-text-3, var(--mat-sys-on-surface-variant)); }
    .pill {
      display: inline-flex; align-items: center; height: 32px; margin-top: 12px; padding: 0 12px;
      border-radius: var(--r-pill, 9999px); background: #ffffff1a;
      font: 600 14px/1 var(--f-body, inherit); color: var(--c-text, var(--mat-sys-on-surface));
    }
    @media (max-width: 599px) {
      .person { width: var(--person-w, 300px); height: 132px; }
      .person-img { width: 100px; height: 100px; }
    }
  `,
})
export class PersonCardComponent {
  readonly name = input.required<string>();
  /** Target of the card, or of the name with `withActions`; null leaves the name plain. */
  readonly link = input<CardLink | null>(null);
  readonly imageUrl = input<string | null | undefined>(null);
  readonly lines = input<readonly string[]>([]);
  /** Label of the ghost pill, e.g. "View artist". */
  readonly action = input<string | null>(null);
  /** Hold projected `[cardAction]` controls instead of being one link. */
  readonly withActions = input(false);
  protected readonly initial = computed(() => artInitial(this.name()));
}
