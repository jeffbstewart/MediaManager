import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import {
  ArtistRecommendation,
  CatalogService,
  HeroTitle,
  HomeFeed,
  MissingSeason,
  TitleCard,
} from '../../core/catalog.service';
import { FeatureService } from '../../core/feature.service';
import { AppRoutes } from '../../core/routes';
import {
  HeroBannerComponent,
  MediaRowComponent,
  PersonCardComponent,
  PosterCardComponent,
  SectionHeadComponent,
  WideCardComponent,
} from '../../shared/media';

/** The single hero at the top of the page: a show with seasons to acquire. */
interface DiscoverHero {
  title: HeroTitle;
  kicker: string | null;
}

/**
 * Discover (docs/plans/web-theming.md, Phase 3; prototype
 * `trendingPage`). The frame comes from the home feed: a hero banner
 * for the spotlight show naming its missing seasons, the promo box
 * that points at the wish list, and a "New Seasons Out" row of wide
 * cards, then "More From Authors You Read" from the feed's recently
 * added books. A feed that fails to load just leaves those rows out.
 * "Popular In Your Library" closes the page with the most popular
 * movies from `ListTitles`; it is left out when empty.
 *
 * Between them, the artist recommendations, as person cards, read the
 * pre-computed `ArtistService.ListArtistRecommendations` payload (M8 /
 * docs/MUSIC.md) with Wishlist and Dismiss per artist. They are gated
 * on `has_music_radio`, because the recommendation engine and radio
 * share the same Last.fm cache; when the gate is off that section
 * shows an explainer.
 */
@Component({
  selector: 'app-discover',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink, MatIconModule, MatButtonModule, MatProgressSpinnerModule,
    HeroBannerComponent, MediaRowComponent, PersonCardComponent, PosterCardComponent,
    SectionHeadComponent, WideCardComponent,
  ],
  templateUrl: './discover.html',
  styleUrl: './discover.scss',
})
export class DiscoverComponent implements OnInit {
  private readonly catalog = inject(CatalogService);
  private readonly features = inject(FeatureService);
  private readonly http = inject(HttpClient);

  readonly routes = AppRoutes;
  readonly feed = signal<HomeFeed | null>(null);
  readonly loading = signal(true);
  readonly refreshing = signal(false);
  readonly error = signal('');
  readonly artists = signal<ArtistRecommendation[]>([]);
  readonly wishSubmitting = signal<Set<string>>(new Set());
  readonly wishedMbids = signal<Set<string>>(new Set());

  readonly hasMusicRadio = computed(() => this.features.hasMusicRadio());

  readonly missingSeasons = computed(() => this.feed()?.missing_seasons ?? []);
  readonly authorBooks = computed(() => this.feed()?.recently_added_books ?? []);
  /** Most popular movies in the library, for "Popular In Your Library". */
  readonly popular = signal<TitleCard[]>([]);

  /**
   * The feed's spotlight (a missing-seasons show with a backdrop), or
   * failing that the first missing-seasons show without artwork.
   */
  readonly hero = computed<DiscoverHero | null>(() => {
    const feed = this.feed();
    if (!feed) return null;
    const seasons = feed.missing_seasons;
    const spot = feed.spotlight;
    if (spot) {
      const match = seasons.find(s => s.title_id === spot.title_id);
      return { title: spot, kicker: match ? this.missingKicker(match) : null };
    }
    const first = seasons[0];
    if (!first) return null;
    return {
      title: {
        title_id: first.title_id,
        title_name: first.title_name,
        description: null,
        backdrop_url: null,
        backdrop_tint: null,
        content_rating: null,
        release_year: null,
        playable: false,
        transcode_id: null,
      },
      kicker: this.missingKicker(first),
    };
  });

  async ngOnInit(): Promise<void> {
    void this.loadFeed();
    void this.loadPopular();
    if (!this.hasMusicRadio()) {
      this.loading.set(false);
      return;
    }
    await this.load();
  }

