import { Component, inject, signal, computed, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CatalogService, ArtistDetail, ArtistOtherWork } from '../../core/catalog.service';
import { AppRoutes } from '../../core/routes';
import { WishInterstitialService } from '../../core/wish-interstitial.service';

@Component({
  selector: 'app-artist',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatIconModule, MatProgressSpinnerModule],
  templateUrl: './artist.html',
  styleUrl: './artist.scss',
})
export class ArtistComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly catalog = inject(CatalogService);
  private readonly wishInterstitial = inject(WishInterstitialService);
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly error = signal('');
  readonly artist = signal<ArtistDetail | null>(null);
  readonly bioExpanded = signal(false);

  // When no headshot is cached (ArtistEnrichmentAgent hasn't reached this
  // artist yet, or Wikipedia has no thumbnail), use the first owned album
  // cover as a visual fallback. The small icon-only placeholder orphans a
  // 200px tall box next to an empty bio column; an album cover gives the
  // page an identity and matches the albums grid immediately below.
  readonly heroFallbackUrl = computed<string | null>(() => {
    const a = this.artist();
    if (!a || a.headshot_url) return null;
    return a.owned_albums?.[0]?.poster_url ?? null;
  });

  async ngOnInit(): Promise<void> {
    const id = Number(this.route.snapshot.paramMap.get('artistId'));
    if (!id) {
      this.error.set('Invalid artist ID');
      this.loading.set(false);
      return;
    }
    try {
      this.artist.set(await this.catalog.getArtistDetail(id));
    } catch {
      this.error.set('Failed to load artist details');
    } finally {
      this.loading.set(false);
    }
  }

  /**
   * Tenure label for a membership row. "1965 – 1971", "Since 1965", or
   * blank when MB has neither date. Drives both band-members and member-of
   * lists.
   */
  formatTenure(begin: string | null, end: string | null): string {
    const b = begin?.substring(0, 4);
    const e = end?.substring(0, 4);
    if (b && e) return `${b} \u2013 ${e}`;
    if (b) return `Since ${b}`;
    if (e) return `until ${e}`;
    return '';
  }

  /**
   * Toggles the heart icon on an Other-Works entry. Uses the existing
   * wish-interstitial confirmation so new wishlisters see the "visible
   * to admins" disclosure once per session. Compilation shape propagates
   * to the wish row so the Wishlist page renders title-first.
   */
  async toggleWish(work: ArtistOtherWork): Promise<void> {
    const a = this.artist();
    if (!a) return;

    if (work.already_wished) {
      await this.catalog.removeAlbumWish(work.release_group_id);
      work.already_wished = false;
    } else {
      if (await this.wishInterstitial.needsInterstitial()) {
        if (!confirm('Your wish list entries are visible to administrators to help inform purchase decisions. Continue?')) return;
        this.wishInterstitial.acknowledge();
      }
      await this.catalog.addAlbumWish({
        release_group_id: work.release_group_id,
        title: work.title,
        // For compilation-shaped entries we record "Various Artists" as the
        // display string, per docs/MUSIC.md Q9. Otherwise the current
        // artist's name is the primary credit.
        primary_artist: work.is_compilation ? 'Various Artists' : a.name,
        year: work.year,
        cover_release_id: null,
        is_compilation: work.is_compilation,
      });
      work.already_wished = true;
    }
    this.artist.update(v => v ? { ...v } : v);
  }

  formatLifespan(): string {
    const a = this.artist();
    if (!a?.begin_date) return '';
    const begin = a.begin_date.substring(0, 4);
    const end = a.end_date?.substring(0, 4);
    const isIndividual = a.artist_type === 'PERSON';
    if (isIndividual) {
      return end ? `${begin} \u2013 ${end}` : `Born ${begin}`;
    }
    return end ? `${begin} \u2013 ${end}` : `${begin} \u2013 present`;
  }
}
