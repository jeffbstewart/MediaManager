import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

export interface CarouselTitle {
  title_id: number;
  title_name: string;
  poster_url: string | null;
  release_year: number | null;
}

export interface ContinueWatchingItem {
  transcode_id: number;
  title_id: number;
  title_name: string;
  poster_url: string | null;
  position_seconds: number;
  duration_seconds: number;
  progress_fraction: number;
  time_remaining: string;
  is_episode: boolean;
  episode_label: string | null;
  season_number: number | null;
  episode_number: number | null;
  episode_name: string | null;
}

export interface MissingSeason {
  title_id: number;
  title_name: string;
  poster_url: string | null;
  tmdb_id: number | null;
  tmdb_media_type: string | null;
  missing_seasons: { season_number: number }[];
}

export interface TitleCard {
  title_id: number;
  title_name: string;
  /** Server-side enum name: MOVIE / TV / PERSONAL / BOOK / ALBUM. Optional for callers that pre-filter by media type. */
  media_type?: string;
  poster_url: string | null;
  release_year: number | null;
  content_rating: string | null;
  playable: boolean;
  progress_fraction: number | null;
  /** Primary album artist — populated only when media_type is ALBUM. */
  artist_name?: string | null;
  /** Primary book author — populated only when media_type is BOOK. */
  author_name?: string | null;
}

export interface TitleListResponse {
  titles: TitleCard[];
  total: number;
  available_ratings: string[];
}

export type MediaType = 'MOVIE' | 'TV' | 'PERSONAL' | 'BOOK' | 'ALBUM';

/**
 * Returns a same-origin URL that proxies a TMDB image through
 * `/proxy/tmdb/{size}/{file}`. Callers pass the raw `poster_path` /
 * `profile_path` / `backdrop_path` string TMDB returned (it carries a
 * leading slash; this helper strips it). Returns `null` when the input
 * is nullish so templates can render a placeholder.
 *
 * Centralized here so future routing changes (e.g., signed URLs, a
 * different cache path) are one-line edits.
 */
export function tmdbImageUrl(
  path: string | null | undefined,
  size: string = 'w92'
): string | null {
  if (!path) return null;
  return `/proxy/tmdb/${size}/${path.replace(/^\/+/, '')}`;
}
export type SortMode = 'name' | 'year' | 'recent' | 'popular' | 'artist' | 'author';

/** Sort modes accepted by the artist exploration landing page. */
export type ArtistsSortMode = 'albums' | 'name' | 'recent';

export interface ArtistsListParams {
  sort?: ArtistsSortMode;
  q?: string;
  /** Default true on the server; pass false to include artists with no playable albums. */
  playableOnly?: boolean;
}

export interface ArtistsListItem {
  id: number;
  name: string;
  sort_name: string | null;
  artist_type: string;
  headshot_url: string | null;
  album_count: number;
  /** First owned album poster — used as a hero fallback when headshot is missing. */
  fallback_poster_url: string | null;
}

export interface ArtistsListResponse {
  artists: ArtistsListItem[];
  total: number;
}

export interface TitleListParams {
  mediaType: MediaType;
  sort?: SortMode;
  ratings?: string[];
  playableOnly?: boolean;
}

export interface FeatureFlags {
  has_personal_videos: boolean;
  has_books?: boolean;
  has_music?: boolean;
  has_music_radio?: boolean;
  has_cameras: boolean;
  has_live_tv: boolean;
  is_admin: boolean;
  wish_ready_count: number;
  unmatched_count: number;
  unmatched_books_count?: number;
  unmatched_audio_count?: number;
  data_quality_count: number;
  open_reports_count: number;
}

