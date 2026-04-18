import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CatalogService, ArtistDetail } from '../../core/catalog.service';
import { AppRoutes } from '../../core/routes';

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
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly error = signal('');
  readonly artist = signal<ArtistDetail | null>(null);
  readonly bioExpanded = signal(false);

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
   * Bands: formation year, breakup year if disbanded. Individuals: birth / death.
   * Renders "1965 – 1973" for a disbanded group, "1965 – present" for an active
   * group, "Born 1949" for an individual, etc. Date strings are ISO.
   */
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
