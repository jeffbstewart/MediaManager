import { Component, ElementRef, inject, signal, OnInit, ChangeDetectionStrategy, viewChild } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { firstValueFrom } from 'rxjs';

interface UnmatchedAudio {
  id: number;
  file_path: string;
  file_name: string;
  file_size_bytes: number | null;
  media_format: string;
  parsed_title: string | null;
  parsed_album: string | null;
  parsed_album_artist: string | null;
  parsed_track_artist: string | null;
  parsed_track_number: number | null;
  parsed_disc_number: number | null;
  parsed_duration_seconds: number | null;
  parsed_mb_release_id: string | null;
  parsed_mb_recording_id: string | null;
  discovered_at: string | null;
}

interface TrackCandidate {
  track_id: number;
  track_name: string;
  disc_number: number;
  track_number: number;
  album_title_id: number;
  album_name: string;
  artist_name: string | null;
  already_linked: boolean;
}

@Component({
  selector: 'app-unmatched-audio',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatIconModule, MatProgressSpinnerModule, MatTableModule, MatButtonModule, MatCardModule],
  templateUrl: './unmatched-audio.html',
  styleUrl: './unmatched-audio.scss',
})
export class UnmatchedAudioComponent implements OnInit {
  private readonly http = inject(HttpClient);

  readonly loading = signal(true);
  readonly files = signal<UnmatchedAudio[]>([]);
  readonly total = signal(0);

  // Native <dialog> — renders in the browser's top layer (avoids
  // mat-sidenav-content transform clipping). Same pattern as Unmatched Books.
  readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('linkDialog');

  readonly linkFile = signal<UnmatchedAudio | null>(null);
  readonly linkError = signal<string | null>(null);
  readonly linkBusy = signal(false);

  readonly trackQuery = signal('');
  readonly trackSearching = signal(false);
  readonly trackResults = signal<TrackCandidate[]>([]);

  readonly columns = ['file', 'track', 'album', 'artist', 'duration', 'actions'];

  async ngOnInit(): Promise<void> {
    await this.refresh();
  }

  async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      const data = await firstValueFrom(
        this.http.get<{ files: UnmatchedAudio[]; total: number }>('/api/v2/admin/unmatched-audio')
      );
      this.files.set(data.files);
      this.total.set(data.total);
    } catch { /* ignore */ }
    this.loading.set(false);
  }

  async ignore(file: UnmatchedAudio): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/admin/unmatched-audio/${file.id}/ignore`, {}));
    this.files.update(f => f.filter(x => x.id !== file.id));
    this.total.update(n => n - 1);
  }

  openLinkDialog(file: UnmatchedAudio): void {
    this.linkFile.set(file);
    this.linkError.set(null);
    // Seed the search box with the best available guess — album name is
    // the most useful starting point since admins usually remember the album.
    const seed = file.parsed_album ?? file.parsed_title ?? '';
    this.trackQuery.set(seed);
    this.trackResults.set([]);
    this.dialogRef()?.nativeElement.showModal();
    if (seed.length >= 2) this.searchTracks(seed);
  }

  closeLinkDialog(): void {
    this.dialogRef()?.nativeElement.close();
    this.linkFile.set(null);
  }

  updateTrackQuery(event: Event): void {
    const q = (event.target as HTMLInputElement).value;
    this.trackQuery.set(q);
    if (q.trim().length >= 2) this.searchTracks(q.trim());
    else this.trackResults.set([]);
  }

  async searchTracks(q: string): Promise<void> {
    this.trackSearching.set(true);
    try {
      const data = await firstValueFrom(
        this.http.get<{ tracks: TrackCandidate[] }>(
          '/api/v2/admin/unmatched-audio/search-tracks', { params: { q } }
        )
      );
      this.trackResults.set(data.tracks);
    } catch { /* ignore */ }
    this.trackSearching.set(false);
  }

  async linkToTrack(candidate: TrackCandidate): Promise<void> {
    const file = this.linkFile();
    if (!file) return;
    if (candidate.already_linked) return;
    this.linkBusy.set(true);
    this.linkError.set(null);
    try {
      await firstValueFrom(
        this.http.post(
          `/api/v2/admin/unmatched-audio/${file.id}/link-track`,
          { track_id: candidate.track_id }
        )
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

  formatDuration(seconds: number | null): string {
    if (!seconds) return '—';
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return `${m}:${s.toString().padStart(2, '0')}`;
  }

  formatDiscTrack(disc: number | null, track: number | null): string {
    if (disc == null && track == null) return '—';
    if (disc == null || disc === 1) return track?.toString() ?? '—';
    return `${disc}.${track ?? '?'}`;
  }
}