export interface TitleDetail {
  title_id: number;
  title_name: string;
  media_type: string;
  release_year: number | null;
  description: string | null;
  content_rating: string | null;
  poster_url: string | null;
  backdrop_url: string | null;
  event_date: string | null;
  is_starred: boolean;
  is_hidden: boolean;
  genres: string[];
  tags: { id: number; name: string; bg_color: string; text_color: string }[];
  formats: string[];
  /** Admin-only list of the MediaItem rows linked to this title, with their format + UPC, so the UI can route to /admin/item/:id for each. Empty for non-admin users. */
  admin_media_items: { media_item_id: number; media_format: string; upc: string | null }[];
  transcodes: TranscodeInfo[];
  readable_editions?: ReadableEdition[];
  cast: CastInfo[];
  episodes: EpisodeInfo[];
  seasons: { season_number: number; acquisition_status: string }[];
  family_members: { id: number; name: string }[];
  similar_titles: CarouselTitle[];
  collection: { id: number; name: string } | null;
  // Book-specific. Null/empty for non-book titles.
  authors?: { id: number; name: string }[];
  book_series?: { id: number; name: string; number: number | null } | null;
  page_count?: number | null;
  first_publication_year?: number | null;
  open_library_work_id?: string | null;
  // Album-specific. Null/empty for non-album titles.
  artists?: { id: number; name: string; artist_type: string }[];
  tracks?: AlbumTrack[];
  track_count?: number | null;
  total_duration_seconds?: number | null;
  label?: string | null;
  musicbrainz_release_group_id?: string | null;
  musicbrainz_release_id?: string | null;
  /** Personnel credits (M6). Empty when MB hasn't documented the album yet. */
  personnel?: AlbumPersonnelEntry[];
}

export interface AlbumPersonnelEntry {
  artist_id: number;
  artist_name: string;
  /** CreditRole enum name: PERFORMER / COMPOSER / PRODUCER / ENGINEER / MIXER / OTHER. */
  role: string;
  /** Instrument string for PERFORMER; null for other roles. */
  instrument: string | null;
  track_id: number;
  track_name: string | null;
  disc_number: number | null;
  track_number: number | null;
}

export interface AlbumTrack {
  track_id: number;
  disc_number: number;
  track_number: number;
  name: string;
  duration_seconds: number | null;
  /** Populated only when the per-track performer differs from the album-level credit (compilations). */
  track_artists: { id: number; name: string }[];
  /** Tags attached to this specific track (phase B). Empty for tracks with no tags. */
  tags: TagCard[];
  /** ID3 BPM when available — integer, else null. */
  bpm: number | null;
  /** Raw time signature ("3/4", "4/4", etc.) when known — null otherwise. */
  time_signature: string | null;
}

export interface TranscodeInfo {
  transcode_id: number;
  file_name: string;
  playable: boolean;
  media_format: string | null;
  position_seconds?: number;
  duration_seconds?: number;
  season_number?: number;
  episode_number?: number;
  episode_name?: string;
}

export interface ReadableEdition {
  media_item_id: number;
  media_format: 'EBOOK_EPUB' | 'EBOOK_PDF' | string;
  percent: number;
  cfi: string | null;
  updated_at: string | null;
}

export interface ReadingProgress {
  media_item_id: number;
  cfi: string | null;
  percent: number;
  updated_at: string | null;
}

export interface CastInfo {
  id: number;
  name: string;
  character_name: string | null;
  headshot_url: string | null;
  tmdb_person_id: number;
}

export interface EpisodeInfo {
  id: number;
  season_number: number;
  episode_number: number;
  name: string | null;
  transcode_id: number | null;
  playable: boolean;
  position_seconds: number | null;
  duration_seconds: number | null;
}

export interface HomeFeed {
  continue_watching: ContinueWatchingItem[];
  recently_added: CarouselTitle[];
  recently_added_books: RecentBook[];
  recently_added_albums?: RecentAlbum[];
  resume_listening?: ResumeListeningItem[];
  resume_reading?: ResumeReadingItem[];
  recently_watched: CarouselTitle[];
  missing_seasons: MissingSeason[];
  features: FeatureFlags;
}

export interface ResumeReadingItem {
  media_item_id: number;
  title_id: number;
  title_name: string;
  poster_url: string | null;
  percent: number;
  media_format: string;
  updated_at: string | null;
}

export interface RecentBook {
  title_id: number;
  title_name: string;
  poster_url: string | null;
  release_year: number | null;
  author_name: string | null;
  series_name: string | null;
  series_number: string | null;
}

export interface RecentAlbum {
  title_id: number;
  title_name: string;
  poster_url: string | null;
  release_year: number | null;
  artist_name: string | null;
  track_count: number | null;
}

export interface ResumeListeningItem {
  track_id: number;
  track_name: string;
  title_id: number;
  title_name: string;
  poster_url: string | null;
  artist_name: string | null;
  position_seconds: number;
  duration_seconds: number;
  percent: number;
  updated_at: string | null;
}

export interface CollectionCard {
  id: number;
  name: string;
  poster_url: string | null;
  owned_count: number;
  total_parts: number;
}

export interface CollectionListResponse {
  collections: CollectionCard[];
  total: number;
}

