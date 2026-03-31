import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { firstValueFrom } from 'rxjs';

interface UnmatchedFile {
  id: number;
  file_name: string;
  file_path: string;
  directory: string;
  media_type: string | null;
  is_personal: boolean;
  parsed_title: string | null;
  parsed_year: number | null;
  parsed_season: number | null;
  parsed_episode: number | null;
  suggestion: { title_id: number; title_name: string; score: number } | null;
}

interface SearchTitle {
  id: number;
  name: string;
  media_type: string;
  release_year: number | null;
}

@Component({
  selector: 'app-transcode-unmatched',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule, MatProgressSpinnerModule, MatTableModule, MatButtonModule, MatCardModule, MatChipsModule],
  templateUrl: './transcode-unmatched.html',
  styleUrl: './transcode-unmatched.scss',
})
export class TranscodeUnmatchedComponent implements OnInit {
  private readonly http = inject(HttpClient);

  readonly loading = signal(true);
  readonly files = signal<UnmatchedFile[]>([]);
  readonly total = signal(0);

  // Link dialog state
  readonly linkDialogOpen = signal(false);
  readonly linkFile = signal<UnmatchedFile | null>(null);
  readonly linkQuery = signal('');
  readonly linkSearching = signal(false);
  readonly linkResults = signal<SearchTitle[]>([]);

  // TMDB search in link dialog
  readonly tmdbQuery = signal('');
  readonly tmdbType = signal<'MOVIE' | 'TV'>('MOVIE');
  readonly tmdbResults = signal<{ tmdb_id: number; title: string; media_type: string; release_year: number | null; poster_path: string | null; overview: string | null }[]>([]);
  readonly tmdbSearching = signal(false);

  readonly columns = ['file', 'directory', 'parsed', 'year', 'suggestion', 'actions'];

  async ngOnInit(): Promise<void> {
    await this.refresh();
  }

  async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      const data = await firstValueFrom(this.http.get<{ files: UnmatchedFile[]; total: number }>('/api/v2/admin/unmatched'));
      this.files.set(data.files);
      this.total.set(data.total);
    } catch { /* ignore */ }
    this.loading.set(false);
  }

  async accept(file: UnmatchedFile): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/admin/unmatched/${file.id}/accept`, {}));
    this.files.update(f => f.filter(x => x.id !== file.id));
    this.total.update(n => n - 1);
  }

  async ignore(file: UnmatchedFile): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/admin/unmatched/${file.id}/ignore`, {}));
    this.files.update(f => f.filter(x => x.id !== file.id));
    this.total.update(n => n - 1);
  }

  openLinkDialog(file: UnmatchedFile): void {
    this.linkFile.set(file);
    this.linkQuery.set(file.parsed_title ?? '');
    this.linkResults.set([]);
    this.tmdbQuery.set(file.parsed_title ?? '');
    this.tmdbResults.set([]);
    this.linkDialogOpen.set(true);
    if (file.parsed_title) this.searchTitles(file.parsed_title);
  }

  closeLinkDialog(): void {
    this.linkDialogOpen.set(false);
    this.linkFile.set(null);
  }

  updateLinkQuery(event: Event): void {
    const q = (event.target as HTMLInputElement).value;
    this.linkQuery.set(q);
    if (q.trim().length >= 2) this.searchTitles(q.trim());
  }

  async searchTitles(q: string): Promise<void> {
    this.linkSearching.set(true);
    try {
      const data = await firstValueFrom(this.http.get<{ titles: SearchTitle[] }>('/api/v2/admin/unmatched/search-titles', { params: { q } }));
      this.linkResults.set(data.titles);
    } catch { /* ignore */ }
    this.linkSearching.set(false);
  }

  async linkToTitle(title: SearchTitle): Promise<void> {
    const file = this.linkFile();
    if (!file) return;
    await firstValueFrom(this.http.post(`/api/v2/admin/unmatched/${file.id}/link/${title.id}`, {}));
    this.files.update(f => f.filter(x => x.id !== file.id));
    this.total.update(n => n - 1);
    this.closeLinkDialog();
  }

  async createPersonal(file: UnmatchedFile): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/admin/unmatched/${file.id}/create-personal`, {}));
    this.files.update(f => f.filter(x => x.id !== file.id));
    this.total.update(n => n - 1);
  }

  async searchTmdb(): Promise<void> {
    const q = this.tmdbQuery().trim();
    if (!q) return;
    this.tmdbSearching.set(true);
    try {
      const d = await firstValueFrom(this.http.get<{ results: { tmdb_id: number; title: string; media_type: string; release_year: number | null; poster_path: string | null; overview: string | null }[] }>(
        '/api/v2/admin/media-item/search-tmdb', { params: { q, type: this.tmdbType() } }));
      this.tmdbResults.set(d.results);
    } catch { /* ignore */ }
    this.tmdbSearching.set(false);
  }

  async addFromTmdb(result: { tmdb_id: number; media_type: string }): Promise<void> {
    const file = this.linkFile();
    if (!file) return;
    await firstValueFrom(this.http.post(`/api/v2/admin/unmatched/${file.id}/add-from-tmdb`, {
      tmdb_id: result.tmdb_id, media_type: result.media_type
    }));
    this.files.update(f => f.filter(x => x.id !== file.id));
    this.total.update(n => n - 1);
    this.closeLinkDialog();
  }

  posterUrl(path: string): string { return `https://image.tmdb.org/t/p/w92${path}`; }
}
