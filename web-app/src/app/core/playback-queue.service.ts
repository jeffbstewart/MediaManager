import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

// Volume/mute persist via localStorage so playback resumes at the
// user's last level instead of jumping to 100% on each new track.
// Wrapped in try/catch because Safari private mode and locked-down
// profiles throw on access; in those cases we silently fall back to
// the in-memory defaults.
const VOLUME_KEY = 'mm.audioVolume';
const MUTED_KEY = 'mm.audioMuted';

function loadStoredVolume(): number {
  try {
    const raw = localStorage.getItem(VOLUME_KEY);
    if (raw === null) return 1.0;
    const v = Number(raw);
    return Number.isFinite(v) ? Math.max(0, Math.min(1, v)) : 1.0;
  } catch { return 1.0; }
}

function loadStoredMuted(): boolean {
  try { return localStorage.getItem(MUTED_KEY) === 'true'; }
  catch { return false; }
}

function saveStoredVolume(v: number): void {
  try { localStorage.setItem(VOLUME_KEY, String(v)); } catch { /* ignore */ }
}

function saveStoredMuted(m: boolean): void {
  try { localStorage.setItem(MUTED_KEY, m ? 'true' : 'false'); } catch { /* ignore */ }
}

/**
 * One row in the audio player's queue. `albumPosterUrl` is used for the
 * bottom-bar thumbnail; `albumTitleId` lets the now-playing chip link back
 * to the AlbumScreen the track came from.
 */
export interface QueuedTrack {
  trackId: number;
  trackName: string;
  durationSeconds: number | null;
  albumTitleId: number;
  albumName: string;
  albumPosterUrl: string | null;
  primaryArtistName: string | null;
  /** Optional per-track artist override, e.g. on compilations. */
  trackArtistName?: string | null;
  /**
   * Phase 2 — when populated, ties the queue entry to the originating
   * playlist_track row so the audio player can post resume-cursor
   * updates as it advances. Smart playlists and ad-hoc queues leave
   * this null; only owned/forked playlists wire it up.
   */
  playlistContext?: { playlistId: number; playlistTrackId: number } | null;
}

export type RepeatMode = 'OFF' | 'ONE' | 'ALL';

/** Queue generation mode — EXPLICIT (user chose the list) vs RADIO (server-generated, auto-refills). */
export type QueueMode = 'EXPLICIT' | 'RADIO';

export interface RadioSeedDescriptor {
  seed_type: 'album' | 'track';
  seed_id: number;
  seed_name: string;
  seed_artist_name: string | null;
}

/** Shape of /api/v2/radio/{start,next} track entries. */
interface RadioTrackDto {
  track_id: number;
  track_name: string;
  album_title_id: number;
  album_name: string;
  artist_name: string | null;
  disc_number: number;
  track_number: number;
}

/**
 * Owns the web audio player's queue, current-track, position, and shuffle /
 * repeat state. The [AudioPlayerComponent] in the shell binds an HTML5
 * `<audio>` element to [currentTrackUrl] and reports playback events back
 * through [reportPosition]. Every ~10 s of play the service POSTs to
 * /api/v2/audio/progress so "Continue Listening" has a resume point.
 *
 * Persistence across route changes is M5-deferred (see docs/MUSIC.md): the
 * audio element lives inside the shell, so in-app navigation does keep it
 * alive, but a full-reload or tab close ends playback. The server-side
 * listening_progress row is the safety net — the Continue Listening
 * carousel is one click away.
 */
@Injectable({ providedIn: 'root' })
export class PlaybackQueueService {
  private readonly http = inject(HttpClient);

  /** Upstream queue; index 0 is up next after the current track finishes. */
  private readonly queue = signal<QueuedTrack[]>([]);
  private readonly currentIndex = signal<number>(-1);
  readonly playing = signal<boolean>(false);
  readonly positionSeconds = signal<number>(0);
  readonly durationSeconds = signal<number>(0);
  readonly volume = signal<number>(loadStoredVolume());
  readonly muted = signal<boolean>(loadStoredMuted());
  readonly shuffleEnabled = signal<boolean>(false);
  readonly repeatMode = signal<RepeatMode>('OFF');

