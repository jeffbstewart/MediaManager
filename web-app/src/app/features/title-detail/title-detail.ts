import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { DecimalPipe } from '@angular/common';
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
import { CatalogService, TitleDetail, AlbumPersonnelEntry } from '../../core/catalog.service';
import { FeatureService } from '../../core/feature.service';
import { PlaybackQueueService, QueuedTrack } from '../../core/playback-queue.service';
import { AddToPlaylistComponent } from '../../shared/add-to-playlist/add-to-playlist';
import { TagPickerComponent } from '../../shared/tag-picker/tag-picker';
import type { TagCard } from '../../core/catalog.service';

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
    DecimalPipe,
    AddToPlaylistComponent,
    TagPickerComponent,
  ],
  templateUrl: './title-detail.html',
  styleUrl: './title-detail.scss',
})
export class TitleDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly catalog = inject(CatalogService);
  private readonly http = inject(HttpClient);
  readonly features = inject(FeatureService);
  private readonly playbackQueue = inject(PlaybackQueueService);

  readonly routes = AppRoutes;
  readonly loading = signal(true);
  readonly error = signal('');
  readonly title = signal<TitleDetail | null>(null);
  readonly selectedSeason = signal<number | null>(null);

  // Phase 2 — playlist picker for album / track adds.
  readonly pickerOpen = signal(false);
  readonly pickerTrackIds = signal<number[]>([]);
  readonly pickerHeading = signal('Add to playlist');

  // Tag picker — admin-only "+ tag" affordance on the title detail.
  readonly tagPickerOpen = signal(false);

  // Track-level tag picker (phase B) — opens per-track, carries the
  // track id + current tag set so the picker can hide already-attached tags.
  readonly trackTagPickerOpen = signal(false);
  readonly trackTagPickerHeading = signal('Tag this track');
  private trackTagTargetId = 0;
  private trackTagCurrentIds: number[] = [];

  currentTrackTagIds(): number[] {
    return this.trackTagCurrentIds;
  }

  async openTrackTagPicker(trackId: number, trackName: string): Promise<void> {
    this.trackTagTargetId = trackId;
    this.trackTagPickerHeading.set(`Tag "${trackName}"`);
    // Fetch the track's existing tags so the picker can hide them.
    try {
      const existing = await this.catalog.getTrackTags(trackId);
      this.trackTagCurrentIds = existing.map(t => t.id);
    } catch {
      this.trackTagCurrentIds = [];
    }
    this.trackTagPickerOpen.set(true);
  }

  closeTrackTagPicker(): void {
    this.trackTagPickerOpen.set(false);
    this.trackTagTargetId = 0;
    this.trackTagCurrentIds = [];
  }

  async onTrackTagPicked(tag: TagCard): Promise<void> {
    await this.attachTrackTag(tag.id);
  }

  async onTrackTagCreatedAndPicked(tag: TagCard): Promise<void> {
    await this.attachTrackTag(tag.id);
  }

  private async attachTrackTag(tagId: number): Promise<void> {
    if (!this.trackTagTargetId) return;
    const next = [...this.trackTagCurrentIds.filter(id => id !== tagId), tagId];
    await this.catalog.setTrackTags(this.trackTagTargetId, next);
    this.closeTrackTagPicker();
  }

  currentTagIds(): number[] {
    return this.title()?.tags?.map(t => t.id) ?? [];
  }

  openTagPicker(): void { this.tagPickerOpen.set(true); }
  closeTagPicker(): void { this.tagPickerOpen.set(false); }

  async onTagPicked(tag: TagCard): Promise<void> {
    await this.attachTag(tag.id);
  }

  async onTagCreatedAndPicked(tag: TagCard): Promise<void> {
    // Fresh tag created from the picker — attach immediately.
    await this.attachTag(tag.id);
  }

  async removeTagFromTitle(tagId: number): Promise<void> {
    const t = this.title();
    if (!t) return;
    const next = (t.tags ?? []).map(x => x.id).filter(id => id !== tagId);
    await this.catalog.setTitleTags(t.title_id, next);
    await this.refreshTitle();
  }

  private async attachTag(tagId: number): Promise<void> {
    const t = this.title();
    if (!t) return;
    const existing = (t.tags ?? []).map(x => x.id);
    if (existing.includes(tagId)) {
      this.tagPickerOpen.set(false);
      return;
    }
    await this.catalog.setTitleTags(t.title_id, [...existing, tagId]);
    this.tagPickerOpen.set(false);
    await this.refreshTitle();
  }

  private async refreshTitle(): Promise<void> {
    const t = this.title();
    if (!t) return;
    try {
      this.title.set(await this.catalog.getTitleDetail(t.title_id));
    } catch { /* keep existing */ }
  }

  get isPersonal(): boolean { return this.title()?.media_type === 'PERSONAL'; }
  get isTv(): boolean { return this.title()?.media_type === 'TV'; }
  get isBook(): boolean { return this.title()?.media_type === 'BOOK'; }
  get isAlbum(): boolean { return this.title()?.media_type === 'ALBUM'; }

  /** Album total-duration formatter: "48 min" or "1 h 24 min". */
  formatAlbumDuration(seconds: number | null | undefined): string | null {
    if (!seconds || seconds < 1) return null;
    const totalMin = Math.round(seconds / 60);
    if (totalMin < 60) return `${totalMin} min`;
    const h = Math.floor(totalMin / 60);
    const m = totalMin % 60;
    return m === 0 ? `${h} h` : `${h} h ${m} min`;
  }

  /** Per-track duration formatter: "3:42" or "12:03". */
  formatTrackDuration(seconds: number | null | undefined): string {
    if (!seconds || seconds < 1) return '—';
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return `${m}:${s.toString().padStart(2, '0')}`;
  }

  /**
   * Play the whole album starting at [startIndex]. Builds a queue where
   * each entry carries the album context (poster, name, artist) so the
   * bottom-bar player can render without additional fetches.
   */
  playAlbum(startIndex: number = 0): void {
    const t = this.title();
    const tracks = t?.tracks ?? [];
    if (!t || tracks.length === 0) return;

    const primaryArtist = t.artists?.[0]?.name ?? null;
    const queued: QueuedTrack[] = tracks.map(track => ({
      trackId: track.track_id,
      trackName: track.name,
      durationSeconds: track.duration_seconds,
      albumTitleId: t.title_id,
      albumName: t.title_name,
      albumPosterUrl: t.poster_url,
      primaryArtistName: primaryArtist,
      // Per-track artist (compilation case) overrides the album-level name.
      trackArtistName: track.track_artists.length > 0
        ? track.track_artists.map(a => a.name).join(', ')
        : null,
    }));
    this.playbackQueue.playAlbum(queued, startIndex);
  }

  /** Play a single track; queues the rest of the album behind it. */
  playTrackAt(index: number): void {
    this.playAlbum(index);
  }

  /** Open the picker pre-loaded with every track on the current album. */
  openPickerForAlbum(): void {
    const tracks = this.title()?.tracks ?? [];
    if (tracks.length === 0) return;
    this.pickerTrackIds.set(tracks.map(t => t.track_id));
    this.pickerHeading.set(`Add album to playlist`);
    this.pickerOpen.set(true);
  }

  /** Open the picker for a single track row. */
  openPickerForTrack(trackId: number, trackName: string): void {
    this.pickerTrackIds.set([trackId]);
    this.pickerHeading.set(`Add "${trackName}" to playlist`);
    this.pickerOpen.set(true);
  }

  closePicker(): void {
    this.pickerOpen.set(false);
  }

  onPickerPicked(_evt: { playlistId: number; created: boolean }): void {
    this.pickerOpen.set(false);
  }

  readonly radioStarting = signal<boolean>(false);

  /** Gate the Start Radio button on the has_music_radio feature flag. */
  hasMusicRadio(): boolean {
    return this.features.hasMusicRadio();
  }

  async startRadio(): Promise<void> {
    const t = this.title();
    if (!t || !this.isAlbum) return;
    this.radioStarting.set(true);
    try {
      await this.playbackQueue.startRadio('album', t.title_id);
    } finally {
      this.radioStarting.set(false);
    }
  }

  /** Personnel section expanded state (collapsed by default — M6). */
  readonly personnelExpanded = signal<boolean>(false);

  /**
   * Personnel credits grouped by role, in the display order the UI shows.
   * A role bucket renders one linked artist per row with an instrument /
   * per-track context line. Empty buckets are omitted from the list.
   */
  get personnelByRole(): { role: string; label: string; entries: AlbumPersonnelEntry[] }[] {
    const personnel = this.title()?.personnel ?? [];
    if (personnel.length === 0) return [];
    const order: { role: string; label: string }[] = [
      { role: 'PERFORMER', label: 'Performers' },
      { role: 'COMPOSER', label: 'Composers' },
      { role: 'PRODUCER', label: 'Producers' },
      { role: 'ENGINEER', label: 'Engineers' },
      { role: 'MIXER', label: 'Mix' },
      { role: 'OTHER', label: 'Other' },
    ];
    return order
      .map(({ role, label }) => ({
        role,
        label,
        entries: personnel.filter(p => p.role === role),
      }))
      .filter(bucket => bucket.entries.length > 0);
  }

  /** Album tracks grouped by disc_number; preserves track_number order. */
  get albumDiscs(): { discNumber: number; tracks: NonNullable<TitleDetail['tracks']> }[] {
    const t = this.title();
    const tracks = t?.tracks ?? [];
    if (tracks.length === 0) return [];
    const byDisc = new Map<number, NonNullable<TitleDetail['tracks']>>();
    for (const track of tracks) {
      const list = byDisc.get(track.disc_number) ?? [];
      list.push(track);
      byDisc.set(track.disc_number, list);
    }
    return [...byDisc.entries()]
      .sort(([a], [b]) => a - b)
      .map(([discNumber, tracks]) => ({ discNumber, tracks }));
  }

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

  async confirmHide(): Promise<void> {
    const t = this.title();
    if (!t) return;

    if (t.is_hidden) {
      // Unhiding doesn't need confirmation
      const r = await firstValueFrom(this.http.post<{ is_hidden: boolean }>(
        `/api/v2/catalog/titles/${t.title_id}/hide`, {}));
      this.title.set({ ...t, is_hidden: r.is_hidden });
      return;
    }

    const confirmed = confirm(
      `Hide "${t.title_name}" from your library?\n\n` +
      `This title will no longer appear in your movie and TV lists, search results, or home page. ` +
      `Other users are not affected. You can unhide it from your Profile page.`
    );
    if (!confirmed) return;

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
