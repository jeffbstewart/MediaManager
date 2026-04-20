import { Component, inject, signal, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';
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
import type { AlbumTrack, TagCard } from '../../core/catalog.service';

@Component({
  selector: 'app-title-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    MatChipsModule,
    MatIconModule,
    MatButtonModule,
    MatMenuModule,
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

  // Admin: rescan-files in-flight flag so the menu item disables while
  // the server walks the album's directories.
  readonly rescanning = signal(false);

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

  /**
   * Tooltip for the BPM + time-sig pill on each track row. Handy when
   * only one of the two is set — the pill itself shows "128 BPM" but
   * the tooltip explains "Time signature unknown".
   */
  musicMetaTitle(t: AlbumTrack): string {
    const parts: string[] = [];
    if (t.bpm) parts.push(`${t.bpm} BPM`);
    else parts.push('BPM unknown');
    if (t.time_signature) parts.push(`Time signature ${t.time_signature}`);
    else parts.push('Time signature unknown');
    return parts.join(' · ');
  }

  /**
   * Admin-only — prompt for a new BPM and time signature. Pressing
   * Cancel on either prompt aborts. Null-able fields sent through the
   * API so the server can clear the current value by passing empty.
   */
  async editTrackMusicTags(t: AlbumTrack): Promise<void> {
    const currentBpm = t.bpm != null ? String(t.bpm) : '';
    const bpmIn = window.prompt(
      `BPM for "${t.name}" (blank to clear, Cancel to skip)`,
      currentBpm
    );
    // Cancel aborts; empty string clears; otherwise validate integer.
    if (bpmIn === null) return;
    const trimmedBpm = bpmIn.trim();
    let bpm: number | null = null;
    if (trimmedBpm !== '') {
      const n = parseInt(trimmedBpm, 10);
      if (!Number.isFinite(n) || n < 1 || n > 999) {
        window.alert('BPM must be an integer between 1 and 999.');
        return;
      }
      bpm = n;
    }

    const currentTs = t.time_signature ?? '';
    const tsIn = window.prompt(
      `Time signature for "${t.name}" (e.g. 3/4, blank to clear)`,
      currentTs
    );
    if (tsIn === null) return;
    const trimmedTs = tsIn.trim();
    if (trimmedTs !== '' && !/^\d{1,2}\/\d{1,2}$/.test(trimmedTs)) {
      window.alert("Time signature must look like '3/4' / '4/4' / '6/8'.");
      return;
    }

    await this.catalog.setTrackMusicTags(t.track_id, {
      bpm,
      time_signature: trimmedTs === '' ? null : trimmedTs,
    });
    await this.refreshTitle();
  }

  /** Admin-only — detach a tag from a track via the inline × on the chip. */
  async removeTrackTag(trackId: number, tagId: number): Promise<void> {
    const existing = await this.catalog.getTrackTags(trackId);
    const next = existing.map(t => t.id).filter(id => id !== tagId);
    await this.catalog.setTrackTags(trackId, next);
    await this.refreshTitle();
  }

  private async attachTrackTag(tagId: number): Promise<void> {
    if (!this.trackTagTargetId) return;
    const next = [...this.trackTagCurrentIds.filter(id => id !== tagId), tagId];
    await this.catalog.setTrackTags(this.trackTagTargetId, next);
    this.closeTrackTagPicker();
    // Refresh so the new chip appears on the track row without a reload.
    await this.refreshTitle();
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

  /**
   * Admin-only — walk the album's directories and link any previously
   * unlinked tracks. Shows a one-line summary in the error banner slot
   * (reusing its positioning) so the admin knows what happened. Also
   * refreshes the title so freshly-linked tracks get play buttons.
   */
  async rescanAlbum(): Promise<void> {
    const t = this.title();
    if (!t || this.rescanning()) return;
    this.rescanning.set(true);
    try {
      const r = await this.catalog.rescanAlbum(t.title_id);
      // Verbose message when nothing matched so the admin can tell
      // whether the walk found files at all and where it looked —
      // without this, "linked 0" is opaque.
      const lines = [
        `Rescan: linked ${r.linked}, still unlinked ${r.no_match}.`,
        `${r.candidates_considered} candidate(s) kept from ${r.files_walked} ` +
          `audio file(s) walked.`,
        `${r.files_wrong_album_tag} rejected (album tag didn't match), ` +
          `${r.files_path_rejected} skipped by path prefilter, ` +
          `${r.files_already_linked_elsewhere} already linked elsewhere.`,
        `${r.files_accepted_by_artist_position} accepted by artist+position bypass.`,
      ];
      if (r.rejected_album_tag_samples.length > 0) {
        lines.push(
          `Rejected album tags saw (sample): ${r.rejected_album_tag_samples
            .map(s => `"${s}"`).join(', ')}`,
        );
      }
      lines.push(`Searched: ${r.roots_walked.join(' → ')}`);
      lines.push(`music_root_path: ${r.music_root_configured}`);
      window.alert(r.message ?? lines.join('\n'));
      const fresh = await this.catalog.getTitleDetail(t.title_id);
      this.title.set(fresh);
    } catch (e) {
      window.alert(`Rescan failed: ${(e as Error).message ?? e}`);
    } finally {
      this.rescanning.set(false);
    }
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
