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
  poster_url: string | null;
  release_year: number | null;
  content_rating: string | null;
  playable: boolean;
  progress_fraction: number | null;
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
export type SortMode = 'name' | 'year' | 'recent' | 'popular';

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
  type: 'movie' | 'tv' | 'personal' | 'actor' | 'collection' | 'tag' | 'channel' | 'camera';
  name: string;
  title_id?: number;
  person_id?: number;
  collection_id?: number;
  tag_id?: number;
  channel_id?: number;
  camera_id?: number;
  poster_url?: string | null;
  headshot_url?: string | null;
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

@Injectable({ providedIn: 'root' })
export class CatalogService {
  private readonly http = inject(HttpClient);

  async getHomeFeed(): Promise<HomeFeed> {
    return firstValueFrom(this.http.get<HomeFeed>('/api/v2/catalog/home'));
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

  async search(query: string, limit = 30): Promise<SearchResponse> {
    return firstValueFrom(this.http.get<SearchResponse>('/api/v2/search', { params: { q: query, limit: limit.toString() } }));
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

  async getFamilyVideos(params: { sort?: FamilySortMode; members?: number[]; playableOnly?: boolean } = {}): Promise<FamilyVideosResponse> {
    const queryParams: Record<string, string> = {};
    if (params.sort) queryParams['sort'] = params.sort;
    if (params.members?.length) queryParams['members'] = params.members.join(',');
    if (params.playableOnly) queryParams['playable_only'] = 'true';
    return firstValueFrom(this.http.get<FamilyVideosResponse>('/api/v2/catalog/family-videos', { params: queryParams }));
  }
}
