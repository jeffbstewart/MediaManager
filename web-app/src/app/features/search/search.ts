import { Component, inject, signal, OnInit, OnDestroy, ChangeDetectionStrategy } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CatalogService, SearchResult } from '../../core/catalog.service';
import { AppRoutes } from '../../core/routes';

@Component({
  selector: 'app-search',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatIconModule, MatProgressSpinnerModule],
  templateUrl: './search.html',
  styleUrl: './search.scss',
})
export class SearchComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly catalog = inject(CatalogService);
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly query = signal('');
  readonly results = signal<SearchResult[]>([]);

  private querySub?: Subscription;

  ngOnInit(): void {
    this.querySub = this.route.queryParamMap.subscribe(params => {
      const q = params.get('q') ?? '';
      this.query.set(q);
      if (q.trim().length >= 2) {
        this.doSearch(q);
      } else {
        this.loading.set(false);
        this.results.set([]);
      }
    });
  }

  ngOnDestroy(): void {
    this.querySub?.unsubscribe();
  }

  async doSearch(q: string): Promise<void> {
    this.loading.set(true);
    try {
      const data = await this.catalog.search(q.trim(), 100);
      this.results.set(data.results);
    } catch {
      this.results.set([]);
    } finally {
      this.loading.set(false);
    }
  }

  resultRoute(item: SearchResult): string {
    switch (item.type) {
      case 'movie': case 'tv': case 'personal': return `/title/${item.title_id}`;
      case 'actor': return `/actor/${item.person_id}`;
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
      case 'tv': return 'TV Show';
      case 'personal': return 'Family Video';
      case 'actor': return 'Actor';
      case 'collection': return 'Collection';
      case 'tag': return 'Tag';
      case 'channel': return 'TV Channel';
      case 'camera': return 'Camera';
      default: return type;
    }
  }
}