  // Radio state. When queueMode === 'RADIO', the service auto-appends a
  // fresh batch via /api/v2/radio/next whenever the remaining queue gets
  // thin. radioSeed carries the session identifier + display info for
  // the bottom-bar chip.
  readonly queueMode = signal<QueueMode>('EXPLICIT');
  readonly radioSeed = signal<RadioSeedDescriptor | null>(null);
  private radioSeedId: string | null = null;
  /** History of recently-played tracks fed back to /radio/next for skip weighting. */
  private readonly radioHistory: { track_id: number; skipped_early: boolean }[] = [];
  /** Re-entrancy guard so a burst of `next()` calls don't all fire refill requests. */
  private radioRefillInFlight = false;
  /** Per-track timestamp for detecting skip-within-30s. */
  private currentTrackStartedAt: number | null = null;

  readonly currentTrack = computed<QueuedTrack | null>(() => {
    const idx = this.currentIndex();
    const q = this.queue();
    return idx >= 0 && idx < q.length ? q[idx] : null;
  });

  /** Readonly view of the full queue — used by the queue-panel UI. */
  readonly queueSnapshot = computed<QueuedTrack[]>(() => this.queue());

  /** Readonly view of the playing-index — lets the queue panel highlight the current row. */
  readonly currentQueueIndex = computed<number>(() => this.currentIndex());

  readonly hasQueue = computed<boolean>(() => this.currentTrack() !== null);

  readonly currentTrackUrl = computed<string | null>(() => {
    const t = this.currentTrack();
    return t ? `/audio/${t.trackId}` : null;
  });

  private lastReportedAt = 0;
  private readonly REPORT_INTERVAL_MS = 10_000;
  private lastPlaylistReportedAt = 0;
  private readonly PLAYLIST_REPORT_INTERVAL_MS = 15_000;
  /** Set of trackIds we've already counted as "completed" this play session. */
  private readonly completedThisSession = new Set<number>();

  /** Start playing an album from [startIndex] of its track list. */
  playAlbum(tracks: QueuedTrack[], startIndex: number = 0): void {
    if (tracks.length === 0) return;
    this.stopRadio();
    this.queue.set([...tracks]);
    this.currentIndex.set(Math.max(0, Math.min(startIndex, tracks.length - 1)));
    this.playing.set(true);
    this.positionSeconds.set(0);
    this.currentTrackStartedAt = Date.now();
    // Duration comes from the audio element's metadata-loaded event; seed
    // with the catalogue value so the scrubber can render before metadata
    // arrives (e.g. while FFmpeg is still transcoding).
    const dur = this.currentTrack()?.durationSeconds ?? 0;
    this.durationSeconds.set(dur);
  }

  /**
   * Start a Last.fm-seeded radio session from an album or a single track.
   * Replaces any existing queue and transitions into RADIO mode — the
   * queue will auto-refill as it gets thin. Returns false when the server
   * had nothing to play (no similar artists / no owned tracks overlap).
   */
  async startRadio(seedType: 'album' | 'track', seedId: number): Promise<boolean> {
    try {
      const resp = await firstValueFrom(
        this.http.post<{
          radio_seed_id: string;
          seed: RadioSeedDescriptor;
          tracks: RadioTrackDto[];
        }>('/api/v2/radio/start', { seed_type: seedType, seed_id: seedId })
      );
      if (!resp.tracks || resp.tracks.length === 0) return false;
      this.radioSeedId = resp.radio_seed_id;
      this.radioSeed.set(resp.seed);
      this.radioHistory.length = 0;
      this.queueMode.set('RADIO');
      this.queue.set(resp.tracks.map(dtoToQueued));
      this.currentIndex.set(0);
      this.playing.set(true);
      this.positionSeconds.set(0);
      this.currentTrackStartedAt = Date.now();
      const dur = this.currentTrack()?.durationSeconds ?? 0;
      this.durationSeconds.set(dur);
      return true;
    } catch {
      return false;
    }
  }

