import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import {
  ArtistRecommendation,
  CatalogService,
} from '../../core/catalog.service';
import { FeatureService } from '../../core/feature.service';
import { AppRoutes } from '../../core/routes';

/**
 * Library recommendations surface (M8 / docs/MUSIC.md). Reads the
 * pre-computed `/api/v2/recommendations/artists` payload and renders a
 * grid of unowned artists with voter-explanation lines and one-click
 * actions: Wishlist the representative album, or Dismiss.
 *
 * Gated on `has_music_radio` — the recommendation engine and radio
 * share the same Last.fm cache, so the flag doubling as the gate is
 * intentional. When the gate is off, the page shows an explainer.
 */
@Component({
  selector: 'app-discover',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatIconModule, MatButtonModule, MatProgressSpinnerModule],
  templateUrl: './discover.html',
  styleUrl: './discover.scss',
})
export class DiscoverComponent implements OnInit {
  private readonly catalog = inject(CatalogService);
  private readonly features = inject(FeatureService);
  private readonly http = inject(HttpClient);

  readonly routes = AppRoutes;
  readonly loading = signal(true);
  readonly refreshing = signal(false);
  readonly error = signal('');
  readonly artists = signal<ArtistRecommendation[]>([]);
  readonly wishSubmitting = signal<Set<string>>(new Set());
  readonly wishedMbids = signal<Set<string>>(new Set());

  readonly hasMusicRadio = computed(() => this.features.hasMusicRadio());

  async ngOnInit(): Promise<void> {
    if (!this.hasMusicRadio()) {
      this.loading.set(false);
      return;
    }
    await this.load();
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
      await firstValueFrom(this.http.post('/api/v2/wishlist/albums', {
        release_group_id: rgid,
        title,
        primary_artist: artist.suggested_artist_name,
      }));
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