export interface CollectionPart {
  title_id?: number;
  tmdb_movie_id?: number;
  title_name: string;
  poster_url: string | null;
  release_year: number | null;
  owned: boolean;
  playable: boolean;
  progress_fraction: number | null;
  wished?: boolean;
}

export interface CollectionDetail {
  id: number;
  name: string;
  owned_count: number;
  total_parts: number;
  parts: CollectionPart[];
}

export interface TagCard {
  id: number;
  name: string;
  bg_color: string;
  text_color: string;
  title_count: number;
}

export interface TagListResponse {
  tags: TagCard[];
  total: number;
}

export interface TagDetailResponse {
  tag: TagCard;
  titles: TitleCard[];
  total: number;
  /** Phase B — tracks tagged with this tag. Independent of the title list. */
  tracks: TaggedTrackCard[];
  track_total: number;
}

/** Track row on the tag detail page (tagged-tracks section). */
export interface TaggedTrackCard {
  track_id: number;
  track_name: string;
  duration_seconds: number | null;
  title_id: number;
  title_name: string | null;
  poster_url: string | null;
  playable: boolean;
}

export interface FamilyVideoCard {
  title_id: number;
  title_name: string;
  poster_url: string | null;
  event_date: string | null;
  description: string | null;
  playable: boolean;
  progress_fraction: number | null;
  family_members: { id: number; name: string }[];
  tags: { id: number; name: string; bg_color: string; text_color: string }[];
}

export interface FamilyVideosResponse {
  videos: FamilyVideoCard[];
  total: number;
  family_members: { id: number; name: string }[];
}

export type FamilySortMode = 'date_desc' | 'date_asc' | 'name' | 'recent';

export interface TvChannelInfo {
  id: number;
  guide_number: string;
  guide_name: string;
  network_affiliation: string | null;
  reception_quality: number;
}

export interface TvChannelListResponse {
  channels: TvChannelInfo[];
  total: number;
}

export interface CameraInfo {
  id: number;
  name: string;
}

export interface CameraListResponse {
  cameras: CameraInfo[];
  total: number;
}

export interface MediaWish {
  id: number;
  tmdb_id: number;
  tmdb_title: string;
  tmdb_media_type: string;
  tmdb_poster_path: string | null;
  tmdb_release_year: number | null;
  season_number: number | null;
  lifecycle_stage: string;
  lifecycle_label: string;
  title_id: number | null;
  vote_count: number;
  dismissible: boolean;
}

export interface TranscodeWish {
  id: number;
  title_id: number;
  title_name: string;
  poster_url: string | null;
  status: 'ready' | 'pending';
}

export interface WishListResponse {
  media_wishes: MediaWish[];
  transcode_wishes: TranscodeWish[];
  book_wishes: BookWish[];
  album_wishes?: AlbumWish[];
  has_any_media_wish: boolean;
}

export interface BookWish {
  id: number;
  ol_work_id: string;
  title: string;
  author: string | null;
  cover_url: string | null;
  series_id: number | null;
  series_number: string | null;
}

export interface TmdbSearchResultItem {
  tmdb_id: number;
  title: string;
  media_type: string;
  poster_path: string | null;
  release_year: number | null;
  popularity: number | null;
  already_wished: boolean;
}

export interface TmdbSearchResponse {
  results: TmdbSearchResultItem[];
}

export interface SearchResult {
  type: 'movie' | 'tv' | 'personal' | 'book' | 'album' | 'track'
      | 'actor' | 'artist' | 'author'
      | 'collection' | 'tag' | 'channel' | 'camera';
  name: string;
  title_id?: number;
  track_id?: number;
  person_id?: number;
  artist_id?: number;
  author_id?: number;
  collection_id?: number;
  tag_id?: number;
  channel_id?: number;
  camera_id?: number;
  poster_url?: string | null;
  headshot_url?: string | null;
  album_name?: string | null;
  year?: number | null;
  playable?: boolean;
  bg_color?: string;
  text_color?: string;
  title_count?: number;
  affiliation?: string | null;
  score?: number;
}

export interface SearchResponse {
  results: SearchResult[];
  query: string;
}

/** Canonical dance preset fetched from /api/v2/search/presets. */
export interface AdvancedSearchPreset {
  key: string;
  name: string;
  description: string;
  bpm_min: number | null;
  bpm_max: number | null;
  time_signature: string | null;
}

