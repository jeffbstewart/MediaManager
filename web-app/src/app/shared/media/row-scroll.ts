import { DestroyRef, Directive, ElementRef, afterNextRender, inject, signal } from '@angular/core';

/**
 * Tracks a horizontally scrolling row (spec 3.1) and pages it.
 * Ported from the prototype's `wireRows`: a passive scroll listener
 * plus a ResizeObserver keep `atStart`, `atEnd` and `index` current,
 * so the arrows hide at the ends and carousel dots follow the scroll.
 */
@Directive({
  selector: '[appRowScroll]',
  exportAs: 'rowScroll',
})
export class RowScrollDirective {
  private readonly el = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  private readonly destroyRef = inject(DestroyRef);

  readonly atStart = signal(true);
  readonly atEnd = signal(true);
  /** Index of the child nearest the row's left edge. */
  readonly index = signal(0);

  constructor() {
    const sync = () => this.sync();
    afterNextRender(() => {
      this.el.addEventListener('scroll', sync, { passive: true });
      const ro = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(sync);
      ro?.observe(this.el);
      sync();
      this.destroyRef.onDestroy(() => {
        this.el.removeEventListener('scroll', sync);
        ro?.disconnect();
      });
    });
  }

  /** Scroll by most of a viewport width; `dir` is -1 or 1. */
  page(dir: -1 | 1): void {
    this.el.scrollBy({ left: dir * this.el.clientWidth * 0.85, behavior: this.behavior() });
  }

  /** Scroll so child `i` sits at the row's left edge. */
  scrollToIndex(i: number): void {
    const child = this.el.children[i] as HTMLElement | undefined;
    if (!child) return;
    this.el.scrollTo({ left: child.offsetLeft - this.el.offsetLeft, behavior: this.behavior() });
  }

  private sync(): void {
    const r = this.el;
    this.atStart.set(r.scrollLeft < 4);
    this.atEnd.set(r.scrollLeft + r.clientWidth >= r.scrollWidth - 4);
    const first = r.firstElementChild as HTMLElement | null;
    const gap = parseFloat(getComputedStyle(r).columnGap) || 0;
    const w = (first?.offsetWidth ?? 0) + gap;
    this.index.set(w > 0 ? Math.round(r.scrollLeft / w) : 0);
  }

  private behavior(): ScrollBehavior {
    return matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth';
  }
}
