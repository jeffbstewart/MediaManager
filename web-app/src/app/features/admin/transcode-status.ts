import { Component, inject, signal, OnInit, OnDestroy, ChangeDetectionStrategy } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatCardModule } from '@angular/material/card';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom } from 'rxjs';

interface TranscodeStatus {
  local_disabled: boolean;
  local_status: { active: boolean; file_name?: string; progress_percent?: number; lease_type?: string };
  overall: {
    total_completed: number;
    total_bytes: string;
    pending: { transcodes: number; mobile: number; thumbnails: number; subtitles: number; chapters: number; total: number };
    throughput: { transcode_rate: number; mobile_rate: number; thumbnail_rate: number; subtitle_rate: number; chapter_rate: number; bytes_per_hour: string };
    eta_seconds: number | null;
    active_workers: number;
    failed_count: number;
    thumbnail_stats: { total: number; today: number };
    subtitle_stats: { total: number; today: number };
    chapter_stats: { total: number; today: number };
  };
  active_buddies: ActiveBuddy[];
  recent: RecentLease[];
}

interface ActiveBuddy {
  id: number;
  buddy_name: string;
  file_name: string;
  relative_path: string;
  lease_type: string;
  status: string;
  progress_percent: number;
  encoder: string | null;
  elapsed_seconds: number;
}

interface RecentLease {
  id: number;
  buddy_name: string;
  file_name: string;
  lease_type: string;
  status: string;
  file_size_bytes: number | null;
  elapsed_seconds: number | null;
  error_message: string | null;
  completed_at: string | null;
}

@Component({
  selector: 'app-transcode-status',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DecimalPipe, MatIconModule, MatProgressSpinnerModule, MatProgressBarModule, MatCardModule, MatTableModule, MatButtonModule],
  templateUrl: './transcode-status.html',
  styleUrl: './transcode-status.scss',
})
export class TranscodeStatusComponent implements OnInit, OnDestroy {
  private readonly http = inject(HttpClient);

  readonly loading = signal(true);
  readonly data = signal<TranscodeStatus | null>(null);
  readonly scanning = signal(false);
  private refreshTimer: ReturnType<typeof setInterval> | null = null;

  readonly recentColumns = ['status', 'buddy', 'type', 'file', 'elapsed', 'size', 'completed'];

  async ngOnInit(): Promise<void> {
    await this.refresh();
    this.refreshTimer = setInterval(() => this.refresh(), 15000);
  }

  ngOnDestroy(): void {
    if (this.refreshTimer) clearInterval(this.refreshTimer);
  }

  async refresh(): Promise<void> {
    try {
      const status = await firstValueFrom(this.http.get<TranscodeStatus>('/api/v2/admin/transcode-status'));
      this.data.set(status);
    } catch { /* ignore */ }
    this.loading.set(false);
  }

  async scanNas(): Promise<void> {
    this.scanning.set(true);
    try {
      await firstValueFrom(this.http.post('/api/v2/admin/transcode-status/scan', {}));
    } catch { /* ignore */ }
    // Scan runs async on server; refresh will pick up changes
    setTimeout(() => { this.scanning.set(false); this.refresh(); }, 5000);
  }

  async clearFailures(): Promise<void> {
    await firstValueFrom(this.http.post('/api/v2/admin/transcode-status/clear-failures', {}));
    await this.refresh();
  }

  formatEta(seconds: number): string {
    if (seconds < 3600) return `${Math.round(seconds / 60)}m`;
    if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ${Math.round((seconds % 3600) / 60)}m`;
    const days = Math.floor(seconds / 86400);
    const hours = Math.round((seconds % 86400) / 3600);
    return `${days}d ${hours}h`;
  }

  formatElapsed(seconds: number): string {
    if (seconds < 60) return `${seconds}s`;
    if (seconds < 3600) return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
    return `${Math.floor(seconds / 3600)}h ${Math.floor((seconds % 3600) / 60)}m`;
  }

  formatSize(bytes: number): string {
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
  }

  formatTimestamp(ts: string | null): string {
    if (!ts) return '—';
    const d = new Date(ts);
    return d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true });
  }

  leaseTypeLabel(type: string): string {
    switch (type) {
      case 'TRANSCODE': return 'Transcode';
      case 'MOBILE_TRANSCODE': return 'Mobile';
      case 'THUMBNAILS': return 'Thumbs';
      case 'SUBTITLES': return 'Subs';
      case 'CHAPTERS': return 'Chapters';
      default: return type;
    }
  }
}