export interface AdvancedTrackSearchFilters {
  query?: string;
  bpmMin?: number | null;
  bpmMax?: number | null;
  timeSignature?: string | null;
  limit?: number;
}

export interface TrackSearchHit {
  track_id: number;
  title_id: number;
  name: string;
  album_name: string;
  artist_name: string | null;
  bpm: number | null;
  time_signature: string | null;
  duration_seconds: number | null;
  poster_url: string | null;
  playable: boolean;
}

export interface RecommendationVoter {
  mbid: string;
  name: string;
  album_count: number;
}

export interface ArtistRecommendation {
  suggested_artist_mbid: string;
  suggested_artist_name: string;
  artist_id: number | null;
  score: number;
  voters: RecommendationVoter[];
  representative_release_group_id: string | null;
  representative_release_title: string | null;
  cover_url: string | null;
}

export interface ArtistRecommendationsResponse {
  artists: ArtistRecommendation[];
}

export interface ActorDetail {
  person_id: number;
  name: string;
  biography: string | null;
  birthday: string | null;
  deathday: string | null;
  place_of_birth: string | null;
  known_for: string | null;
  profile_path: string | null;
  owned_titles: ActorOwnedTitle[];
  other_works: ActorCredit[];
}

export interface ActorOwnedTitle {
  title_id: number;
  title_name: string;
  poster_url: string | null;
  release_year: number | null;
  character_name: string | null;
}

export interface ActorCredit {
  tmdb_id: number;
  title: string;
  media_type: string;
  character_name: string | null;
  release_year: number | null;
  poster_path: string | null;
  popularity: number | null;
  already_wished: boolean;
}

// ---- Books ----

export interface AuthorOwnedBook {
  title_id: number;
  title_name: string;
  poster_url: string | null;
  release_year: number | null;
  series_name: string | null;
  series_number: string | null;
}

export interface AuthorDetail {
  id: number;
  name: string;
  biography: string | null;
  headshot_url: string | null;
  birth_date: string | null;
  death_date: string | null;
  open_library_author_id: string | null;
  owned_books: AuthorOwnedBook[];
  other_works: AuthorOtherWork[];
}

export interface AuthorOtherWork {
  ol_work_id: string;
  title: string;
  year: number | null;
  cover_url: string | null;
  series_raw: string | null;
  already_wished: boolean;
}

export interface ArtistDetail {
  id: number;
  name: string;
  sort_name: string;
  artist_type: string;
  biography: string | null;
  headshot_url: string | null;
  begin_date: string | null;
  end_date: string | null;
  musicbrainz_artist_id: string | null;
  owned_albums: ArtistOwnedAlbum[];
  other_works: ArtistOtherWork[];
  /** Members of this band, for GROUP / ORCHESTRA / CHOIR artists (M6). */
  band_members?: ArtistMembershipEntry[];
  /** Bands this person has been in, for PERSON artists (M6). */
  member_of?: ArtistMembershipEntry[];
}

export interface ArtistMembershipEntry {
  id: number;
  name: string;
  artist_type: string;
  begin_date: string | null;
  end_date: string | null;
  instruments: string | null;
}

export interface ArtistOwnedAlbum {
  title_id: number;
  title_name: string;
  poster_url: string | null;
  release_year: number | null;
  track_count: number | null;
}

export interface ArtistOtherWork {
  release_group_id: string;
  title: string;
  year: number | null;
  primary_type: string | null;
  secondary_types: string[];
  is_compilation: boolean;
  cover_url: string | null;
  already_wished: boolean;
}

export interface AlbumWishInput {
  release_group_id: string;
  title: string;
  primary_artist: string | null;
  year: number | null;
  cover_release_id: string | null;
  is_compilation: boolean;
}

export interface AlbumWish {
  id: number;
  release_group_id: string;
  title: string;
  primary_artist: string | null;
  year: number | null;
  cover_url: string | null;
  is_compilation: boolean;
}

export interface BookSeriesVolume {
  title_id: number;
  title_name: string;
  poster_url: string | null;
  series_number: string | null;
  first_publication_year: number | null;
  owned: boolean;
}

export interface BookSeriesDetail {
  id: number;
  name: string;
  description: string | null;
  poster_url: string | null;
  author: { id: number; name: string } | null;
  volumes: BookSeriesVolume[];
  missing_volumes: BookSeriesMissingVolume[];
  can_fill_gaps: boolean;
}

