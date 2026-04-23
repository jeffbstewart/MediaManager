import { Component, inject, signal, OnInit, OnDestroy, ChangeDetectionStrategy } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatListModule } from '@angular/material/list';
import { MatMenuModule } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
import { AuthService } from '../auth.service';
import { CatalogService, SearchResult } from '../catalog.service';
import { FeatureService } from '../feature.service';
import { AppRoutes } from '../routes';
import { ReportProblemDialogComponent } from './report-problem-dialog';
import { AudioPlayerComponent } from '../../shared/audio-player/audio-player';
import { PlaybackQueueService } from '../playback-queue.service';

@Component({
  selector: 'app-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatToolbarModule,
    MatSidenavModule,
    MatIconModule,
    MatButtonModule,
    MatListModule,
    MatMenuModule,
    MatDividerModule,
    ReportProblemDialogComponent,
    AudioPlayerComponent,
  ],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class ShellComponent implements OnInit, OnDestroy {
  private readonly auth = inject(AuthService);
  private readonly catalog = inject(CatalogService);
  private readonly router = inject(Router);
  readonly features = inject(FeatureService);
  readonly playbackQueue = inject(PlaybackQueueService);

  readonly routes = AppRoutes;

  // Search
  readonly searchQuery = signal('');
  readonly suggestions = signal<SearchResult[]>([]);
  readonly showSuggestions = signal(false);
  private searchDebounce: ReturnType<typeof setTimeout> | null = null;

  // Responsive sidenav. On phones (incl. iOS Chrome) the sidenav lives
  // in `over` mode and starts closed so the page content isn't pushed
  // off-screen by a 260 px drawer. On tablet+ it stays in `side` mode,
  // pinned open. Tracked via matchMedia so it reacts to rotation /
  // window resizes without an Angular CDK dependency.
  private readonly mobileQuery: MediaQueryList | null =
    typeof window !== 'undefined' ? window.matchMedia('(max-width: 768px)') : null;
  readonly isMobile = signal(this.mobileQuery?.matches ?? false);
  private readonly mobileListener = (e: MediaQueryListEvent) => this.isMobile.set(e.matches);
  private routerSub?: { unsubscribe(): void };

  async ngOnInit(): Promise<void> {
    this.mobileQuery?.addEventListener('change', this.mobileListener);
    // Auto-close the over-mode drawer after a navigation so tapping a
    // nav item on a phone doesn't leave the drawer covering the page.
    this.routerSub = this.router.events
      .pipe(filter(e => e instanceof NavigationEnd))
      .subscribe(() => {
        if (this.isMobile()) this.drawerOpen.set(false);
      });
    try {
      const flags = await this.catalog.getFeatures();
      this.features.update(flags);
    } catch {
      // Non-fatal — nav will show minimal set until home page loads
    }
  }

  ngOnDestroy(): void {
    this.mobileQuery?.removeEventListener('change', this.mobileListener);
    this.routerSub?.unsubscribe();
  }

  /** Drawer open state. Starts open on desktop, closed on mobile. */
  readonly drawerOpen = signal(!(this.mobileQuery?.matches ?? false));
  toggleDrawer(): void { this.drawerOpen.update(v => !v); }
  readonly purchasesOpen = signal(false);
  readonly transcodesOpen = signal(false);

  // Report problem dialog
  readonly reportDialogOpen = signal(false);
  readonly reportTitleId = signal<number | null>(null);
  readonly reportTitleName = signal<string | null>(null);
  readonly reportSeasonNumber = signal<number | null>(null);
  readonly reportEpisodeNumber = signal<number | null>(null);

  onSearchInput(event: Event): void {
    const q = (event.target as HTMLInputElement).value;
    this.searchQuery.set(q);
    if (this.searchDebounce) clearTimeout(this.searchDebounce);
    if (q.trim().length < 2) {
      this.suggestions.set([]);
      return;
    }
    this.searchDebounce = setTimeout(async () => {
      try {
        const data = await this.catalog.search(q.trim(), 8);
        this.suggestions.set(data.results);
        this.showSuggestions.set(true);
      } catch { /* ignore */ }
    }, 250);
  }

  onSearchSubmit(): void {
    const q = this.searchQuery().trim();
    if (q) {
      this.showSuggestions.set(false);
      this.router.navigate(['/search'], { queryParams: { q } });
    }
  }

  onSearchFocus(): void {
    if (this.suggestions().length > 0) this.showSuggestions.set(true);
  }

  onSearchBlur(): void {
    setTimeout(() => this.showSuggestions.set(false), 200);
  }

  clearSearch(): void {
    this.searchQuery.set('');
    this.suggestions.set([]);
    this.showSuggestions.set(false);
  }

  /**
   * Navigate to the search page with `advanced=1`, which triggers the
   * Advanced dialog to open automatically. The current input text (if
   * any) rides along so the user doesn't lose what they typed.
   */
  openAdvancedSearch(): void {
    const queryParams: Record<string, string> = { advanced: '1' };
    const q = this.searchQuery().trim();
    if (q) queryParams['q'] = q;
    this.showSuggestions.set(false);
    this.router.navigate(['/search'], { queryParams });
  }

  onSuggestionClick(): void {
    this.showSuggestions.set(false);
    this.searchQuery.set('');
  }

  resultRoute(item: SearchResult): string {
    switch (item.type) {
      case 'movie': case 'tv': case 'personal':
      case 'book': case 'album':
      case 'track':
        // Tracks open the album detail page — no per-track surface exists.
        return `/title/${item.title_id}`;
      case 'actor': return `/actor/${item.person_id}`;
      case 'artist': return `/artist/${item.artist_id}`;
      case 'author': return `/author/${item.author_id}`;
      case 'collection': return `/content/collection/${item.collection_id}`;
      case 'tag': return `/tag/${item.tag_id}`;
      case 'channel': return `/live-tv/${item.channel_id}`;
      case 'camera': return '/cameras';
      default: return '/';
    }
  }

  typeLabel(type: string): string {
    switch (type) {
      case 'movie': return 'Movie';
      case 'tv': return 'TV';
      case 'personal': return 'Video';
      case 'book': return 'Book';
      case 'album': return 'Album';
      case 'track': return 'Track';
      case 'actor': return 'Actor';
      case 'artist': return 'Artist';
      case 'author': return 'Author';
      case 'collection': return 'Collection';
      case 'tag': return 'Tag';
      case 'channel': return 'Channel';
      case 'camera': return 'Camera';
      default: return type;
    }
  }

  togglePurchases(): void {
    this.purchasesOpen.update(v => !v);
  }

  toggleTranscodes(): void {
    this.transcodesOpen.update(v => !v);
  }

  async openReportDialog(): Promise<void> {
    // Reset context
    this.reportTitleId.set(null);
    this.reportTitleName.set(null);
    this.reportSeasonNumber.set(null);
    this.reportEpisodeNumber.set(null);

    // Extract title context from current URL
    const url = this.router.url;
    const titleMatch = url.match(/^\/title\/(\d+)/);
    if (titleMatch) {
      const titleId = Number(titleMatch[1]);
      this.reportTitleId.set(titleId);
      try {
        const detail = await this.catalog.getTitleDetail(titleId);
        this.reportTitleName.set(detail.title_name);
      } catch { /* context is optional */ }
    }

    this.reportDialogOpen.set(true);
  }

  async onReportDialogSubmitted(): Promise<void> {
    // Refresh feature flags so badge count updates
    try {
      const flags = await this.catalog.getFeatures();
      this.features.update(flags);
    } catch { /* non-fatal */ }
  }

  onReportDialogClosed(): void {
    this.reportDialogOpen.set(false);
  }

  onLogout(): void {
    this.auth.logout();
  }
}
