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
  ActorDetail as ProtoActorDetail,
  CreditEntry as ProtoCreditEntry,
  OwnedCredit as ProtoOwnedCredit,
  ReleaseGroupType as ProtoReleaseGroupType,
  Tag as ProtoTag,
  TagListItem as ProtoTagListItem,
  TitleDetail as ProtoTitleDetail,
  Track as ProtoTrack,
  TrackArtistRef as ProtoTrackArtistRef,
  Transcode as ProtoTranscode,
} from '../proto-gen/common_pb';
import { CatalogService as CatalogServiceDesc } from '../proto-gen/catalog_pb';
import { ArtistService as ArtistServiceDesc } from '../proto-gen/artist_pb';
import { PlaylistService as PlaylistServiceDesc } from '../proto-gen/playlist_pb';
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
    const client = grpcClient(CatalogServiceDesc);
    const proto = await client.homeFeed({});
    return adaptProtoHomeFeed(proto);
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
    const client = grpcClient(CatalogServiceDesc);
    const proto = await client.getFeatures({});
    return adaptProtoFeatures(proto);
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
    const client = grpcClient(CatalogServiceDesc);
    // Legacy default for `playable_only` was true (server `@Default("true")`);
    // proto bool defaults false. Preserve the legacy semantic.
    const proto = await client.listTitles({
      type: legacyMediaTypeToProto(params.mediaType),
      sort: params.sort ?? '',
      ratings: params.ratings ?? [],
      playableOnly: params.playableOnly !== false,
    });
    return {
      titles: proto.titles.map(adaptTitleCard),
      total: Number(proto.pagination?.total ?? proto.titles.length),
      available_ratings: proto.availableRatings,
    };
  }

  async clearProgress(transcodeId: number): Promise<void> {
    await firstValueFrom(this.http.delete(`/api/v2/playback-progress/${transcodeId}`));
  }

  async dismissMissingSeasons(titleId: number): Promise<void> {
    // Omitting season_number tells the server to dismiss every
    // missing-season entry for this title at once.
    const client = grpcClient(CatalogServiceDesc);
    await client.dismissMissingSeason({ titleId: BigInt(titleId) });
  }

  async getCollections(): Promise<CollectionListResponse> {
    const client = grpcClient(CatalogServiceDesc);
    const proto = await client.listCollections({});
    return {
      collections: proto.collections.map(c => ({
        // Now the tmdb_collection_id — the SPA's /content/collection/:id
        // route accepts whatever the gRPC GetCollectionDetail RPC takes.
        id: c.tmdbCollectionId,
        name: c.name,
        poster_url: c.posterUrl ?? null,
        owned_count: c.titleCount,
        total_parts: c.totalParts,
      })),
      total: proto.collections.length,
    };
  }

  async getCollectionDetail(collectionId: number): Promise<CollectionDetail> {
    const client = grpcClient(CatalogServiceDesc);
    const proto = await client.getCollectionDetail({ tmdbCollectionId: collectionId });
    return adaptProtoCollectionDetail(collectionId, proto);
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
    const client = grpcClient(CatalogServiceDesc);
    await client.setTitleTags({
      titleId: BigInt(titleId),
      tagIds: tagIds.map(t => BigInt(t)),
    });
  }

  /**
   * Set this user's "favorited" state on a title. Idempotent — caller
   * passes the desired final value rather than relying on a server-
   * side toggle. The previous REST endpoint flipped the bit on every
   * POST and echoed back the new state; the gRPC RPC explicitly takes
   * the value, so the caller updates local UI state to whatever it
   * just sent.
   */
  async setFavorite(titleId: number, value: boolean): Promise<void> {
    const client = grpcClient(CatalogServiceDesc);
    await client.setFavorite({ titleId: BigInt(titleId), value });
  }

  /** Set this user's "hidden" state on a title. Idempotent — see setFavorite. */
  async setHidden(titleId: number, value: boolean): Promise<void> {
    const client = grpcClient(CatalogServiceDesc);
    await client.setHidden({ titleId: BigInt(titleId), value });
  }

  async getTrackTags(trackId: number): Promise<TagCard[]> {
    const client = grpcClient(CatalogServiceDesc);
    const proto = await client.listTagsForTrack({ trackId: BigInt(trackId) });
    return proto.tags.map(adaptTagListItem);
  }

  /** Replace a track's tag set in one shot (admin only). */
  async setTrackTags(trackId: number, tagIds: number[]): Promise<void> {
    const client = grpcClient(CatalogServiceDesc);
    await client.setTrackTags({
      trackId: BigInt(trackId),
      tagIds: tagIds.map(t => BigInt(t)),
    });
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
    // Proto contract per field: clearX wins; otherwise field-present =
    // set, field-absent = leave alone. Maps cleanly from the legacy
    // {undefined: leave alone, null: clear, value: set} convention.
    const req: {
      trackId: bigint;
      bpm?: number;
      timeSignature?: string;
      clearBpm?: boolean;
      clearTimeSignature?: boolean;
    } = { trackId: BigInt(trackId) };
    if (updates.bpm === null) req.clearBpm = true;
    else if (updates.bpm !== undefined) req.bpm = updates.bpm;
    if (updates.time_signature === null) req.clearTimeSignature = true;
    else if (updates.time_signature !== undefined) req.timeSignature = updates.time_signature;
    const client = grpcClient(CatalogServiceDesc);
    await client.setTrackMusicTags(req);
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
    const client = grpcClient(CatalogServiceDesc);
    const proto = await client.getActorDetail({ tmdbPersonId: personId });
    return adaptProtoActorDetail(personId, proto);
  }

  async getAuthorDetail(authorId: number): Promise<AuthorDetail> {
    const client = grpcClient(ArtistServiceDesc);
    const proto = await client.getAuthorDetail({ authorId: BigInt(authorId) });
    return adaptProtoAuthorDetail(proto);
  }

  async getArtistDetail(artistId: number): Promise<ArtistDetail> {
    const client = grpcClient(ArtistServiceDesc);
    const proto = await client.getArtistDetail({ artistId: BigInt(artistId) });
    return adaptProtoArtistDetail(proto);
  }

  /**
   * Audio landing page driver — returns the artist exploration grid
   * sorted by owned-album count (default), name, or recently-added.
   * Supports an optional substring search and a playable-only filter.
   */
  async listArtists(params: ArtistsListParams = {}): Promise<ArtistsListResponse> {
    const client = grpcClient(ArtistServiceDesc);
    const proto = await client.listArtists({
      sort: params.sort ?? '',
      q: params.q ?? '',
      // Legacy default: server treats absence as true. Proto bool defaults
      // to false, so flip undefined → true here.
      playableOnly: params.playableOnly !== false,
    });
    return {
      artists: proto.artists.map(adaptArtistsListItem),
      total: Number(proto.pagination?.total ?? proto.artists.length),
    };
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
    const client = grpcClient(PlaylistServiceDesc);
    await client.setPlaylistPrivacy({ id: BigInt(id), isPrivate });
  }

  async reportPlaylistProgress(id: number, playlistTrackId: number, positionSeconds: number): Promise<void> {
    const client = grpcClient(PlaylistServiceDesc);
    await client.reportPlaylistProgress({
      id: BigInt(id),
      playlistTrackId: BigInt(playlistTrackId),
      positionSeconds,
    });
  }

  async clearPlaylistProgress(id: number): Promise<void> {
    const client = grpcClient(PlaylistServiceDesc);
    await client.clearPlaylistProgress({ id: BigInt(id) });
  }

  async recordTrackCompletion(trackId: number): Promise<void> {
    const client = grpcClient(PlaylistServiceDesc);
    await client.recordTrackCompletion({ trackId: BigInt(trackId) });
  }

  async getPlaylist(id: number): Promise<PlaylistDetail> {
    return firstValueFrom(this.http.get<PlaylistDetail>(`/api/v2/playlists/${id}`));
  }

  async createPlaylist(name: string, description: string | null): Promise<{ id: number; name: string }> {
    const client = grpcClient(PlaylistServiceDesc);
    const proto = await client.createPlaylist({ name, description: description ?? undefined });
    return { id: Number(proto.id), name: proto.name };
  }

  async renamePlaylist(id: number, name: string, description: string | null): Promise<void> {
    const client = grpcClient(PlaylistServiceDesc);
    await client.renamePlaylist({
      id: BigInt(id),
      name,
      description: description ?? undefined,
    });
  }

  async deletePlaylist(id: number): Promise<void> {
    const client = grpcClient(PlaylistServiceDesc);
    await client.deletePlaylist({ id: BigInt(id) });
  }

  async addTracksToPlaylist(id: number, trackIds: number[]): Promise<{ added: number; playlist_track_ids: number[] }> {
    const client = grpcClient(PlaylistServiceDesc);
    const proto = await client.addTracksToPlaylist({
      id: BigInt(id),
      trackIds: trackIds.map(t => BigInt(t)),
    });
    return {
      added: proto.added,
      playlist_track_ids: proto.playlistTrackIds.map(n => Number(n)),
    };
  }

  async removeTrackFromPlaylist(id: number, playlistTrackId: number): Promise<void> {
    const client = grpcClient(PlaylistServiceDesc);
    await client.removeTrackFromPlaylist({
      id: BigInt(id),
      playlistTrackId: BigInt(playlistTrackId),
    });
  }

  async reorderPlaylist(id: number, playlistTrackIds: number[]): Promise<void> {
    const client = grpcClient(PlaylistServiceDesc);
    await client.reorderPlaylist({
      id: BigInt(id),
      playlistTrackIdsInOrder: playlistTrackIds.map(t => BigInt(t)),
    });
  }

  async setPlaylistHero(id: number, trackId: number | null): Promise<void> {
    const client = grpcClient(PlaylistServiceDesc);
    // The proto uses optional + zero-as-clear. Pass undefined to omit
    // the field (= clear); the server reads the absence as "fall back
    // to the first track's poster". Using 0 would do the same thing
    // but undefined is unambiguous wire-side.
    await client.setPlaylistHero({
      id: BigInt(id),
      trackId: trackId != null ? BigInt(trackId) : undefined,
    });
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
    // The collection link routes to /content/collection/:tmdb_collection_id
    // — that's the id the gRPC GetCollectionDetail RPC accepts. Take it
    // from Title.tmdb_collection_id (the int32 TMDB id) rather than the
    // proto Collection.id, which is the local TmdbCollection row id.
    collection: p.collection && t.tmdbCollectionId != null
      ? { id: t.tmdbCollectionId, name: p.collection.name }
      : null,
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

// MediaType enum keys on the SPA side line up exactly with the proto
// enum values (MOVIE, TV, PERSONAL, BOOK, ALBUM all share the same
// names). UNKNOWN sentinels and the "no filter" case both map to
// MEDIA_TYPE_UNKNOWN, which the server reads as "no filter".
function legacyMediaTypeToProto(mt: MediaType): ProtoMediaType {
  switch (mt) {
    case 'MOVIE': return ProtoMediaType.MOVIE;
    case 'TV': return ProtoMediaType.TV;
    case 'PERSONAL': return ProtoMediaType.PERSONAL;
    case 'BOOK': return ProtoMediaType.BOOK;
    case 'ALBUM': return ProtoMediaType.ALBUM;
    default: return ProtoMediaType.UNKNOWN;
  }
}

// Title proto → legacy TitleCard. Skips fields the grid template
// doesn't read (transcode_id, popularity, …) — they stay on the
// proto value but never need adapting.
//
// poster_url is preserved as null when the server explicitly omits
// it. The legacy template's @if (c.poster_url) gate distinguishes
// a real poster from a "render the placeholder" cell, so a generic
// fallback URL would silently flip placeholder cells into broken
// img tags.
function adaptTitleCard(t: import('../proto-gen/common_pb').Title): TitleCard {
  const card: TitleCard = {
    title_id: Number(t.id),
    title_name: t.name,
    media_type: PROTO_MEDIA_TYPE_TO_LEGACY[t.mediaType] ?? 'UNKNOWN',
    poster_url: t.posterUrl ?? null,
    release_year: t.year ?? null,
    content_rating: PROTO_CONTENT_RATING_TO_LEGACY[t.contentRating] ?? null,
    playable: t.playable,
    progress_fraction: t.progressFraction ?? null,
  };
  if (t.artistName) card.artist_name = t.artistName;
  if (t.authorName) card.author_name = t.authorName;
  return card;
}

function adaptProtoCollectionDetail(
  tmdbCollectionId: number,
  p: import('../proto-gen/common_pb').CollectionDetail,
): CollectionDetail {
  const parts: CollectionPart[] = p.items.map(it => {
    if (it.owned && it.titleId != null) {
      return {
        title_id: Number(it.titleId),
        title_name: it.name,
        poster_url: it.posterUrl ?? null,
        release_year: it.year ?? null,
        owned: true,
        playable: it.playable,
        progress_fraction: it.progressFraction ?? null,
      };
    }
    return {
      tmdb_movie_id: it.tmdbMovieId,
      title_name: it.name,
      poster_url: it.posterUrl ?? null,
      release_year: it.year ?? null,
      owned: false,
      playable: false,
      progress_fraction: null,
      wished: it.wished,
    };
  });
  return {
    id: tmdbCollectionId,
    name: p.name,
    owned_count: parts.filter(p => p.owned).length,
    total_parts: parts.length,
    parts,
  };
}

function adaptContinueWatching(t: import('../proto-gen/common_pb').Title): ContinueWatchingItem {
  // Title carries resume_position / resume_duration on the home-feed
  // continue_watching slot. Episode context (S/E numbers + name) is
  // populated only for TV titles.
  const positionSeconds = t.resumePosition?.seconds ?? 0;
  const durationSeconds = t.resumeDuration?.seconds ?? 0;
  const fraction = durationSeconds > 0
    ? Math.max(0, Math.min(1, positionSeconds / durationSeconds))
    : 0;
  const remaining = Math.max(1, Math.floor((durationSeconds - positionSeconds) / 60));
  const isEpisode = t.resumeSeasonNumber != null;
  const seasonNumber = t.resumeSeasonNumber ?? null;
  const episodeNumber = t.resumeEpisodeNumber ?? null;
  const episodeName = t.resumeEpisodeName ?? null;
  const sxxexx = isEpisode
    ? `S${String(seasonNumber).padStart(2, '0')}E${String(episodeNumber ?? 0).padStart(2, '0')}`
    : null;
  return {
    transcode_id: Number(t.transcodeId ?? 0n),
    title_id: Number(t.id),
    title_name: t.name,
    poster_url: t.posterUrl ?? null,
    position_seconds: positionSeconds,
    duration_seconds: durationSeconds,
    progress_fraction: fraction,
    time_remaining: `${remaining} min left`,
    is_episode: isEpisode,
    episode_label: isEpisode
      ? (episodeName ? `${sxxexx} — ${episodeName}` : sxxexx)
      : null,
    season_number: seasonNumber,
    episode_number: episodeNumber,
    episode_name: episodeName,
  };
}

function adaptCarouselTitle(t: import('../proto-gen/common_pb').Title): CarouselTitle {
  return {
    title_id: Number(t.id),
    title_name: t.name,
    poster_url: t.posterUrl ?? null,
    release_year: t.year ?? null,
  };
}

function adaptRecentBook(t: import('../proto-gen/common_pb').Title): RecentBook {
  return {
    title_id: Number(t.id),
    title_name: t.name,
    poster_url: t.posterUrl ?? null,
    release_year: t.year ?? null,
    author_name: t.authorName ?? null,
    series_name: t.seriesName ?? null,
    series_number: t.seriesNumber ?? null,
  };
}

function adaptRecentAlbum(t: import('../proto-gen/common_pb').Title): RecentAlbum {
  return {
    title_id: Number(t.id),
    title_name: t.name,
    poster_url: t.posterUrl ?? null,
    release_year: t.year ?? null,
    artist_name: t.artistName ?? null,
    track_count: t.trackCount ?? null,
  };
}

function adaptResumeTrack(rt: import('../proto-gen/common_pb').ResumeTrack): ResumeListeningItem {
  return {
    track_id: Number(rt.trackId),
    track_name: rt.trackName,
    title_id: Number(rt.titleId),
    title_name: rt.titleName,
    poster_url: rt.posterUrl ?? null,
    artist_name: rt.artistName ?? null,
    position_seconds: rt.position?.seconds ?? 0,
    duration_seconds: rt.duration?.seconds ?? 0,
    percent: rt.percent,
    updated_at: rt.updatedAt
      ? new Date(Number(rt.updatedAt.secondsSinceEpoch) * 1000).toISOString()
      : null,
  };
}

function adaptResumeReading(rr: import('../proto-gen/common_pb').ResumeReading): ResumeReadingItem {
  return {
    media_item_id: Number(rr.mediaItemId),
    title_id: Number(rr.titleId),
    title_name: rr.titleName,
    poster_url: rr.posterUrl ?? null,
    percent: rr.percent,
    media_format: PROTO_MEDIA_FORMAT_TO_LEGACY[rr.mediaFormat] ?? 'UNKNOWN',
    updated_at: rr.updatedAt
      ? new Date(Number(rr.updatedAt.secondsSinceEpoch) * 1000).toISOString()
      : null,
  };
}

function adaptMissingSeason(m: import('../proto-gen/common_pb').MissingSeason): MissingSeason {
  return {
    title_id: Number(m.titleId),
    title_name: m.titleName,
    poster_url: m.posterUrl ?? null,
    tmdb_id: m.tmdbId ?? null,
    // The proto MediaType enum collapses to 'MOVIE' / 'TV' / etc.; the
    // legacy field is "movie" / "tv" lowercase (TMDB convention) for
    // the missing-seasons-side dismiss link. Normalise to lowercase.
    tmdb_media_type: (PROTO_MEDIA_TYPE_TO_LEGACY[m.mediaType] ?? '').toLowerCase() || null,
    missing_seasons: m.seasons.map(s => ({ season_number: s.seasonNumber })),
  };
}

function adaptProtoFeatures(f: import('../proto-gen/catalog_pb').Features | undefined): FeatureFlags {
  return {
    has_personal_videos: f?.hasPersonalVideos ?? false,
    has_books: f?.hasBooks ?? false,
    has_music: f?.hasMusic ?? false,
    has_music_radio: f?.hasMusicRadio ?? false,
    has_cameras: f?.hasCameras ?? false,
    has_live_tv: f?.hasLiveTv ?? false,
    is_admin: f?.isAdmin ?? false,
    wish_ready_count: f?.wishReadyCount ?? 0,
    unmatched_count: f?.unmatchedCount ?? 0,
    unmatched_books_count: f?.unmatchedBooksCount ?? 0,
    unmatched_audio_count: f?.unmatchedAudioCount ?? 0,
    data_quality_count: f?.dataQualityCount ?? 0,
    open_reports_count: f?.openReportsCount ?? 0,
  };
}

function adaptProtoHomeFeed(p: import('../proto-gen/catalog_pb').HomeFeedResponse): HomeFeed {
  return {
    continue_watching: p.continueWatching.map(adaptContinueWatching),
    recently_added: p.recentlyAdded.map(adaptCarouselTitle),
    recently_added_books: p.recentlyAddedBooks.map(adaptRecentBook),
    recently_added_albums: p.recentlyAddedAlbums.map(adaptRecentAlbum),
    resume_listening: p.resumeListening.map(adaptResumeTrack),
    resume_reading: p.resumeReading.map(adaptResumeReading),
    recently_watched: p.recentlyWatched.map(adaptCarouselTitle),
    missing_seasons: p.missingSeasons.map(adaptMissingSeason),
    features: adaptProtoFeatures(p.features),
  };
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

// Proto wraps TMDB poster paths as a full URL
// (https://image.tmdb.org/t/p/w500/abc.jpg). Legacy callers pass the
// raw path through tmdbImageUrl(), which would double-prefix and break
// the URL. Strip the wrapper back to "/abc.jpg" so the existing helper
// keeps producing /proxy/tmdb/{size}/abc.jpg.
function stripTmdbWrapper(url: string | undefined): string | null {
  if (!url) return null;
  const stripped = url.replace(/^https?:\/\/image\.tmdb\.org\/t\/p\/[^/]+/, '');
  return stripped || null;
}

// proto/time.proto Month enum values are 1..12 (with 0 = UNKNOWN), so
// we can use the numeric value directly. Returns "YYYY-MM-DD" matching
// what the legacy REST endpoint serialised from the DB column.
function calendarDateToIsoDate(d: { year: number; month: number; day: number } | undefined): string | null {
  if (!d || d.month < 1 || d.day < 1) return null;
  const mm = String(d.month).padStart(2, '0');
  const dd = String(d.day).padStart(2, '0');
  return `${d.year}-${mm}-${dd}`;
}

function adaptActorOwnedTitle(c: ProtoOwnedCredit): ActorOwnedTitle {
  const t = c.title!;
  return {
    title_id: Number(t.id),
    title_name: t.name,
    poster_url: t.posterUrl ?? `/posters/w185/${t.id}`,
    release_year: t.year ?? null,
    character_name: c.characterName ?? null,
  };
}

function adaptActorCredit(c: ProtoCreditEntry): ActorCredit {
  return {
    tmdb_id: c.tmdbId,
    title: c.title,
    media_type: PROTO_MEDIA_TYPE_TO_LEGACY[c.mediaType] ?? 'UNKNOWN',
    character_name: c.characterName ?? null,
    release_year: c.releaseYear ?? null,
    poster_path: stripTmdbWrapper(c.posterUrl),
    popularity: c.popularity,
    already_wished: c.wished,
  };
}

// Headshot URL is constructed client-side from the entity id when the
// server reports has_headshot=true. The server endpoints
// (/author-headshots/{id}, /artist-headshots/{id}) handle the actual
// source resolution — they serve the cached image when present and
// fall back to OL (server-side fetch + cache) for authors. The proto
// never carries the URL so the wire stays decoupled from the route.
function authorHeadshotUrl(authorId: number, hasHeadshot: boolean): string | null {
  return hasHeadshot ? `/author-headshots/${authorId}` : null;
}

function artistHeadshotUrl(artistId: number, hasHeadshot: boolean): string | null {
  return hasHeadshot ? `/artist-headshots/${artistId}` : null;
}

// Proto ReleaseGroupType enum → free-text "primary_type" the legacy
// REST server emitted. Lossy by design — the SPA doesn't render the
// value today; it ships through to keep the contract intact for any
// future template binding.
const PROTO_RELEASE_GROUP_TYPE_TO_LEGACY: Record<ProtoReleaseGroupType, string | null> = {
  [ProtoReleaseGroupType.UNKNOWN]: null,
  [ProtoReleaseGroupType.ALBUM]: 'Album',
  [ProtoReleaseGroupType.EP]: 'EP',
  [ProtoReleaseGroupType.SINGLE]: 'Single',
  [ProtoReleaseGroupType.COMPILATION]: 'Compilation',
  [ProtoReleaseGroupType.SOUNDTRACK]: 'Soundtrack',
  [ProtoReleaseGroupType.LIVE]: 'Live',
  [ProtoReleaseGroupType.REMIX]: 'Remix',
  [ProtoReleaseGroupType.OTHER]: 'Other',
};

function adaptProtoAuthorDetail(p: import('../proto-gen/common_pb').AuthorDetail): AuthorDetail {
  const a = p.author!;
  const id = Number(a.id);
  return {
    id,
    name: a.name,
    biography: a.biography ?? null,
    headshot_url: authorHeadshotUrl(id, a.hasHeadshot),
    birth_date: calendarDateToIsoDate(a.birthDate),
    death_date: calendarDateToIsoDate(a.deathDate),
    open_library_author_id: a.openlibraryId ?? null,
    owned_books: p.ownedBooks.map(adaptAuthorOwnedBook),
    other_works: p.otherWorks.map(adaptAuthorOtherWork),
  };
}

function adaptAuthorOwnedBook(t: import('../proto-gen/common_pb').Title): AuthorOwnedBook {
  return {
    title_id: Number(t.id),
    title_name: t.name,
    poster_url: t.posterUrl ?? null,
    release_year: t.year ?? null,
    series_name: t.seriesName ?? null,
    series_number: t.seriesNumber ?? null,
  };
}

function adaptAuthorOtherWork(b: import('../proto-gen/common_pb').BibliographyEntry): AuthorOtherWork {
  return {
    ol_work_id: b.openlibraryWorkId,
    title: b.name,
    year: b.year ?? null,
    // Cover comes from /proxy/ol/olid/{ol_work_id}/M (server-side fetch
    // + cache; client never talks to OL directly).
    cover_url: `/proxy/ol/olid/${b.openlibraryWorkId}/M`,
    series_raw: b.seriesRaw ?? null,
    already_wished: b.alreadyWished,
  };
}

function adaptProtoArtistDetail(p: import('../proto-gen/common_pb').ArtistDetail): ArtistDetail {
  const a = p.artist!;
  const id = Number(a.id);
  return {
    id,
    name: a.name,
    sort_name: a.sortName ?? '',
    artist_type: PROTO_ARTIST_TYPE_TO_LEGACY[a.artistType] ?? 'OTHER',
    biography: p.biography ?? null,
    headshot_url: artistHeadshotUrl(id, a.hasHeadshot),
    begin_date: calendarDateToIsoDate(a.beginDate),
    end_date: calendarDateToIsoDate(a.endDate),
    musicbrainz_artist_id: a.musicbrainzArtistId ?? null,
    owned_albums: p.ownedAlbums.map(adaptArtistOwnedAlbum),
    other_works: p.otherWorks.map(adaptArtistOtherWork),
    band_members: p.members.map(adaptArtistMember),
    member_of: p.memberOf.map(adaptArtistMember),
  };
}

function adaptArtistOwnedAlbum(t: import('../proto-gen/common_pb').Title): ArtistOwnedAlbum {
  return {
    title_id: Number(t.id),
    title_name: t.name,
    poster_url: t.posterUrl ?? null,
    release_year: t.year ?? null,
    track_count: t.trackCount ?? null,
  };
}

function adaptArtistOtherWork(d: import('../proto-gen/common_pb').DiscographyEntry): ArtistOtherWork {
  return {
    release_group_id: d.musicbrainzReleaseGroupId,
    title: d.name,
    year: d.year ?? null,
    primary_type: PROTO_RELEASE_GROUP_TYPE_TO_LEGACY[d.releaseGroupType] ?? null,
    secondary_types: d.secondaryTypes,
    is_compilation: d.isCompilation,
    cover_url: `/proxy/caa/release-group/${d.musicbrainzReleaseGroupId}/front-250`,
    already_wished: d.alreadyWished,
  };
}

function adaptArtistsListItem(a: import('../proto-gen/artist_pb').ArtistListItem): ArtistsListItem {
  const id = Number(a.id);
  const fallbackTitleId = a.fallbackAlbumTitleId != null ? Number(a.fallbackAlbumTitleId) : null;
  return {
    id,
    name: a.name,
    sort_name: a.sortName ?? null,
    artist_type: PROTO_ARTIST_TYPE_TO_LEGACY[a.artistType] ?? 'OTHER',
    headshot_url: artistHeadshotUrl(id, a.hasHeadshot),
    album_count: a.ownedAlbumCount,
    fallback_poster_url: fallbackTitleId != null ? `/posters/w185/${fallbackTitleId}` : null,
  };
}

function adaptArtistMember(m: import('../proto-gen/common_pb').ArtistMemberEntry): ArtistMembershipEntry {
  return {
    id: Number(m.artistId),
    name: m.name,
    artist_type: PROTO_ARTIST_TYPE_TO_LEGACY[m.artistType] ?? 'OTHER',
    begin_date: calendarDateToIsoDate(m.beginDate),
    end_date: calendarDateToIsoDate(m.endDate),
    instruments: m.instruments ?? null,
  };
}

function adaptProtoActorDetail(personId: number, p: ProtoActorDetail): ActorDetail {
  return {
    person_id: personId,
    name: p.name,
    biography: p.biography ?? null,
    birthday: calendarDateToIsoDate(p.birthday),
    deathday: calendarDateToIsoDate(p.deathday),
    place_of_birth: p.placeOfBirth ?? null,
    known_for: p.knownForDepartment ?? null,
    profile_path: stripTmdbWrapper(p.headshotUrl),
    owned_titles: p.ownedTitles.map(adaptActorOwnedTitle),
    other_works: p.otherWorks.map(adaptActorCredit),
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