export interface BookSeriesMissingVolume {
  ol_work_id: string;
  title: string;
  series_number: string | null;
  year: number | null;
  cover_url: string | null;
  already_wished: boolean;
}

export interface BookWishInput {
  ol_work_id: string;
  title: string;
  author?: string | null;
  cover_isbn?: string | null;
  series_id?: number | null;
  series_number?: string | null;
}

// ----------------------------- Playlists -----------------------------

export interface PlaylistSummary {
  id: number;
  name: string;
  description: string | null;
  owner_user_id: number;
  owner_username: string;
  is_owner: boolean;
  is_private: boolean;
  hero_poster_url: string | null;
  updated_at: string | null;
}

export interface SmartPlaylistSummary {
  key: string;
  name: string;
  description: string;
  is_smart: true;
  track_count: number;
  hero_poster_url: string | null;
}

export interface SmartPlaylistDetail extends SmartPlaylistSummary {
  total_duration_seconds: number;
  tracks: PlaylistTrackEntry[];
}

export interface PlaylistResume {
  playlist_track_id: number;
  track_id: number;
  position_seconds: number;
  updated_at: string | null;
}

export interface PlaylistTrackEntry {
  playlist_track_id: number;
  position: number;
  track_id: number;
  track_name: string;
  duration_seconds: number | null;
  title_id: number;
  title_name: string | null;
  poster_url: string | null;
  playable: boolean;
}

export interface PlaylistDetail {
  id: number;
  name: string;
  description: string | null;
  owner_user_id: number;
  owner_username: string;
  is_owner: boolean;
  is_private: boolean;
  hero_track_id: number | null;
  hero_poster_url: string | null;
  track_count: number;
  total_duration_seconds: number;
  created_at: string | null;
  updated_at: string | null;
  resume: PlaylistResume | null;
  tracks: PlaylistTrackEntry[];
}

/** A track returned by the library-shuffle endpoint — minimal shape for queueing. */
export interface ShuffleTrack {
  track_id: number;
  track_name: string;
  title_id: number;
  title_name: string | null;
  poster_url: string | null;
  duration_seconds: number | null;
  playable: boolean;
}

@Injectable({ providedIn: 'root' })
export class CatalogService {
  private readonly http = inject(HttpClient);

  async getHomeFeed(): Promise<HomeFeed> {
    return firstValueFrom(this.http.get<HomeFeed>('/api/v2/catalog/home'));
  }

  async getArtistRecommendations(limit: number = 30): Promise<ArtistRecommendationsResponse> {
    return firstValueFrom(
      this.http.get<ArtistRecommendationsResponse>('/api/v2/recommendations/artists', {
        params: { limit: String(limit) }
      })
    );
  }

  async dismissArtistRecommendation(mbid: string): Promise<void> {
    await firstValueFrom(
      this.http.post('/api/v2/recommendations/dismiss', { suggested_artist_mbid: mbid })
    );
  }

  async refreshArtistRecommendations(): Promise<void> {
    await firstValueFrom(
      this.http.post('/api/v2/recommendations/refresh', {})
    );
  }

  async getFeatures(): Promise<FeatureFlags> {
    return firstValueFrom(this.http.get<FeatureFlags>('/api/v2/catalog/features'));
  }

  async getTitleDetail(titleId: number): Promise<TitleDetail> {
    return firstValueFrom(this.http.get<TitleDetail>(`/api/v2/catalog/titles/${titleId}`));
  }

  async getTitles(params: TitleListParams): Promise<TitleListResponse> {
    const queryParams: Record<string, string> = { media_type: params.mediaType };
    if (params.sort) queryParams['sort'] = params.sort;
    if (params.ratings?.length) queryParams['ratings'] = params.ratings.join(',');
    if (params.playableOnly === false) queryParams['playable_only'] = 'false';
    return firstValueFrom(this.http.get<TitleListResponse>('/api/v2/catalog/titles', { params: queryParams }));
  }

  async clearProgress(transcodeId: number): Promise<void> {
    await firstValueFrom(this.http.delete(`/api/v2/playback-progress/${transcodeId}`));
  }