  /**
   * Jump to an arbitrary index in the queue. Used by the queue panel so
   * the user can tap a track further down the list to play it now.
   * Departing-track skip accounting runs first so radio weighting stays
   * honest.
   */
  jumpTo(index: number): void {
    const q = this.queue();
    if (index < 0 || index >= q.length) return;
    if (index === this.currentIndex()) return;
    this.recordDepartingTrack();
    this.currentIndex.set(index);
    this.positionSeconds.set(0);
    this.playing.set(true);
    this.currentTrackStartedAt = Date.now();
    this.maybeRefillRadio();
  }

  /**
   * Close the audio player entirely: stop playback, drop the queue,
   * exit radio mode, flush one last progress report. The bottom-bar
   * UI hides itself when the queue is empty, so this is the "×" the
   * user clicks to make the player go away.
   */
  clearQueue(): void {
    this.flushProgress();
    this.playing.set(false);
    this.positionSeconds.set(0);
    this.durationSeconds.set(0);
    this.queue.set([]);
    this.currentIndex.set(-1);
    this.completedThisSession.clear();
    this.queueMode.set('EXPLICIT');
    this.radioSeed.set(null);
    this.radioSeedId = null;
    this.radioHistory.length = 0;
    this.radioRefillInFlight = false;
    this.currentTrackStartedAt = null;
  }

  /** Turn off radio mode. The already-generated queue keeps playing. */
  stopRadio(): void {
    this.queueMode.set('EXPLICIT');
    this.radioSeed.set(null);
    this.radioSeedId = null;
    this.radioHistory.length = 0;
    this.radioRefillInFlight = false;
  }

  /** Queue a single track and start it; useful from the home carousel. */
  playSingleTrack(track: QueuedTrack): void {
    this.playAlbum([track], 0);
  }

  /**
   * Replace the queue with a sequence of tracks and start at [startIndex].
   * Identical to [playAlbum] semantically — the separate name is just for
   * caller readability when the source isn't a single album (playlists,
   * library shuffle).
   */
  playTracks(tracks: QueuedTrack[], startIndex: number = 0): void {
    this.playAlbum(tracks, startIndex);
  }

  /**
   * Returns a snapshot of the current queue's track ids in queue order.
   * Used by "Save as playlist" / "Add queue to playlist" actions on the
   * bottom-bar — the call site posts these to PlaylistService.
   */
  currentQueueTrackIds(): number[] {
    return this.queue().map(t => t.trackId);
  }

  /** Advance to the next queued track, respecting shuffle / repeat. */
  next(): void {
    const q = this.queue();
    if (q.length === 0) return;
    // Record the departing track's skip status for the radio history
    // before we advance. "Skipped early" == user hit next within 30 s of
    // the track starting, which feeds artist down-weighting.
    this.recordDepartingTrack();
    const repeat = this.repeatMode();
    if (repeat === 'ONE') {
      // Restart the current track; audio element will seek to 0.
      this.positionSeconds.set(0);
      this.currentTrackStartedAt = Date.now();
      return;
    }

    let nextIdx: number;
    if (this.shuffleEnabled()) {
      nextIdx = this.pickShuffledNext();
    } else {
      nextIdx = this.currentIndex() + 1;
    }

    if (nextIdx >= q.length) {
      if (repeat === 'ALL') {
        nextIdx = 0;
      } else {
        // End of queue. Pause but keep `currentIndex` pointing at the
        // last track so the bar stays visible showing the finished
        // track. Tearing it down here would also tear it down for
        // legitimate "Continue Listening" resumes whose saved
        // position is close enough to the track end that playback
        // ends within a second or two of starting. The "×" button on
        // the bar is the explicit "clear queue" path.
        this.playing.set(false);
        return;
      }
    }
    this.currentIndex.set(nextIdx);
    this.positionSeconds.set(0);
    this.playing.set(true);
    this.currentTrackStartedAt = Date.now();
    this.maybeRefillRadio();
  }

  prev(): void {
    // Standard media-app convention: first 3 s seeks to 0, after that goes
    // to previous track.
    if (this.positionSeconds() > 3) {
      this.positionSeconds.set(0);
      return;
    }
    const idx = this.currentIndex();
    if (idx <= 0) {
      this.positionSeconds.set(0);
      return;
    }
    this.currentIndex.set(idx - 1);
    this.positionSeconds.set(0);
    this.playing.set(true);
  }

