import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CatalogService, ActorDetail, ActorCredit } from '../../core/catalog.service';
import { WishInterstitialService } from '../../core/wish-interstitial.service';
import { AppRoutes } from '../../core/routes';

@Component({
  selector: 'app-actor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatIconModule, MatProgressSpinnerModule],
  templateUrl: './actor.html',
  styleUrl: './actor.scss',
})
export class ActorComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly catalog = inject(CatalogService);
  private readonly wishInterstitial = inject(WishInterstitialService);
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly error = signal('');
  readonly actor = signal<ActorDetail | null>(null);
  readonly bioExpanded = signal(false);

  async ngOnInit(): Promise<void> {
    const id = Number(this.route.snapshot.paramMap.get('personId'));
    if (!id) {
      this.error.set('Invalid person ID');
      this.loading.set(false);
      return;
    }
    try {
      this.actor.set(await this.catalog.getActorDetail(id));
    } catch {
      this.error.set('Failed to load actor details');
    } finally {
      this.loading.set(false);
    }
  }

  async toggleWish(credit: ActorCredit): Promise<void> {
    if (credit.already_wished) return;
    if (await this.wishInterstitial.needsInterstitial()) {
      if (!confirm('Your media wish list entries are visible to administrators to help inform media purchase decisions. Continue?')) return;
      this.wishInterstitial.acknowledge();
    }
    await this.catalog.addMediaWish({
      tmdb_id: credit.tmdb_id,
      title: credit.title,
      media_type: credit.media_type,
      poster_path: credit.poster_path,
      release_year: credit.release_year,
      popularity: credit.popularity,
    });
    credit.already_wished = true;
    this.actor.update(a => a ? { ...a } : a);
  }

  profileImgUrl(path: string): string {
    return `https://image.tmdb.org/t/p/w185${path}`;
  }

  posterUrl(path: string): string {
    return `https://image.tmdb.org/t/p/w185${path}`;
  }

  formatLifespan(): string {
    const a = this.actor();
    if (!a?.birthday) return '';
    const born = a.birthday;
    if (a.deathday) return `${born} \u2013 ${a.deathday}`;
    return `Born ${born}`;
  }
}