  async dismissMissingSeasons(titleId: number): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/catalog/dismiss-missing-seasons/${titleId}`, {}));
  }

  async getCollections(): Promise<CollectionListResponse> {
    return firstValueFrom(this.http.get<CollectionListResponse>('/api/v2/catalog/collections'));
  }

  async getCollectionDetail(collectionId: number): Promise<CollectionDetail> {
    return firstValueFrom(this.http.get<CollectionDetail>(`/api/v2/catalog/collections/${collectionId}`));
  }

  async getTags(): Promise<TagListResponse> {
    return firstValueFrom(this.http.get<TagListResponse>('/api/v2/catalog/tags'));
  }

  async getTagDetail(tagId: number): Promise<TagDetailResponse> {
    return firstValueFrom(this.http.get<TagDetailResponse>(`/api/v2/catalog/tags/${tagId}`));
  }

  /** Admin only — create a new tag and return its id. */
  async createTag(name: string, bgColor: string): Promise<{ id: number }> {
    return firstValueFrom(this.http.post<{ ok: boolean; id: number }>(
      '/api/v2/admin/tags', { name, bg_color: bgColor }));
  }

  /**
   * Admin only — replace a title's tag set with [tagIds]. The server
   * supports add/remove via /tags/{tagId}/titles/{titleId} too, but the
   * set-API is one round-trip and the title-detail page already has the
   * full current tag set in hand.
   */
  async setTitleTags(titleId: number, tagIds: number[]): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/catalog/titles/${titleId}/tags`,
      { tag_ids: tagIds }));
  }

  async getTrackTags(trackId: number): Promise<TagCard[]> {
    const resp = await firstValueFrom(
      this.http.get<{ tags: TagCard[] }>(`/api/v2/catalog/tracks/${trackId}/tags`));
    return resp.tags ?? [];
  }

  /** Replace a track's tag set in one shot (admin only). */
  async setTrackTags(trackId: number, tagIds: number[]): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/catalog/tracks/${trackId}/tags`,
      { tag_ids: tagIds }));
  }

  /**
   * Admin override for a track's BPM and/or time signature. Pass null
   * for a field to clear its current value; omit a field to leave it
   * alone. The server re-runs auto-tagging on the track so the
   * BPM-bucket / time-sig tag chips stay consistent.
   */
  async setTrackMusicTags(trackId: number, updates: {
    bpm?: number | null;
    time_signature?: string | null;
  }): Promise<void> {
    await firstValueFrom(this.http.post(
      `/api/v2/catalog/tracks/${trackId}/music-tags`, updates));
  }

  /**
   * Admin-only — rescan the album's directories and link any
   * previously-unlinked tracks whose tags match a file on disk.
   * Never touches already-linked tracks.
   */
  async rescanAlbum(titleId: number): Promise<{
    linked: number;
    skipped_already_linked: number;
    no_match: number;
    candidates_considered: number;
    files_walked: number;
    files_already_linked_elsewhere: number;
    files_wrong_album_tag: number;
    files_path_rejected: number;
    files_accepted_by_artist_position: number;
    rejected_album_tag_samples: string[];
    roots_walked: string[];
    music_root_configured: string;
    unlinked_after_rescan: Array<{
      track_id: number;
      disc_number: number;
      track_number: number;
      name: string;
    }>;
    message?: string;
  }> {
    return firstValueFrom(this.http.post<any>(
      `/api/v2/admin/albums/${titleId}/rescan`, {}));
  }

  async search(query: string, limit = 30): Promise<SearchResponse> {
    return firstValueFrom(this.http.get<SearchResponse>('/api/v2/search', { params: { q: query, limit: limit.toString() } }));
  }

  async listAdvancedSearchPresets(): Promise<AdvancedSearchPreset[]> {
    const resp = await firstValueFrom(
      this.http.get<{ presets: AdvancedSearchPreset[] }>('/api/v2/search/presets'));
    return resp.presets ?? [];
  }

  async searchTracks(filters: AdvancedTrackSearchFilters): Promise<TrackSearchHit[]> {
    const params: Record<string, string> = {};
    if (filters.query) params['q'] = filters.query;
    if (filters.bpmMin != null) params['bpm_min'] = String(filters.bpmMin);
    if (filters.bpmMax != null) params['bpm_max'] = String(filters.bpmMax);
    if (filters.timeSignature) params['time_signature'] = filters.timeSignature;
    if (filters.limit != null) params['limit'] = String(filters.limit);
    const resp = await firstValueFrom(
      this.http.get<{ tracks: TrackSearchHit[] }>('/api/v2/search/tracks', { params }));
    return resp.tracks ?? [];
  }

  async getActorDetail(personId: number): Promise<ActorDetail> {
    return firstValueFrom(this.http.get<ActorDetail>(`/api/v2/catalog/actor/${personId}`));
  }

  async getAuthorDetail(authorId: number): Promise<AuthorDetail> {
    return firstValueFrom(this.http.get<AuthorDetail>(`/api/v2/catalog/authors/${authorId}`));
  }

  async getArtistDetail(artistId: number): Promise<ArtistDetail> {
    return firstValueFrom(this.http.get<ArtistDetail>(`/api/v2/catalog/artists/${artistId}`));
  }

  /**
   * Audio landing page driver — returns the artist exploration grid
   * sorted by owned-album count (default), name, or recently-added.
   * Supports an optional substring search and a playable-only filter.
   */
  async listArtists(params: ArtistsListParams = {}): Promise<ArtistsListResponse> {
    const query: Record<string, string> = {};
    if (params.sort) query['sort'] = params.sort;
    if (params.q) query['q'] = params.q;
    if (params.playableOnly === false) query['playable_only'] = 'false';
    return firstValueFrom(this.http.get<ArtistsListResponse>('/api/v2/catalog/artists', { params: query }));
  }

  async getSeriesDetail(seriesId: number): Promise<BookSeriesDetail> {
    return firstValueFrom(this.http.get<BookSeriesDetail>(`/api/v2/catalog/series/${seriesId}`));
  }

  async getWishList(): Promise<WishListResponse> {
    return firstValueFrom(this.http.get<WishListResponse>('/api/v2/wishlist'));
  }

  async searchTmdb(query: string): Promise<TmdbSearchResponse> {
    return firstValueFrom(this.http.get<TmdbSearchResponse>('/api/v2/wishlist/search', { params: { q: query } }));
  }

  async addMediaWish(item: { tmdb_id: number; title: string; media_type: string; poster_path: string | null; release_year: number | null; popularity: number | null }): Promise<{ ok: boolean }> {
    return firstValueFrom(this.http.post<{ ok: boolean }>('/api/v2/wishlist/add', item));
  }

  async addBookWish(input: BookWishInput): Promise<{ id: number; status: string }> {
    return firstValueFrom(this.http.post<{ id: number; status: string }>('/api/v2/wishlist/books', input));
  }

  async removeBookWish(olWorkId: string): Promise<{ removed: boolean }> {
    return firstValueFrom(this.http.delete<{ removed: boolean }>(`/api/v2/wishlist/books/${olWorkId}`));
  }

  async addAlbumWish(input: AlbumWishInput): Promise<{ id: number; status: string }> {
    return firstValueFrom(this.http.post<{ id: number; status: string }>('/api/v2/wishlist/albums', input));
  }

  async removeAlbumWish(releaseGroupId: string): Promise<{ removed: boolean }> {
    return firstValueFrom(
      this.http.delete<{ removed: boolean }>(`/api/v2/wishlist/albums/${releaseGroupId}`));
  }

  async getReadingProgress(mediaItemId: number): Promise<ReadingProgress> {
    return firstValueFrom(this.http.get<ReadingProgress>(`/api/v2/reading-progress/${mediaItemId}`));
  }

  async saveReadingProgress(mediaItemId: number, cfi: string, percent: number): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/reading-progress/${mediaItemId}`, { cfi, percent }));
  }

  async wishlistSeriesGaps(seriesId: number): Promise<{ added: number; already_wished: number; error?: string }> {
    return firstValueFrom(
      this.http.post<{ added: number; already_wished: number; error?: string }>(
        `/api/v2/catalog/series/${seriesId}/wishlist-gaps`, {}));
  }

  async cancelWish(wishId: number): Promise<void> {
    await firstValueFrom(this.http.delete(`/api/v2/wishlist/${wishId}`));
  }

  async dismissWish(wishId: number): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/wishlist/${wishId}/dismiss`, {}));
  }

  async removeTranscodeWish(wishId: number): Promise<void> {
    await firstValueFrom(this.http.delete(`/api/v2/wishlist/transcode/${wishId}`));
  }

  async getTvChannels(): Promise<TvChannelListResponse> {
    return firstValueFrom(this.http.get<TvChannelListResponse>('/api/v2/catalog/live-tv/channels'));
  }

  async getCameras(): Promise<CameraListResponse> {
    return firstValueFrom(this.http.get<CameraListResponse>('/api/v2/catalog/cameras'));
  }

  // ----------------------------- Playlists -------------------------

  async listPlaylists(scope: 'all' | 'mine' = 'all'): Promise<{
    playlists: PlaylistSummary[];
    smartPlaylists: SmartPlaylistSummary[];
  }> {
    const url = scope === 'mine' ? '/api/v2/playlists/mine' : '/api/v2/playlists';
    const resp = await firstValueFrom(this.http.get<{
      playlists: PlaylistSummary[];
      smart_playlists?: SmartPlaylistSummary[];
    }>(url));
    return {
      playlists: resp.playlists ?? [],
      smartPlaylists: resp.smart_playlists ?? [],
    };
  }

  async getSmartPlaylist(key: string): Promise<SmartPlaylistDetail> {
    return firstValueFrom(
      this.http.get<SmartPlaylistDetail>(`/api/v2/playlists/smart/${encodeURIComponent(key)}`));
  }

  async setPlaylistPrivacy(id: number, isPrivate: boolean): Promise<void> {
    await firstValueFrom(this.http.post(
      `/api/v2/playlists/${id}/privacy`, { is_private: isPrivate }));
  }

  async reportPlaylistProgress(id: number, playlistTrackId: number, positionSeconds: number): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/playlists/${id}/progress`, {
      playlist_track_id: playlistTrackId,
      position_seconds: positionSeconds,
    }));
  }

  async clearPlaylistProgress(id: number): Promise<void> {
    await firstValueFrom(this.http.delete(`/api/v2/playlists/${id}/progress`));
  }

  async recordTrackCompletion(trackId: number): Promise<void> {
    await firstValueFrom(this.http.post('/api/v2/playlists/track-completed', { track_id: trackId }));
  }

  async getPlaylist(id: number): Promise<PlaylistDetail> {
    return firstValueFrom(this.http.get<PlaylistDetail>(`/api/v2/playlists/${id}`));
  }

  async createPlaylist(name: string, description: string | null): Promise<{ id: number; name: string }> {
    return firstValueFrom(this.http.post<{ id: number; name: string }>(
      '/api/v2/playlists', { name, description }));
  }

  async renamePlaylist(id: number, name: string, description: string | null): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/playlists/${id}/rename`, { name, description }));
  }

  async deletePlaylist(id: number): Promise<void> {
    await firstValueFrom(this.http.delete(`/api/v2/playlists/${id}`));
  }

  async addTracksToPlaylist(id: number, trackIds: number[]): Promise<{ added: number; playlist_track_ids: number[] }> {
    return firstValueFrom(this.http.post<{ added: number; playlist_track_ids: number[] }>(
      `/api/v2/playlists/${id}/tracks`, { track_ids: trackIds }));
  }

  async removeTrackFromPlaylist(id: number, playlistTrackId: number): Promise<void> {
    await firstValueFrom(this.http.delete(`/api/v2/playlists/${id}/tracks/${playlistTrackId}`));
  }

  async reorderPlaylist(id: number, playlistTrackIds: number[]): Promise<void> {
    await firstValueFrom(this.http.post(
      `/api/v2/playlists/${id}/reorder`, { playlist_track_ids: playlistTrackIds }));
  }

  async setPlaylistHero(id: number, trackId: number | null): Promise<void> {
    await firstValueFrom(this.http.post(`/api/v2/playlists/${id}/hero`, { track_id: trackId }));
  }

  /**
   * Fork [sourceId] into a new playlist owned by the caller. Works
   * regardless of whether the caller owns the source.
   */
  async duplicatePlaylist(sourceId: number, newName?: string): Promise<{ id: number; name: string }> {
    return firstValueFrom(this.http.post<{ id: number; name: string }>(
      `/api/v2/playlists/${sourceId}/duplicate`,
      newName ? { name: newName } : {}));
  }

  async libraryShuffle(): Promise<ShuffleTrack[]> {
    const resp = await firstValueFrom(
      this.http.get<{ tracks: ShuffleTrack[] }>('/api/v2/playlists/library-shuffle'));
    return resp.tracks ?? [];
  }

  async getFamilyVideos(params: { sort?: FamilySortMode; members?: number[]; playableOnly?: boolean } = {}): Promise<FamilyVideosResponse> {
    const queryParams: Record<string, string> = {};
    if (params.sort) queryParams['sort'] = params.sort;
    if (params.members?.length) queryParams['members'] = params.members.join(',');
    if (params.playableOnly) queryParams['playable_only'] = 'true';
    return firstValueFrom(this.http.get<FamilyVideosResponse>('/api/v2/catalog/family-videos', { params: queryParams }));
  }
}
