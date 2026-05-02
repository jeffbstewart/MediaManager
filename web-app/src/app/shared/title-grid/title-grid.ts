import {
  Component, input, inject, signal, computed,
  OnInit, OnDestroy, viewChild, ElementRef, effect,
  ChangeDetectionStrategy,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AppRoutes } from '../../core/routes';
import {
  CatalogService,
  TitleCard,
  MediaType,
  SortMode,
} from '../../core/catalog.service';

/**
 * Page size for incremental loads. Larger than the screen so the user
 * scrolls a bit before the next fetch fires; small enough that the
 * first paint is fast.
 */
const PAGE_SIZE = 60;

@Component({
  selector: 'app-title-grid',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    MatChipsModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './title-grid.html',
  styleUrl: './title-grid.scss',
})
export class TitleGridComponent implements OnInit, OnDestroy {
  readonly mediaType = input.required<MediaType>();
  readonly label = input.required<string>();

  private readonly catalog = inject(CatalogService);
  readonly routes = AppRoutes;

  constructor() {
    // The sentinel is rendered inside an @if branch that flips when
    // loading() goes false. Arm the IntersectionObserver reactively
    // on the viewchild signal so we re-arm every time the sentinel's
    // host element changes (initial render, reset on filter change).
    effect(() => {
      const el = this.sentinel()?.nativeElement;
      if (!el) return;
      this.armObserver(el);
    });
  }

  readonly loading = signal(true);
  readonly loadingMore = signal(false);
  readonly error = signal('');
  readonly titles = signal<TitleCard[]>([]);
  readonly total = signal(0);
  readonly availableRatings = signal<string[]>([]);
  readonly page = signal(1);

  readonly hasMore = computed(() => this.titles().length < this.total());

  readonly playableOnly = signal(true);
  readonly selectedRatings = signal<Set<string>>(new Set());
  readonly sortMode = signal<SortMode>('name');

  // Sort chips vary by media type. Books and albums replace "Popular"
  // (no ingestion populates it for those types) with the primary-credit
  // sort — author for books, album artist for music.
  readonly sortOptions = computed<{ value: SortMode; label: string }[]>(() => {
    switch (this.mediaType()) {
      case 'ALBUM':
        return [
          { value: 'name', label: 'Name' },
          { value: 'artist', label: 'Artist' },
          { value: 'year', label: 'Year' },
          { value: 'recent', label: 'Recent' },
        ];
      case 'BOOK':
        return [
          { value: 'name', label: 'Name' },
          { value: 'author', label: 'Author' },
          { value: 'year', label: 'Year' },
          { value: 'recent', label: 'Recent' },
        ];
      default:
        return [
          { value: 'name', label: 'Name' },
          { value: 'year', label: 'Year' },
          { value: 'recent', label: 'Recent' },
          { value: 'popular', label: 'Popular' },
        ];
    }
  });

  // Sentinel element below the grid — when it scrolls into view we
  // fetch the next page. Cleaner than a scroll listener.
  private readonly sentinel = viewChild<ElementRef<HTMLElement>>('sentinel');
  private observer: IntersectionObserver | null = null;

  async ngOnInit(): Promise<void> {
    await this.loadFirstPage();
  }

  ngOnDestroy(): void {
    this.observer?.disconnect();
    this.observer = null;
  }

  async togglePlayable(): Promise<void> {
    this.playableOnly.update(v => !v);
    await this.loadFirstPage();
  }

  async toggleRating(rating: string): Promise<void> {
    this.selectedRatings.update(set => {
      const next = new Set(set);
      if (next.has(rating)) {
        next.delete(rating);
      } else {
        next.add(rating);
      }
      return next;
    });
    await this.loadFirstPage();
  }

  async clearRatings(): Promise<void> {
    this.selectedRatings.set(new Set());
    await this.loadFirstPage();
  }

  async setSort(mode: SortMode): Promise<void> {
    this.sortMode.set(mode);
    await this.loadFirstPage();
  }

  /**
   * Reset the grid and fetch page 1. Called on initial load and on
   * every filter/sort change.
   */
  private async loadFirstPage(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    this.titles.set([]);
    this.page.set(1);
    try {
      const data = await this.fetchPage(1);
      this.titles.set(data.titles);
      this.total.set(data.total);
      this.availableRatings.set(data.available_ratings);
    } catch {
      this.error.set('Failed to load titles');
    } finally {
      this.loading.set(false);
    }
    // The viewchild effect re-arms the observer when the sentinel
    // re-enters the DOM after the loading-state @if flips.
  }

  /**
   * Fetch the next page if there's more to load and we're not already
   * loading. Triggered by the IntersectionObserver on the bottom sentinel.
   */
  private async loadMore(): Promise<void> {
    if (this.loadingMore() || this.loading() || !this.hasMore()) return;
    this.loadingMore.set(true);
    try {
      const next = this.page() + 1;
      const data = await this.fetchPage(next);
      this.titles.update(existing => [...existing, ...data.titles]);
      this.total.set(data.total);
      this.page.set(next);
    } catch {
      // Don't clobber the existing visible items on a partial failure;
      // surface a discreet error and let the user retry by scrolling.
      this.error.set('Failed to load more titles');
    } finally {
      this.loadingMore.set(false);
    }
  }

  private async fetchPage(page: number) {
    const ratings = this.selectedRatings();
    return await this.catalog.getTitles({
      mediaType: this.mediaType(),
      sort: this.sortMode(),
      ratings: ratings.size > 0 ? [...ratings] : undefined,
      playableOnly: this.playableOnly(),
      page,
      limit: PAGE_SIZE,
    });
  }

  private armObserver(el: HTMLElement): void {
    // Disconnect any prior observation so we never have two armed.
    this.observer?.disconnect();
    this.observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) {
          // Fire and forget — loadMore handles its own concurrency.
          this.loadMore();
        }
      }
    }, {
      // Pre-fetch a page before the user actually hits the bottom so
      // the grid feels uninterrupted.
      rootMargin: '400px',
    });
    this.observer.observe(el);
  }
}
