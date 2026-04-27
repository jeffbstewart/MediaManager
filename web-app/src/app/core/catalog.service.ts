import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import {
  AcquisitionStatus as ProtoAcquisitionStatus,
  AdminMediaItem as ProtoAdminMediaItem,
  Album as ProtoAlbum,
  AlbumPersonnelEntry as ProtoAlbumPersonnelEntry,
  Artist as ProtoArtist,
  ArtistType as ProtoArtistType,
  Author as ProtoAuthor,
  BookDetail as ProtoBookDetail,
  CastMember as ProtoCastMember,
  Episode as ProtoEpisode,
  MediaFormat as ProtoMediaFormat,
  MediaType as ProtoMediaType,
  ContentRating as ProtoContentRating,
  PersonnelRole as ProtoPersonnelRole,
  ReadableEdition as ProtoReadableEdition,
  Season as ProtoSeason,
  SimilarTitle as ProtoSimilarTitle,
  Tag as ProtoTag,
  TagListItem as ProtoTagListItem,
  TitleDetail as ProtoTitleDetail,
  Track as ProtoTrack,
  TrackArtistRef as ProtoTrackArtistRef,
  Transcode as ProtoTranscode,
} from '../proto-gen/common_pb';
import { CatalogService as CatalogServiceDesc } from '../proto-gen/catalog_pb';
import { grpcClient } from './grpc-client';

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
  artist_name: string | null;
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

  /**
   * Mint a 12-hour signed token authorising an unauthenticated fetch
   * of this title's poster via `/public/album-art/{token}`. Used by the
   * audio player's MediaSession integration so iOS / macOS lock-screen
   * now-playing UI can render album art (the OS-level fetch doesn't
   * share the browser's auth cookies).
   */
  async getPublicArtToken(titleId: number): Promise<{ token: string; ttl_seconds: number }> {
    return firstValueFrom(this.http.get<{ token: string; ttl_seconds: number }>(
      '/api/v2/public-art-token', { params: { title_id: String(titleId) } }
    ));
  }

  async getTitleDetail(titleId: number): Promise<TitleDetail> {
    // Every media type now flows through the proto gRPC-JSON wire
    // (single source of truth: proto/catalog.proto + common.proto).
    // The proto-to-legacy adapter keeps component types unchanged for
    // now; it can collapse to a typed pass-through once consumers
    // migrate off the legacy TitleDetail interface.
    const client = grpcClient(CatalogServiceDesc);
    const proto = await client.getTitleDetail({ titleId: BigInt(titleId) });
    return adaptProtoTitleDetail(proto);
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
    const client = grpcClient(CatalogServiceDesc);
    const proto = await client.listTags({});
    return {
      tags: proto.tags.map(adaptTagListItem),
      total: proto.tags.length,
    };
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

// ----------------------------------------------------------------------------
// Proto → legacy adapter for getTitleDetail.
//
// Transition shim: the rest of the codebase still types against the
// hand-written TitleDetail interface above. Each adapter field is one
// renaming or coercion away from the proto shape. As callers migrate to
// the proto-derived types directly, the corresponding mapping line
// disappears. When the legacy interface is empty, the shim deletes
// itself and getTitleDetail returns ProtoTitleDetail.
// ----------------------------------------------------------------------------

const PROTO_MEDIA_TYPE_TO_LEGACY: Record<ProtoMediaType, string> = {
  [ProtoMediaType.UNKNOWN]: 'UNKNOWN',
  [ProtoMediaType.MOVIE]: 'MOVIE',
  [ProtoMediaType.TV]: 'TV',
  [ProtoMediaType.PERSONAL]: 'PERSONAL',
  [ProtoMediaType.BOOK]: 'BOOK',
  [ProtoMediaType.ALBUM]: 'ALBUM',
};

const PROTO_MEDIA_FORMAT_TO_LEGACY: Record<ProtoMediaFormat, string> = {
  [ProtoMediaFormat.UNKNOWN]: 'UNKNOWN',
  [ProtoMediaFormat.DVD]: 'DVD',
  [ProtoMediaFormat.BLURAY]: 'BLURAY',
  [ProtoMediaFormat.UHD_BLURAY]: 'UHD_BLURAY',
  [ProtoMediaFormat.HD_DVD]: 'HD_DVD',
  [ProtoMediaFormat.MASS_MARKET_PAPERBACK]: 'MASS_MARKET_PAPERBACK',
  [ProtoMediaFormat.TRADE_PAPERBACK]: 'TRADE_PAPERBACK',
  [ProtoMediaFormat.HARDBACK]: 'HARDBACK',
  [ProtoMediaFormat.EBOOK_EPUB]: 'EBOOK_EPUB',
  [ProtoMediaFormat.EBOOK_PDF]: 'EBOOK_PDF',
  [ProtoMediaFormat.AUDIOBOOK_CD]: 'AUDIOBOOK_CD',
  [ProtoMediaFormat.AUDIOBOOK_DIGITAL]: 'AUDIOBOOK_DIGITAL',
  [ProtoMediaFormat.CD]: 'CD',
  [ProtoMediaFormat.VINYL_LP]: 'VINYL_LP',
  [ProtoMediaFormat.AUDIO_FLAC]: 'AUDIO_FLAC',
  [ProtoMediaFormat.AUDIO_MP3]: 'AUDIO_MP3',
  [ProtoMediaFormat.AUDIO_AAC]: 'AUDIO_AAC',
  [ProtoMediaFormat.AUDIO_OGG]: 'AUDIO_OGG',
  [ProtoMediaFormat.AUDIO_WAV]: 'AUDIO_WAV',
  [ProtoMediaFormat.OTHER]: 'OTHER',
};

const PROTO_CONTENT_RATING_TO_LEGACY: Partial<Record<ProtoContentRating, string>> = {
  [ProtoContentRating.G]: 'G',
  [ProtoContentRating.PG]: 'PG',
  [ProtoContentRating.PG_13]: 'PG-13',
  [ProtoContentRating.R]: 'R',
  [ProtoContentRating.NC_17]: 'NC-17',
  [ProtoContentRating.TV_Y]: 'TV-Y',
  [ProtoContentRating.TV_Y7]: 'TV-Y7',
  [ProtoContentRating.TV_G]: 'TV-G',
  [ProtoContentRating.TV_PG]: 'TV-PG',
  [ProtoContentRating.TV_14]: 'TV-14',
  [ProtoContentRating.TV_MA]: 'TV-MA',
  [ProtoContentRating.NR]: 'NR',
};

// Legacy `acquisition_status` is the bare enum name from the Kotlin
// `AcquisitionStatus` enum (e.g. "OWNED", "NEEDS_ASSISTANCE"). The proto
// names carry the `ACQUISITION_STATUS_` prefix on the wire but
// @bufbuild/protobuf strips it on the TS enum, so the value names line up
// with what the title-detail template renders inline.
const PROTO_ACQUISITION_STATUS_TO_LEGACY: Record<ProtoAcquisitionStatus, string> = {
  [ProtoAcquisitionStatus.UNKNOWN]: 'UNKNOWN',
  [ProtoAcquisitionStatus.NOT_AVAILABLE]: 'NOT_AVAILABLE',
  [ProtoAcquisitionStatus.REJECTED]: 'REJECTED',
  [ProtoAcquisitionStatus.ORDERED]: 'ORDERED',
  [ProtoAcquisitionStatus.OWNED]: 'OWNED',
  [ProtoAcquisitionStatus.NEEDS_ASSISTANCE]: 'NEEDS_ASSISTANCE',
};

// Same prefix-stripping convention. SPA consumers compare
// `artist_type === 'PERSON'` and similar — bare uppercase names.
const PROTO_ARTIST_TYPE_TO_LEGACY: Record<ProtoArtistType, string> = {
  [ProtoArtistType.UNKNOWN]: 'OTHER',
  [ProtoArtistType.PERSON]: 'PERSON',
  [ProtoArtistType.GROUP]: 'GROUP',
  [ProtoArtistType.ORCHESTRA]: 'ORCHESTRA',
  [ProtoArtistType.CHOIR]: 'CHOIR',
  [ProtoArtistType.OTHER]: 'OTHER',
};

// AlbumPersonnelEntry.role rides on the `CreditRole` Kotlin enum on the
// REST side. Proto enum is `PersonnelRole` with the `PERSONNEL_ROLE_`
// prefix stripped by the TS generator.
const PROTO_PERSONNEL_ROLE_TO_LEGACY: Record<ProtoPersonnelRole, string> = {
  [ProtoPersonnelRole.UNKNOWN]: 'OTHER',
  [ProtoPersonnelRole.PERFORMER]: 'PERFORMER',
  [ProtoPersonnelRole.COMPOSER]: 'COMPOSER',
  [ProtoPersonnelRole.PRODUCER]: 'PRODUCER',
  [ProtoPersonnelRole.ENGINEER]: 'ENGINEER',
  [ProtoPersonnelRole.MIXER]: 'MIXER',
  [ProtoPersonnelRole.OTHER]: 'OTHER',
};

function adaptProtoTitleDetail(p: ProtoTitleDetail): TitleDetail {
  const t = p.title;
  if (!t) {
    throw new Error('GetTitleDetail returned no title');
  }
  // Proto carries a single PlaybackProgress at the top level (the
  // server picks the "best" transcode); legacy callers expect
  // position_seconds on the matching transcode row, so fold it in.
  const pp = p.playbackProgress;
  // Keyed by stringified bigint — Map.get(bigint) doesn't unify with
  // numeric / string keys, so just normalise on the way in and out.
  const positionByTranscode = new Map<string, { position: number; duration: number | undefined }>();
  if (pp) {
    positionByTranscode.set(String(pp.transcodeId), {
      position: pp.position?.seconds ?? 0,
      duration: pp.duration?.seconds,
    });
  }

  const album = p.album;
  const book = p.book;
  return {
    title_id: Number(t.id),
    title_name: t.name,
    media_type: PROTO_MEDIA_TYPE_TO_LEGACY[t.mediaType] ?? 'UNKNOWN',
    release_year: t.year ?? null,
    description: t.description ?? null,
    content_rating: PROTO_CONTENT_RATING_TO_LEGACY[t.contentRating] ?? null,
    // Server-side image URLs are deprecated on the proto. Until the
    // image-id-only flow is wired through ImageService, the title page
    // still expects a URL — fall back to the canonical /posters/w500/:id
    // path the existing image servlet serves.
    poster_url: `/posters/w500/${t.id}`,
    backdrop_url: `/backdrops/${t.id}`,
    event_date: null,
    is_starred: p.isFavorite,
    is_hidden: p.isHidden,
    genres: p.genres.map(g => g.name),
    tags: p.tags.map(adaptTag),
    formats: p.displayFormats,
    admin_media_items: p.adminMediaItems.map(adaptAdminItem),
    transcodes: p.transcodes.map(tc => adaptTranscode(tc, positionByTranscode.get(String(tc.id)))),
    cast: p.cast.map(adaptCast),
    episodes: p.episodes.map(adaptEpisode),
    seasons: p.seasons.map(adaptSeason),
    family_members: p.familyMembersFull.map(fm => ({ id: Number(fm.id), name: fm.name })),
    similar_titles: p.similarTitles.map(adaptSimilar),
    collection: p.collection ? { id: Number(p.collection.id), name: p.collection.name } : null,
    readable_editions: p.readableEditions.map(adaptReadableEdition),
    // Album-specific. Folded into the legacy flat shape from `p.album`.
    artists: album?.albumArtists.map(adaptArtist) ?? undefined,
    tracks: album?.tracks.map(adaptTrack) ?? undefined,
    track_count: album?.trackCount ?? null,
    total_duration_seconds: album?.totalDuration?.seconds ?? null,
    label: album?.label ?? null,
    musicbrainz_release_group_id: album?.musicbrainzReleaseGroupId ?? null,
    musicbrainz_release_id: album?.musicbrainzReleaseId ?? null,
    personnel: album?.personnel.map(adaptPersonnel) ?? undefined,
    // Book-specific. Folded in from `p.book`.
    authors: book?.authors.map(adaptAuthor) ?? undefined,
    book_series: book?.bookSeries ? adaptBookSeries(book.bookSeries) : null,
    page_count: book?.pageCount ?? null,
    first_publication_year: book?.firstPublicationYear ?? null,
    open_library_work_id: book?.openLibraryWorkId ?? null,
  };
}

function adaptTag(t: ProtoTag): { id: number; name: string; bg_color: string; text_color: string } {
  return {
    id: Number(t.id),
    name: t.name,
    bg_color: t.color?.hex ?? '#000000',
    // Server doesn't send text_color over the wire — pick black/white
    // for legibility against bg_color via a quick luminance check.
    text_color: pickTextColor(t.color?.hex ?? '#000000'),
  };
}

function adaptAdminItem(a: ProtoAdminMediaItem): { media_item_id: number; media_format: string; upc: string | null } {
  return {
    media_item_id: Number(a.mediaItemId),
    media_format: PROTO_MEDIA_FORMAT_TO_LEGACY[a.mediaFormat] ?? 'UNKNOWN',
    upc: a.upc ?? null,
  };
}

function adaptTranscode(
  tc: ProtoTranscode,
  progress?: { position: number; duration: number | undefined },
): TranscodeInfo {
  return {
    transcode_id: Number(tc.id),
    file_name: '',  // not on the proto — legacy field used only for diagnostics
    playable: tc.playable,
    media_format: PROTO_MEDIA_FORMAT_TO_LEGACY[tc.mediaFormat] ?? null,
    season_number: tc.seasonNumber,
    episode_number: tc.episodeNumber,
    episode_name: tc.episodeName,
    position_seconds: progress?.position,
    duration_seconds: progress?.duration,
  };
}

function adaptCast(c: ProtoCastMember): CastInfo {
  return {
    id: c.tmdbPersonId,
    tmdb_person_id: c.tmdbPersonId,
    name: c.name,
    character_name: c.characterName ?? null,
    headshot_url: c.headshotUrl ?? null,
  };
}

function adaptSimilar(s: ProtoSimilarTitle): CarouselTitle {
  return {
    title_id: Number(s.titleId),
    title_name: s.titleName,
    poster_url: `/posters/w185/${s.titleId}`,
    release_year: s.releaseYear ?? null,
  };
}

function adaptSeason(s: ProtoSeason): { season_number: number; acquisition_status: string } {
  return {
    season_number: s.seasonNumber,
    acquisition_status:
      PROTO_ACQUISITION_STATUS_TO_LEGACY[s.acquisitionStatus] ?? 'UNKNOWN',
  };
}

function adaptEpisode(e: ProtoEpisode): EpisodeInfo {
  return {
    id: Number(e.episodeId),
    season_number: e.seasonNumber,
    episode_number: e.episodeNumber,
    name: e.name ?? null,
    transcode_id: e.transcodeId != null ? Number(e.transcodeId) : null,
    playable: e.playable,
    position_seconds: e.resumePosition?.seconds ?? null,
    duration_seconds: e.duration?.seconds ?? null,
  };
}

function adaptArtist(a: ProtoArtist): { id: number; name: string; artist_type: string } {
  return {
    id: Number(a.id),
    name: a.name,
    artist_type: PROTO_ARTIST_TYPE_TO_LEGACY[a.artistType] ?? 'OTHER',
  };
}

function adaptTrackArtist(a: ProtoTrackArtistRef): { id: number; name: string } {
  return { id: Number(a.id), name: a.name };
}

// Track-row tags use the same TagCard shape as title-level tags but the
// proto Track only carries id / name / color (no `title_count` — that
// field is only meaningful on the tag-list endpoint). Default to 0 to
// satisfy the structural shape; the title-detail template never reads it.
function adaptTrackTag(t: ProtoTag): TagCard {
  return {
    id: Number(t.id),
    name: t.name,
    bg_color: t.color?.hex ?? '#666666',
    text_color: pickTextColor(t.color?.hex ?? '#666666'),
    title_count: 0,
  };
}

function adaptTrack(tr: ProtoTrack): AlbumTrack {
  return {
    track_id: Number(tr.id),
    disc_number: tr.discNumber,
    track_number: tr.trackNumber,
    name: tr.name,
    duration_seconds: tr.duration?.seconds ?? null,
    track_artists: tr.trackArtists.map(adaptTrackArtist),
    tags: tr.tags.map(adaptTrackTag),
    bpm: tr.bpm ?? null,
    time_signature: tr.timeSignature ?? null,
  };
}

function adaptAuthor(a: ProtoAuthor): { id: number; name: string } {
  return { id: Number(a.id), name: a.name };
}

function adaptTagListItem(t: ProtoTagListItem): TagCard {
  const bg = t.color?.hex ?? '#666666';
  return {
    id: Number(t.id),
    name: t.name,
    bg_color: bg,
    text_color: pickTextColor(bg),
    title_count: t.titleCount,
  };
}

// BookSeriesRef.number rides as a string on the wire to round-trip
// values like "1.5" / "2a" that some series use; the legacy interface
// types it as `number | null`. parseFloat covers numerics — non-numeric
// suffixes like "2a" lose the "a" but that's a rare edge case the
// legacy contract couldn't represent in the first place.
function adaptBookSeries(b: import('../proto-gen/common_pb').BookSeriesRef): {
  id: number; name: string; number: number | null;
} {
  let num: number | null = null;
  if (b.number) {
    const parsed = parseFloat(b.number);
    if (!Number.isNaN(parsed)) num = parsed;
  }
  return { id: Number(b.id), name: b.name, number: num };
}

// Proto stores the per-format reading locator in a oneof: epub_cfi as a
// string, pdf_page as an integer page number. Legacy shape collapses
// both into a single `cfi: string | null` field. PDF pages serialise to
// "/page/N" — that's the format ReadingProgressService accepts on the
// way back in, so the round-trip stays lossless.
function adaptReadableEdition(e: ProtoReadableEdition): ReadableEdition {
  let cfi: string | null = null;
  if (e.locator.case === 'epubCfi') {
    cfi = e.locator.value;
  } else if (e.locator.case === 'pdfPage') {
    cfi = `/page/${e.locator.value}`;
  }
  return {
    media_item_id: Number(e.mediaItemId),
    media_format: PROTO_MEDIA_FORMAT_TO_LEGACY[e.mediaFormat] ?? 'UNKNOWN',
    percent: e.percent,
    cfi,
    updated_at: e.updatedAt
      ? new Date(Number(e.updatedAt.secondsSinceEpoch) * 1000).toISOString()
      : null,
  };
}

// Personnel.track_id is `optional int64` on the wire but `number` on the
// legacy interface — most consumers only use it as part of a @for track
// key, so a 0 sentinel is safe when the credit is album-wide rather
// than track-specific. Same for track_name, which renders as "on …" only
// when set.
function adaptPersonnel(p: ProtoAlbumPersonnelEntry): AlbumPersonnelEntry {
  return {
    artist_id: Number(p.artistId),
    artist_name: p.artistName,
    role: PROTO_PERSONNEL_ROLE_TO_LEGACY[p.role] ?? 'OTHER',
    instrument: p.instrument ?? null,
    track_id: p.trackId != null ? Number(p.trackId) : 0,
    track_name: p.trackName ?? null,
    disc_number: null,
    track_number: null,
  };
}

/**
 * YIQ luminance test — dark-on-light vs light-on-dark for a tag chip
 * given just its bg color. Mirrors what the server's textColor() helper
 * computes server-side; once the proto carries text_color this goes
 * away.
 */
function pickTextColor(hex: string): string {
  const h = hex.replace('#', '');
  if (h.length !== 6) return '#ffffff';
  const r = parseInt(h.slice(0, 2), 16);
  const g = parseInt(h.slice(2, 4), 16);
  const b = parseInt(h.slice(4, 6), 16);
  const yiq = (r * 299 + g * 587 + b * 114) / 1000;
  return yiq >= 128 ? '#000000' : '#ffffff';
}
