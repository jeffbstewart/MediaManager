import {
  Component, inject, signal, OnInit, OnDestroy, ChangeDetectionStrategy,
  ElementRef, afterNextRender, viewChild, computed, effect, DOCUMENT,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { MatIconModule } from '@angular/material/icon';
import { AuthService } from '../auth.service';
import { CatalogService } from '../catalog.service';
import { FeatureService } from '../feature.service';
import { ReportProblemDialogComponent } from './report-problem-dialog';
import { NavListComponent } from './nav-list';
import { SearchBoxComponent } from './search-box';
import { AccountMenuComponent } from './account-menu';
import { AudioPlayerComponent } from '../../shared/audio-player/audio-player';
import { PlaybackQueueService } from '../playback-queue.service';
import { AppRoutes } from '../routes';
import { watchScrolled } from './scroll-source';
import { TabBarComponent } from './tab-bar';
import { sectionForUrl } from './page-section';

/**
 * App shell (spec section 2): sticky header, fixed side nav at 1024px
 * and up, the same nav in a left sheet below that, and the routed page
 * in `main.app-main`. The document scrolls; nothing in the shell is a
 * scroll container.
 */
@Component({
  selector: 'app-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterOutlet,
    RouterLink,
    MatIconModule,
    NavListComponent,
    SearchBoxComponent,
    AccountMenuComponent,
    ReportProblemDialogComponent,
    AudioPlayerComponent,
    TabBarComponent,
  ],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
  host: {
    '[class.has-player]': 'playbackQueue.hasQueue()',
  },
})
export class ShellComponent implements OnInit, OnDestroy {
  private readonly auth = inject(AuthService);
  private readonly catalog = inject(CatalogService);
  private readonly router = inject(Router);
  readonly features = inject(FeatureService);
  readonly playbackQueue = inject(PlaybackQueueService);
  readonly routes = AppRoutes;

  /** True once the page is scrolled past a few pixels: the header frosts. */
  readonly scrolled = signal(false);
  private stopWatchingScroll?: () => void;

  private readonly navSheet = viewChild.required<ElementRef<HTMLDialogElement>>('navSheet');
  /** The phone / tablet nav sheet (below 1024px). */
  readonly navSheetOpen = signal(false);
  private routerSub?: { unsubscribe(): void };

  private readonly document = inject(DOCUMENT);
  private readonly url = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map(e => e.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );
  /** The section the current page belongs to (tab bar, page glow). */
  readonly section = computed(() => sectionForUrl(this.url()));
  /**
   * `data-page` on `body` picks the page glow's gradient. Only home and
   * discover have their own; every other page gets the plain one.
   */
  private readonly glowPage = computed(() => {
    const s = this.section();
    return s === 'home' || s === 'discover' ? s : null;
  });

  constructor() {
    effect(() => {
      const page = this.glowPage();
      const body = this.document.body;
      if (page) body.dataset['page'] = page;
      else delete body.dataset['page'];
    });
    afterNextRender(() => {
      this.stopWatchingScroll = watchScrolled(window, s => this.scrolled.set(s));
    });
  }

  async ngOnInit(): Promise<void> {
    // Close the nav sheet after a navigation so picking a link
    // doesn't leave it covering the page.
    this.routerSub = this.router.events
      .pipe(filter(e => e instanceof NavigationEnd))
      .subscribe(() => this.closeNavSheet());
    try {
      const flags = await this.catalog.getFeatures();
      this.features.update(flags);
    } catch {
      // Non-fatal — nav will show minimal set until home page loads
    }
  }

  ngOnDestroy(): void {
    this.routerSub?.unsubscribe();
    this.stopWatchingScroll?.();
    delete this.document.body.dataset['page'];
  }

  openNavSheet(): void {
    const d = this.navSheet().nativeElement;
    this.navSheetOpen.set(true);
    if (!d.open) d.showModal();
  }

  closeNavSheet(): void {
    const d = this.navSheet().nativeElement;
    if (d.open) d.close();
  }

  /** Fired by the dialog itself for every close path, Escape included. */
  onNavSheetClosed(): void {
    this.navSheetOpen.set(false);
  }

  /**
   * A click whose point lies outside the panel's box landed on the
   * ::backdrop (the dialog element receives those events), so close.
   */
  onNavSheetClick(e: MouseEvent): void {
    const d = this.navSheet().nativeElement;
    if (e.target !== d) return;
    const r = d.getBoundingClientRect();
    if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) {
      this.closeNavSheet();
    }
  }

  // Report problem dialog
  readonly reportDialogOpen = signal(false);
  readonly reportTitleId = signal<number | null>(null);
  readonly reportTitleName = signal<string | null>(null);
  readonly reportSeasonNumber = signal<number | null>(null);
  readonly reportEpisodeNumber = signal<number | null>(null);

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
