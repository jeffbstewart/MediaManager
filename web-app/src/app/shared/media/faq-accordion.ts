import { ChangeDetectionStrategy, Component, input, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

export interface FaqEntry {
  readonly question: string;
  readonly answer: string;
}

let nextId = 0;

/**
 * FAQ accordion (spec 3.9): a centered 700px column of rows, each a
 * button with `aria-expanded` / `aria-controls` that reveals its
 * answer under a hairline. Any number of rows can be open.
 */
@Component({
  selector: 'app-faq-accordion',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule],
  template: `
    <h2>{{ heading() }}</h2>
    <div class="faq-list">
      @for (e of entries(); track e.question; let i = $index) {
        <div class="faq-item">
          <button class="faq-q" type="button" [id]="prefix + '-q' + i"
                  [attr.aria-expanded]="open().has(i)" [attr.aria-controls]="prefix + '-a' + i"
                  (click)="toggle(i)">
            <span>{{ e.question }}</span>
            <mat-icon aria-hidden="true">{{ open().has(i) ? 'remove' : 'add' }}</mat-icon>
          </button>
          <div class="faq-a" role="region" [id]="prefix + '-a' + i"
               [attr.aria-labelledby]="prefix + '-q' + i" [hidden]="!open().has(i)">
            {{ e.answer }}
          </div>
        </div>
      }
    </div>
  `,
  styles: `
    :host { display: block; box-sizing: border-box; width: 100%; padding: 24px var(--pad-x); }
    :host > * { max-width: 700px; margin-left: auto; margin-right: auto; }
    h2 {
      margin-bottom: 24px; text-align: center;
      font: 700 var(--fs-h2, 1.25rem)/27px var(--f-display, inherit);
      color: var(--c-text, var(--mat-sys-on-surface));
    }
    .faq-list { display: flex; flex-direction: column; gap: 16px; }
    .faq-item { overflow: hidden; border-radius: var(--r-card, 8px); background: var(--c-scrim, #0000004d); }
    .faq-q {
      display: flex; align-items: center; justify-content: space-between; gap: 16px;
      width: 100%; min-height: 52px; padding: 14px 16px; border: 0; background: none; cursor: pointer;
      text-align: left; font: 700 16px/22px var(--f-display, inherit);
      color: var(--c-text, var(--mat-sys-on-surface));
    }
    .faq-q:hover { background: #ffffff0d; }
    .faq-q:focus-visible { outline: 2px solid var(--c-accent, var(--mat-sys-primary)); outline-offset: -2px; }
    .faq-q mat-icon { width: 20px; height: 20px; font-size: 20px; color: var(--c-text-2, var(--mat-sys-on-surface-variant)); }
    .faq-a {
      padding: 16px; border-top: 1px solid #ffffff1a;
      font-size: 16px; line-height: 22px; color: var(--c-text-2, var(--mat-sys-on-surface-variant));
    }
    .faq-a[hidden] { display: none; }
  `,
})
export class FaqAccordionComponent {
  readonly heading = input('Frequently asked questions');
  readonly entries = input.required<readonly FaqEntry[]>();

  protected readonly prefix = `faq-${nextId++}`;
  protected readonly open = signal<ReadonlySet<number>>(new Set());

  toggle(i: number): void {
    this.open.update(s => {
      const next = new Set(s);
      if (next.has(i)) next.delete(i); else next.add(i);
      return next;
    });
  }
}