  pause(): void {
    this.playing.set(false);
  }

  resume(): void {
    if (this.currentTrack() != null) this.playing.set(true);
  }

  toggle(): void {
    if (this.playing()) this.pause(); else this.resume();
  }

  seek(seconds: number): void {
    const clamped = Math.max(0, Math.min(seconds, this.durationSeconds()));
    this.positionSeconds.set(clamped);
  }

  setVolume(v: number): void {
    const clamped = Math.max(0, Math.min(1, v));
    this.volume.set(clamped);
    saveStoredVolume(clamped);
  }

  toggleMute(): void {
    const next = !this.muted();
    this.muted.set(next);
    saveStoredMuted(next);
  }

  toggleShuffle(): void {
    this.shuffleEnabled.set(!this.shuffleEnabled());
  }

  cycleRepeat(): void {
    const next: RepeatMode = this.repeatMode() === 'OFF' ? 'ALL'
      : this.repeatMode() === 'ALL' ? 'ONE' : 'OFF';
    this.repeatMode.set(next);
  }

  /**
   * Bound to the audio element's `timeupdate` event. Rate-limits
   * server-side progress reports to the configured interval, AND
   * throttles the displayed [positionSeconds] signal to whole-second
   * changes. `timeupdate` fires 4+ times per second; writing the
   * signal on every tick was cheap individually but cascaded into
   * scrubber re-binds and effect reruns that made the whole app feel
   * sluggish during playback. Rounding to 1 Hz is invisible to the
   * user (the display is in whole seconds anyway) and cuts change-
   * detection work by 4x.
   */
  reportPosition(position: number, duration: number): void {
    const rounded = Math.floor(position);
    if (rounded !== this.positionSeconds()) {
      this.positionSeconds.set(rounded);
    }
    if (duration > 0 && duration !== this.durationSeconds()) {
      this.durationSeconds.set(duration);
    }

    const now = Date.now();
    const track = this.currentTrack();

    // Per-track progress goes to /api/v2/audio/progress (existing).
    if (now - this.lastReportedAt >= this.REPORT_INTERVAL_MS && track) {
      this.lastReportedAt = now;
      this.postProgress(track.trackId, Math.floor(position), Math.floor(duration));
    }

    // Per-playlist resume cursor — only when the queue is sourced from
    // a playlist. Throttled separately because the write rate doesn't
    // need to match the per-track cadence.
    if (track?.playlistContext &&
        now - this.lastPlaylistReportedAt >= this.PLAYLIST_REPORT_INTERVAL_MS) {
      this.lastPlaylistReportedAt = now;
      this.postPlaylistProgress(
        track.playlistContext.playlistId,
        track.playlistContext.playlistTrackId,
        Math.floor(position),
      );
    }

    // Track-completion event for Most Played. Fire once per (track, session)
    // when the user gets past the lesser of 90% or last-30s threshold.
    // Prevents the same track from incrementing the counter on every tick.
    if (track && duration > 30 && !this.completedThisSession.has(track.trackId)) {
      const remaining = duration - position;
      const past90 = position / duration >= 0.9;
      if (past90 || remaining <= 5) {
        this.completedThisSession.add(track.trackId);
        this.postTrackCompleted(track.trackId);
      }
    }
  }

  /** Called on pause / track-end / close to flush a final progress snapshot. */
  flushProgress(): void {
    const track = this.currentTrack();
    if (!track) return;
    this.postProgress(track.trackId, Math.floor(this.positionSeconds()), Math.floor(this.durationSeconds()));
  }

  /**
   * Push a HistoryEntry for the current track onto the radio history
   * buffer. Called when the track is ending (ended / next / skip) so
   * the next /radio/next call can down-weight artists the user skipped.
   */
  private recordDepartingTrack(): void {
    if (this.queueMode() !== 'RADIO') return;
    const track = this.currentTrack();
    if (!track || this.currentTrackStartedAt === null) return;
    const age = Date.now() - this.currentTrackStartedAt;
    const dur = this.durationSeconds();
    // "Skipped early" == user hit next within 30 s AND the track had
    // enough duration that the skip clearly wasn't end-of-track.
    const skippedEarly = age < 30_000 && (dur === 0 || this.positionSeconds() < dur - 3);
    this.radioHistory.push({ track_id: track.trackId, skipped_early: skippedEarly });
    // Cap the history we send back so the payload stays small.
    while (this.radioHistory.length > 50) this.radioHistory.shift();
  }

