import { Component, inject, signal, computed, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { firstValueFrom } from 'rxjs';

interface LinkedRow {
  transcode_id: number;
  title_id: number;
  title_name: string;
  media_type: string;
  format: string | null;
  file_name: string | null;
  file_path: string | null;
  poster_url: string | null;
  playable: boolean;
  retranscode_requested: boolean;
}

@Component({
  selector: 'app-transcode-linked',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatIconModule, MatProgressSpinnerModule, MatTableModule, MatPaginatorModule, MatButtonModule, MatChipsModule],
  templateUrl: './transcode-linked.html',
  styleUrl: './transcode-linked.scss',
})
export class TranscodeLinkedComponent implements OnInit {
  private readonly http = inject(HttpClient);

  readonly loading = signal(true);
  readonly rows = signal<LinkedRow[]>([]);
  readonly total = signal(0);

  readonly search = signal('');
  readonly formatFilter = signal('');
  readonly typeFilter = signal('');
  readonly sortMode = signal('name');
  readonly page = signal(0);
  readonly pageSize = signal(50);

  readonly columns = ['poster', 'title', 'type', 'format', 'file', 'actions'];

  readonly sortOptions = [
    { value: 'name', label: 'Name' },
    { value: 'format', label: 'Format' },
    { value: 'type', label: 'Type' },
  ];

  readonly formatOptions = ['', 'BLURAY', 'DVD', 'UHD_BLURAY'];
  readonly typeOptions = ['', 'MOVIE', 'TV'];

  async ngOnInit(): Promise<void> {
    await this.refresh();
  }

  async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      const params: Record<string, string> = {
        page: this.page().toString(),
        size: this.pageSize().toString(),
        sort: this.sortMode(),
      };
      const s = this.search().trim();
      if (s) params['search'] = s;
      if (this.formatFilter()) params['format'] = this.formatFilter();
      if (this.typeFilter()) params['type'] = this.typeFilter();

      const data = await firstValueFrom(this.http.get<{ rows: LinkedRow[]; total: number }>(
        '/api/v2/admin/linked-transcodes', { params }
      ));
      this.rows.set(data.rows);
      this.total.set(data.total);
    } catch { /* ignore */ }
    this.loading.set(false);
  }

  async onSearchInput(event: Event): Promise<void> {
    this.search.set((event.target as HTMLInputElement).value);
    this.page.set(0);
    await this.refresh();
  }

  async setSort(mode: string): Promise<void> {
    this.sortMode.set(mode);
    this.page.set(0);
    await this.refresh();
  }

  async setFormat(f: string): Promise<void> {
    this.formatFilter.set(this.formatFilter() === f ? '' : f);
    this.page.set(0);
    await this.refresh();
  }

  async setType(t: string): Promise<void> {
    this.typeFilter.set(this.typeFilter() === t ? '' : t);
    this.page.set(0);
    await this.refresh();
  }

  async onPage(event: PageEvent): Promise<void> {
    this.page.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
    await this.refresh();
  }

  async confirmRetranscode(row: LinkedRow): Promise<void> {
    const confirmed = confirm(
      `Request re-transcode for "${row.title_name}"?\n\n` +
      `This is only necessary if you notice quality problems in the video ` +
      `(artifacts, audio sync issues, etc.) that you believe are not present ` +
      `in the original disc rip. The file will be re-transcoded from the ` +
      `source on the NAS, which may take some time.`
    );
    if (!confirmed) return;
    await firstValueFrom(this.http.post(`/api/v2/admin/linked-transcodes/${row.transcode_id}/retranscode`, {}));
    await this.refresh();
  }

  async unlink(row: LinkedRow): Promise<void> {
    await firstValueFrom(this.http.delete(`/api/v2/admin/linked-transcodes/${row.transcode_id}`));
    await this.refresh();
  }

  formatLabel(f: string): string {
    switch (f) {
      case 'UHD_BLURAY': return '4K UHD';
      case 'BLURAY': return 'Blu-ray';
      case 'DVD': return 'DVD';
      default: return f;
    }
  }
}
