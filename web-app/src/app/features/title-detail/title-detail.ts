import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDividerModule } from '@angular/material/divider';
import { MatTabsModule } from '@angular/material/tabs';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { AppRoutes } from '../../core/routes';
import { CatalogService, TitleDetail } from '../../core/catalog.service';
import { FeatureService } from '../../core/feature.service';

@Component({
  selector: 'app-title-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    MatChipsModule,
    MatIconModule,
    MatButtonModule,
    MatProgressSpinnerModule,
    MatDividerModule,
    MatTabsModule,
  ],
  templateUrl: './title-detail.html',
  styleUrl: './title-detail.scss',
})
export class TitleDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly catalog = inject(CatalogService);
  private readonly http = inject(HttpClient);
  readonly features = inject(FeatureService);

  readonly routes = AppRoutes;
  readonly loading = signal(true);
  readonly error = signal('');
  readonly title = signal<TitleDetail | null>(null);
  readonly selectedSeason = signal<number | null>(null);

  get isPersonal(): boolean { return this.title()?.media_type === 'PERSONAL'; }
  get isTv(): boolean { return this.title()?.media_type === 'TV'; }

  /** The transcode with saved progress (for resume button). */
  get resumeTranscode() {
    const t = this.title();
    if (!t) return null;
    const withProgress = t.transcodes.filter(tc => tc.position_seconds && tc.position_seconds > 10);
    if (withProgress.length === 0) return null;
    return withProgress[0];
  }

  resumeLabel(tc: { position_seconds?: number; season_number?: number; episode_number?: number; episode_name?: string }): string {
    const parts: string[] = [];
    if (tc.season_number != null && tc.episode_number != null) {
      parts.push(`S${String(tc.season_number).padStart(2, '0')}E${String(tc.episode_number).padStart(2, '0')}`);
      if (tc.episode_name) parts.push(tc.episode_name);
      parts.push('·');
    }
    if (tc.position_seconds) {
      parts.push(`Resume from ${this.formatResume(tc.position_seconds, null)}`);
    }
    return parts.join(' ');
  }

  get availableSeasons(): number[] {
    const t = this.title();
    if (!t) return [];
    const seasons = [...new Set(t.episodes.map(e => e.season_number))].sort((a, b) => a - b);
    return seasons;
  }

  get filteredEpisodes() {
    const t = this.title();
    const season = this.selectedSeason();
    if (!t || season === null) return [];
    return t.episodes.filter(e => e.season_number === season);
  }

  seasonLabel(n: number): string {
    return n === 0 ? 'Special Features' : `Season ${n}`;
  }

  formatResume(posSeconds: number | undefined | null, durSeconds: number | undefined | null): string | null {
    if (!posSeconds || posSeconds < 10) return null;
    const min = Math.floor(posSeconds / 60);
    const sec = Math.floor(posSeconds % 60);
    return `Resume from ${min}:${sec.toString().padStart(2, '0')}`;
  }

  async ngOnInit(): Promise<void> {
    const titleId = Number(this.route.snapshot.paramMap.get('titleId'));
    if (!titleId) {
      this.error.set('Invalid title ID');
      this.loading.set(false);
      return;
    }

    try {
      const data = await this.catalog.getTitleDetail(titleId);
      this.title.set(data);
      if (data.media_type === 'TV' && data.episodes.length > 0) {
        const seasons = [...new Set(data.episodes.map(e => e.season_number))].sort((a, b) => a - b);
        this.selectedSeason.set(seasons.includes(1) ? 1 : seasons[0]);
      }
    } catch {
      this.error.set('Failed to load title');
    } finally {
      this.loading.set(false);
    }
  }

  selectSeason(n: number): void {
    this.selectedSeason.set(n);
  }

  async toggleStar(): Promise<void> {
    const t = this.title();
    if (!t) return;
    const r = await firstValueFrom(this.http.post<{ is_starred: boolean }>(
      `/api/v2/catalog/titles/${t.title_id}/star`, {}));
    this.title.set({ ...t, is_starred: r.is_starred });
  }

  async toggleHide(): Promise<void> {
    const t = this.title();
    if (!t) return;
    const r = await firstValueFrom(this.http.post<{ is_hidden: boolean }>(
      `/api/v2/catalog/titles/${t.title_id}/hide`, {}));
    this.title.set({ ...t, is_hidden: r.is_hidden });
  }

  formatLabel(f: string): string {
    switch (f) {
      case 'BLURAY': return 'Blu-ray';
      case 'UHD_BLURAY': return '4K UHD';
      case 'HD_DVD': return 'HD DVD';
      default: return f;
    }
  }
}