  /**
   * When fewer than 5 tracks remain in a RADIO queue, fetch the next
   * batch from the server and append. Re-entrancy guard prevents a
   * burst of next() calls from firing overlapping refills.
   */
  private async maybeRefillRadio(): Promise<void> {
    if (this.queueMode() !== 'RADIO') return;
    if (this.radioSeedId === null) return;
    if (this.radioRefillInFlight) return;
    const remaining = this.queue().length - this.currentIndex() - 1;
    if (remaining >= 5) return;
    this.radioRefillInFlight = true;
    try {
      const resp = await firstValueFrom(
        this.http.post<{ tracks: RadioTrackDto[] }>('/api/v2/radio/next', {
          radio_seed_id: this.radioSeedId,
          history: this.radioHistory.slice(-20),
        })
      );
      const appended = resp.tracks?.map(dtoToQueued) ?? [];
      if (appended.length > 0) {
        this.queue.update(q => [...q, ...appended]);
      }
    } catch {
      // Soft-fail; the queue will just end naturally.
    } finally {
      this.radioRefillInFlight = false;
    }
  }

  private pickShuffledNext(): number {
    const q = this.queue();
    if (q.length <= 1) return 0;
    // Simple uniform shuffle: pick any index that isn't the current one.
    let idx: number;
    do {
      idx = Math.floor(Math.random() * q.length);
    } while (idx === this.currentIndex());
    return idx;
  }

  private postProgress(trackId: number, position: number, duration: number): void {
    this.beacon('/api/v2/audio/progress', {
      track_id: trackId,
      position_seconds: position,
      duration_seconds: duration > 0 ? duration : null,
    });
  }

  /** Phase 2 — upsert the per-user playlist resume cursor. Fire-and-forget. */
  private postPlaylistProgress(playlistId: number, playlistTrackId: number, positionSeconds: number): void {
    this.beacon(`/api/v2/playlists/${playlistId}/progress`, {
      playlist_track_id: playlistTrackId,
      position_seconds: positionSeconds,
    });
  }

  /** Phase 2 — bump per-user, per-track play counter. Fire-and-forget. */
  private postTrackCompleted(trackId: number): void {
    this.beacon('/api/v2/playlists/track-completed', { track_id: trackId });
  }

  /**
   * Fire-and-forget JSON POST that doesn't consume one of the
   * browser's six HTTP/1.1 slots per origin. Progress writes used to
   * ride the HttpClient pool; while audio was playing, they piled up
   * behind the audio stream and made user navigation fetches queue
   * visibly (pending for seconds despite 30 ms server responses).
   *
   * navigator.sendBeacon queues requests on a separate scheduler so
   * they never compete with foreground fetches; also guarantees
   * delivery across page unload. Falls back to fetch() with
   * keepalive:true when sendBeacon isn't available (all modern
   * browsers have it, but defensive). Cookies ride along either way.
   */
  private beacon(url: string, payload: unknown): void {
    const body = JSON.stringify(payload);
    try {
      if (typeof navigator !== 'undefined' && typeof navigator.sendBeacon === 'function') {
        const blob = new Blob([body], { type: 'application/json' });
        if (navigator.sendBeacon(url, blob)) return;
        // sendBeacon returned false — queue full / size over limit —
        // fall through to fetch().
      }
      fetch(url, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body,
        keepalive: true,
      }).catch(() => { /* swallow */ });
    } catch {
      /* If Blob/fetch construction fails, skip this tick. */
    }
  }
}

function dtoToQueued(t: RadioTrackDto): QueuedTrack {
  return {
    trackId: t.track_id,
    trackName: t.track_name,
    durationSeconds: null,
    albumTitleId: t.album_title_id,
    albumName: t.album_name,
    albumPosterUrl: `/posters/w185/${t.album_title_id}`,
    primaryArtistName: t.artist_name,
  };
}
