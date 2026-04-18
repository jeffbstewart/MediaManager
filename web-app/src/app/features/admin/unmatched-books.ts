import { Component, ElementRef, inject, signal, OnInit, ChangeDetectionStrategy, viewChild } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { firstValueFrom } from 'rxjs';

interface UnmatchedBook {
  id: number;
  file_path: string;
  file_name: string;
  file_size_bytes: number | null;
  media_format: string;
  parsed_title: string | null;
  parsed_author: string | null;
  parsed_isbn: string | null;
  discovered_at: string | null;
}

interface BookTitle {
  id: number;
  name: string;
  release_year: number | null;
}

interface OlSearchResult {
  work_id: string;
  title: string;
  authors: string[];
  year: number | null;
  cover_url: string | null;
  isbn: string | null;
}

@Component({
  selector: 'app-unmatched-books',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule, MatProgressSpinnerModule, MatTableModule, MatButtonModule, MatCardModule],
  templateUrl: './unmatched-books.html',
  styleUrl: './unmatched-books.scss',
})
export class UnmatchedBooksComponent implements OnInit {
  private readonly http = inject(HttpClient);

  readonly loading = signal(true);
  readonly files = signal<UnmatchedBook[]>([]);
  readonly total = signal(0);

  // The native <dialog> ref. Using <dialog>.showModal() lets the browser
  // render the modal in the top layer, escaping mat-sidenav-content's
  // transform (which would otherwise bound a position:fixed overlay and
  // leave it tucked behind the open side drawer).
  readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('linkDialog');

  // Link dialog state — the dialog covers ISBN re-lookup, OL full-text
  // search, and picking an existing book title. Admins should not need to
  // open a different UI to choose between these.
  readonly linkFile = signal<UnmatchedBook | null>(null);
  readonly linkError = signal<string | null>(null);
  readonly linkBusy = signal(false);

  readonly isbnInput = signal('');

  readonly olQuery = signal('');
  readonly olSearching = signal(false);
  readonly olResults = signal<OlSearchResult[]>([]);

  readonly titleQuery = signal('');
  readonly titleSearching = signal(false);
  readonly titleResults = signal<BookTitle[]>([]);

  readonly columns = ['file', 'title', 'author', 'isbn', 'actions'];

  async ngOnInit(): Promise<void> {
    await this.refresh();
  }

  async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      const data = await firstValueFrom(
        this.http.get<{ files: UnmatchedBook[]; total: number }>('/api/v2/admin/unmatched-books')
      );
      this.files.set(data.files);
      this.total.set(data.total);
    } catch { /* ignore */ }
    this.loading.set(false);
  }

  async ignore(file: UnmatchedBook): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/admin/unmatched-books/${file.id}/ignore`, {}));
    this.files.update(f => f.filter(x => x.id !== file.id));
    this.total.update(n => n - 1);
  }

  openLinkDialog(file: UnmatchedBook): void {
    this.linkFile.set(file);
    this.linkError.set(null);
    this.isbnInput.set(file.parsed_isbn ?? '');
    this.olQuery.set(file.parsed_title ?? '');
    this.olResults.set([]);
    this.titleQuery.set(file.parsed_title ?? '');
    this.titleResults.set([]);
    this.dialogRef()?.nativeElement.showModal();
    if (file.parsed_title && file.parsed_title.length >= 2) {
      this.searchTitles(file.parsed_title);
      this.searchOpenLibrary(file.parsed_title);
    }
  }

  closeLinkDialog(): void {
    this.dialogRef()?.nativeElement.close();
    this.linkFile.set(null);
  }

  updateIsbn(event: Event): void {
    this.isbnInput.set((event.target as HTMLInputElement).value);
  }

  updateOlQuery(event: Event): void {
    const q = (event.target as HTMLInputElement).value;
    this.olQuery.set(q);
    if (q.trim().length >= 2) this.searchOpenLibrary(q.trim());
    else this.olResults.set([]);
  }

  async searchOpenLibrary(q: string): Promise<void> {
    this.olSearching.set(true);
    try {
      const data = await firstValueFrom(
        this.http.get<{ results: OlSearchResult[] }>(
          '/api/v2/admin/unmatched-books/search-ol', { params: { q } }
        )
      );
      this.olResults.set(data.results);
    } catch { /* ignore */ }
    this.olSearching.set(false);
  }

  async linkToOlHit(hit: OlSearchResult): Promise<void> {
    if (!hit.isbn) return;
    const file = this.linkFile();
    if (!file) return;
    this.linkBusy.set(true);
    this.linkError.set(null);
    try {
      const result = await firstValueFrom(
        this.http.post<{ ok: boolean; error?: string }>(
          `/api/v2/admin/unmatched-books/${file.id}/link-isbn`, { isbn: hit.isbn }
        )
      );
      if (!result.ok) {
        this.linkError.set(result.error ?? 'Link failed');
        return;
      }
      this.files.update(f => f.filter(x => x.id !== file.id));
      this.total.update(n => n - 1);
      this.closeLinkDialog();
    } catch {
      this.linkError.set('Link request failed');
    } finally {
      this.linkBusy.set(false);
    }
  }

  async submitIsbn(): Promise<void> {
    const file = this.linkFile();
    if (!file) return;
    const isbn = this.isbnInput().trim().replace(/-/g, '');
    if (!isbn) return;
    this.linkBusy.set(true);
    this.linkError.set(null);
    try {
      const result = await firstValueFrom(
        this.http.post<{ ok: boolean; error?: string }>(
          `/api/v2/admin/unmatched-books/${file.id}/link-isbn`, { isbn }
        )
      );
      if (!result.ok) {
        this.linkError.set(result.error ?? 'Link failed');
        return;
      }
      this.files.update(f => f.filter(x => x.id !== file.id));
      this.total.update(n => n - 1);
      this.closeLinkDialog();
    } catch {
      this.linkError.set('Link request failed');
    } finally {
      this.linkBusy.set(false);
    }
  }

  updateTitleQuery(event: Event): void {
    const q = (event.target as HTMLInputElement).value;
    this.titleQuery.set(q);
    if (q.trim().length >= 2) this.searchTitles(q.trim());
    else this.titleResults.set([]);
  }

  async searchTitles(q: string): Promise<void> {
    this.titleSearching.set(true);
    try {
      const data = await firstValueFrom(
        this.http.get<{ titles: BookTitle[] }>(
          '/api/v2/admin/unmatched-books/search-titles', { params: { q } }
        )
      );
      this.titleResults.set(data.titles);
    } catch { /* ignore */ }
    this.titleSearching.set(false);
  }

  async linkToTitle(title: BookTitle): Promise<void> {
    const file = this.linkFile();
    if (!file) return;
    this.linkBusy.set(true);
    this.linkError.set(null);
    try {
      await firstValueFrom(
        this.http.post(`/api/v2/admin/unmatched-books/${file.id}/link-title`, { title_id: title.id })
      );
      this.files.update(f => f.filter(x => x.id !== file.id));
      this.total.update(n => n - 1);
      this.closeLinkDialog();
    } catch {
      this.linkError.set('Link request failed');
    } finally {
      this.linkBusy.set(false);
    }
  }
}
