import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AppRoutes } from '../../core/routes';
import {
  CatalogService,
  HomeFeed,
} from '../../core/catalog.service';

@Component({
  selector: 'app-home',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    MatProgressSpinnerModule,
  ],
  templateUrl: './home.html',
  styleUrl: './home.scss',
})
export class HomeComponent implements OnInit {
  private readonly catalog = inject(CatalogService);

  readonly routes = AppRoutes;
  readonly loading = signal(true);
  readonly feed = signal<HomeFeed | null>(null);
  readonly error = signal('');

  async ngOnInit(): Promise<void> {
    try {
      const data = await this.catalog.getHomeFeed();
      this.feed.set(data);
    } catch {
      this.error.set('Failed to load home feed');
    } finally {
      this.loading.set(false);
    }
  }
}
