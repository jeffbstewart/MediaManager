import { Component, ElementRef, inject, signal, OnInit, ChangeDetectionStrategy, viewChild } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { firstValueFrom } from 'rxjs';

interface UnmatchedAudioFile {
  id: number;
  file_path: string;
  file_name: string;
  parsed_title: string | null;
  parsed_track_artist: string | null;
  parsed_track_number: number | null;
  parsed_disc_number: number | null;
  parsed_duration_seconds: number | null;
  parsed_mb_recording_id: string | null;
}

interface UnmatchedAudioGroup {
  group_id: string;
  dirs: string[];
  dominant_album: string | null;
  dominant_album_artist: string | null;
  dominant_upc: string | null;
  dominant_mb_release_id: string | null;
  dominant_label: string | null;
  dominant_catalog_number: string | null;
  disc_numbers: number[];
  total_files: number;
  recording_mbid_count: number;
  file_ids: number[];
  files: UnmatchedAudioFile[];
}

interface MusicBrainzCandidate {
  release_mbid: string;
  release_group_mbid: string | null;
  title: string;
  artist_credit: string;
  year: number | null;
  label: string | null;
  barcode: string | null;
  track_count: number;
  disc_count: number;
  accommodates_files: boolean;
  recording_mbid_coverage: number;
}

interface LinkResult {
  title_id: number;
  title_name: string;
  linked: number;
  failed: { file_path: string; reason: string }[];
}

/**
 * Admin triage for the unmatched-audio queue. The previous flat per-row
 * link dialog couldn't help when a whole album was missing from the
 * catalog (no Track rows to search against) — see docs/MUSIC.md M4
 * follow-on. This view collapses files into albums via
 * (album_artist, album), merges multi-disc sibling folders, and offers
 * three resolution paths per album:
 *   1. Find on MusicBrainz — search MB and pick a candidate release.
 *   2. Create from file metadata — derive title + tracks from tags only.
 *   3. Ignore all — when the files genuinely shouldn't be linked.
 */
@Component({
  selector: 'app-unmatched-audio',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule, MatIconModule, MatProgressSpinnerModule, MatButtonModule,
    MatCardModule, MatChipsModule
  ],
  templateUrl: './unmatched-audio.html',
  styleUrl: './unmatched-audio.scss',
})
export class UnmatchedAudioComponent implements OnInit {
  private readonly http = inject(HttpClient);

  readonly loading = signal(true);
  readonly groups = signal<UnmatchedAudioGroup[]>([]);
  readonly totalFiles = signal(0);
  readonly expanded = signal<Set<string>>(new Set());

  // MusicBrainz candidate dialog state
  readonly mbDialogRef = viewChild<ElementRef<HTMLDialogElement>>('mbDialog');
  readonly mbGroup = signal<UnmatchedAudioGroup | null>(null);
  readonly mbBusy = signal(false);
  readonly mbError = signal<string | null>(null);
  readonly mbQueryOverride = signal('');
  readonly mbSearchArtist = signal('');
  readonly mbSearchAlbum = signal('');
  readonly mbCandidates = signal<MusicBrainzCandidate[]>([]);

  // Result toast — set after a successful link, shown for a few seconds
  readonly lastResult = signal<LinkResult | null>(null);

  async ngOnInit(): Promise<void> {
    await this.refresh();
  }

