import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CatalogService, AuthorDetail, AuthorOtherWork } from '../../core/catalog.service';
import { AppRoutes } from '../../core/routes';
import { WishInterstitialService } from '../../core/wish-interstitial.service';

@Component({
  selector: 'app-author',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatIconModule, MatProgressSpinnerModule],
  templateUrl: './author.html',
  styleUrl: './author.scss',
})
export class AuthorComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly catalog = inject(CatalogService);
  private readonly wishInterstitial = inject(WishInterstitialService);
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly error = signal('');
  readonly author = signal<AuthorDetail | null>(null);
  readonly bioExpanded = signal(false);

  async ngOnInit(): Promise<void> {
    const id = Number(this.route.snapshot.paramMap.get('authorId'));
    if (!id) {
      this.error.set('Invalid author ID');
      this.loading.set(false);
      return;
    }
    try {
      this.author.set(await this.catalog.getAuthorDetail(id));
    } catch {
      this.error.set('Failed to load author details');
    } finally {
      this.loading.set(false);
    }
  }

  formatLifespan(): string {
    const a = this.author();
    if (!a?.birth_date) return '';
    if (a.death_date) return `${a.birth_date} \u2013 ${a.death_date}`;
    return `Born ${a.birth_date}`;
  }

  async toggleWish(work: AuthorOtherWork): Promise<void> {
    const a = this.author();
    if (!a) return;
    if (work.already_wished) {
      await this.catalog.removeBookWish(work.ol_work_id);
      work.already_wished = false;
    } else {
      if (await this.wishInterstitial.needsInterstitial()) {
        if (!confirm('Your wish list entries are visible to administrators to help inform purchase decisions. Continue?')) return;
        this.wishInterstitial.acknowledge();
      }
      await this.catalog.addBookWish({
        ol_work_id: work.ol_work_id,
        title: work.title,
        author: a.name,
      });
      work.already_wished = true;
    }
    this.author.update(v => v ? { ...v } : v);
  }
}
