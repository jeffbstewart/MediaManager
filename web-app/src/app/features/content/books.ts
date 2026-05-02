import { Component, ChangeDetectionStrategy, OnInit, signal, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CatalogService, AuthorsListItem, AuthorsSortMode } from '../../core/catalog.service';
import { AppRoutes } from '../../core/routes';

/**
 * Books landing page — author exploration grid. Replaces the original
 * book-title grid because the title list grew unwieldy as the catalog
 * scaled. Drilling through author → existing author detail page (which
 * already lists owned books grouped by series + an OpenLibrary
 * "other works" wishlist) keeps the book view one click away.
 *
 * Mirrors MusicComponent's artist-grid; the per-card hero is the
 * cached author headshot when available, with an icon placeholder
 * otherwise.
 */
@Component({
  selector: 'app-books',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, FormsModule, MatChipsModule, MatIconModule, MatProgressSpinnerModule],
  templateUrl: './books.html',
  styleUrl: './books.scss',
})
export class BooksComponent implements OnInit {
  private readonly catalog = inject(CatalogService);
  readonly routes = AppRoutes;

  readonly loading = signal(true);
  readonly error = signal('');
  readonly authors = signal<AuthorsListItem[]>([]);
  readonly total = signal(0);

  readonly sortMode = signal<AuthorsSortMode>('books');
  readonly query = signal('');

  readonly sortOptions: { value: AuthorsSortMode; label: string }[] = [
    { value: 'books', label: 'Books' },
    { value: 'name', label: 'Name' },
    { value: 'recent', label: 'Recent' },
  ];

  // Same 200ms debounce window as the artist search — short enough to
  // feel snappy, long enough to absorb mid-word typing.
  private searchTimer: ReturnType<typeof setTimeout> | null = null;

  async ngOnInit(): Promise<void> {
    await this.refresh();
  }

  async setSort(mode: AuthorsSortMode): Promise<void> {
    this.sortMode.set(mode);
    await this.refresh();
  }

  onQueryInput(value: string): void {
    this.query.set(value);
    if (this.searchTimer) clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => { void this.refresh(); }, 200);
  }

  async clearQuery(): Promise<void> {
    if (this.searchTimer) { clearTimeout(this.searchTimer); this.searchTimer = null; }
    this.query.set('');
    await this.refresh();
  }

  private async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      const data = await this.catalog.listAuthors({
        sort: this.sortMode(),
        q: this.query().trim() || undefined,
      });
      this.authors.set(data.authors);
      this.total.set(data.total);
    } catch {
      this.error.set('Failed to load authors');
    } finally {
      this.loading.set(false);
    }
  }
}