  private async loadFeed(): Promise<void> {
    try {
      this.feed.set(await this.catalog.getHomeFeed());
    } catch {
      this.feed.set(null);
    }
  }

  private async loadPopular(): Promise<void> {
    try {
      const resp = await this.catalog.getTitles({ mediaType: 'MOVIE', sort: 'popular', limit: 18 });
      this.popular.set(resp.titles.slice(0, 18));
    } catch {
      this.popular.set([]);
    }
  }

  private async load(): Promise<void> {
    try {
      const resp = await this.catalog.getArtistRecommendations(30);
      this.artists.set(resp.artists);
    } catch {
      this.error.set('Failed to load recommendations');
    } finally {
      this.loading.set(false);
    }
  }

  /** "Your shelf is missing Seasons 2 and 3". */
  missingKicker(item: MissingSeason): string | null {
    const nums = item.missing_seasons.map(s => s.season_number);
    if (nums.length === 0) return null;
    if (nums.length === 1) return `Your shelf is missing Season ${nums[0]}`;
    const head = nums.slice(0, -1).join(', ');
    return `Your shelf is missing Seasons ${head} and ${nums[nums.length - 1]}`;
  }

  /** "Season 4 available" / "Seasons 2, 3 available" for a New Seasons Out card. */
  seasonsAvailable(item: MissingSeason): string {
    const nums = item.missing_seasons.map(s => s.season_number);
    return `${nums.length === 1 ? 'Season' : 'Seasons'} ${nums.join(', ')} available`;
  }

  async refresh(): Promise<void> {
    this.refreshing.set(true);
    try {
      await this.catalog.refreshArtistRecommendations();
      // Server refresh runs async on a background thread; give it a
      // few seconds before re-reading the list.
      await new Promise(r => setTimeout(r, 4000));
      await this.load();
    } finally {
      this.refreshing.set(false);
    }
  }

  async dismiss(artist: ArtistRecommendation): Promise<void> {
    try {
      await this.catalog.dismissArtistRecommendation(artist.suggested_artist_mbid);
      this.artists.update(list =>
        list.filter(a => a.suggested_artist_mbid !== artist.suggested_artist_mbid));
    } catch {
      // Soft-fail; user can retry.
    }
  }

  async wishlist(artist: ArtistRecommendation): Promise<void> {
    const rgid = artist.representative_release_group_id;
    const title = artist.representative_release_title;
    if (!rgid || !title) return;
    const mbid = artist.suggested_artist_mbid;
    this.wishSubmitting.update(s => new Set(s).add(mbid));
    try {
      await this.catalog.addAlbumWish({
        release_group_id: rgid,
        title,
        primary_artist: artist.suggested_artist_name,
        year: null,
        cover_release_id: null,
        is_compilation: false,
      });
      this.wishedMbids.update(s => new Set(s).add(mbid));
    } catch {
      // Soft-fail; leave the card alone so retry is obvious.
    } finally {
      this.wishSubmitting.update(s => {
        const next = new Set(s);
        next.delete(mbid);
        return next;
      });
    }
  }

  /** The two meta lines of an artist card: the album to start with, then the voters. */
  artistLines(artist: ArtistRecommendation): string[] {
    const lines: string[] = [];
    if (artist.representative_release_title) lines.push(`Start with ${artist.representative_release_title}`);
    lines.push(this.voterLine(artist));
    return lines;
  }

  /** "because you have X, Y, and Z" — format top voter names. */
  voterLine(artist: ArtistRecommendation): string {
    const names = artist.voters.slice(0, 3).map(v => v.name);
    if (names.length === 0) return 'because you own similar artists';
    if (names.length === 1) return `because you have ${names[0]}`;
    if (names.length === 2) return `because you have ${names[0]} and ${names[1]}`;
    return `because you have ${names[0]}, ${names[1]}, and ${names[2]}`;
  }

  isWishSubmitting(mbid: string): boolean {
    return this.wishSubmitting().has(mbid);
  }

  isWished(mbid: string): boolean {
    return this.wishedMbids().has(mbid);
  }
}