  async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      const data = await firstValueFrom(
        this.http.get<{ groups: UnmatchedAudioGroup[]; total_groups: number; total_files: number }>(
          '/api/v2/admin/unmatched-audio/groups'
        )
      );
      this.groups.set(data.groups);
      this.totalFiles.set(data.total_files);
    } catch { /* ignore */ }
    this.loading.set(false);
  }

  toggleExpand(groupId: string): void {
    this.expanded.update(set => {
      const next = new Set(set);
      if (next.has(groupId)) next.delete(groupId); else next.add(groupId);
      return next;
    });
  }

  isExpanded(groupId: string): boolean {
    return this.expanded().has(groupId);
  }

  // ----- MusicBrainz search dialog -----

  async openMbDialog(group: UnmatchedAudioGroup): Promise<void> {
    this.mbGroup.set(group);
    this.mbError.set(null);
    this.mbQueryOverride.set('');
    this.mbCandidates.set([]);
    this.mbSearchArtist.set('');
    this.mbSearchAlbum.set('');
    this.mbDialogRef()?.nativeElement.showModal();
    await this.runMbSearch();
  }

  closeMbDialog(): void {
    this.mbDialogRef()?.nativeElement.close();
    this.mbGroup.set(null);
  }

  async runMbSearch(): Promise<void> {
    const group = this.mbGroup();
    if (!group) return;
    this.mbBusy.set(true);
    this.mbError.set(null);
    try {
      const body: Record<string, unknown> = { unmatched_audio_ids: group.file_ids };
      const override = this.mbQueryOverride().trim();
      if (override) body['query_override'] = override;
      const data = await firstValueFrom(
        this.http.post<{ search_artist: string; search_album: string; candidates: MusicBrainzCandidate[] }>(
          '/api/v2/admin/unmatched-audio/musicbrainz-search', body
        )
      );
      this.mbSearchArtist.set(data.search_artist);
      this.mbSearchAlbum.set(data.search_album);
      this.mbCandidates.set(data.candidates);
    } catch {
      this.mbError.set('MusicBrainz search failed');
    } finally {
      this.mbBusy.set(false);
    }
  }

  async pickCandidate(candidate: MusicBrainzCandidate): Promise<void> {
    const group = this.mbGroup();
    if (!group) return;
    this.mbBusy.set(true);
    this.mbError.set(null);
    try {
      const result = await firstValueFrom(
        this.http.post<LinkResult>(
          '/api/v2/admin/unmatched-audio/link-album-to-release',
          { unmatched_audio_ids: group.file_ids, release_mbid: candidate.release_mbid }
        )
      );
      this.lastResult.set(result);
      this.closeMbDialog();
      await this.refresh();
    } catch {
      this.mbError.set('Link request failed');
    } finally {
      this.mbBusy.set(false);
    }
  }

  // ----- Manual create -----

  async createFromTags(group: UnmatchedAudioGroup): Promise<void> {
    if (!confirm(`Create "${group.dominant_album ?? '(unnamed)'}" from file tags only? ` +
      `Track names + numbers + duration come from the files themselves; no MusicBrainz match.`)) return;
    try {
      const result = await firstValueFrom(
        this.http.post<LinkResult>(
          '/api/v2/admin/unmatched-audio/link-album-manual',
          { unmatched_audio_ids: group.file_ids }
        )
      );
      this.lastResult.set(result);
      await this.refresh();
    } catch {
      alert('Manual create failed');
    }
  }

  // ----- Ignore -----

  async ignoreGroup(group: UnmatchedAudioGroup): Promise<void> {
    if (!confirm(`Ignore all ${group.total_files} files in "${group.dominant_album ?? group.dirs[0]}"?`)) return;
    for (const file of group.files) {
      await firstValueFrom(this.http.post(`/api/v2/admin/unmatched-audio/${file.id}/ignore`, {}));
    }
    await this.refresh();
  }

  // ----- Display helpers -----

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

  shortDir(dir: string): string {
    return dir.split('/').slice(-2).join('/');
  }

  candidateBadgeColor(c: MusicBrainzCandidate, totalFiles: number): 'gold' | 'green' | 'amber' | 'red' {
    if (c.recording_mbid_coverage === totalFiles && totalFiles > 0) return 'gold';
    if (c.accommodates_files) return 'green';
    if (c.track_count >= totalFiles) return 'amber';
    return 'red';
  }

  candidateBadgeLabel(c: MusicBrainzCandidate, totalFiles: number): string {
    if (c.recording_mbid_coverage === totalFiles && totalFiles > 0) {
      return `Exact: ${c.recording_mbid_coverage}/${totalFiles} recording MBIDs match`;
    }
    if (c.accommodates_files) return 'All track positions fit';
    if (c.track_count >= totalFiles) return `Could fit (${c.track_count} tracks vs ${totalFiles})`;
    return `Too small (${c.track_count} tracks)`;
  }

  dismissResult(): void {
    this.lastResult.set(null);
  }
}
