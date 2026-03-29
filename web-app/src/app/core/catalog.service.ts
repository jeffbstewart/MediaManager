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

export type MediaType = 'MOVIE' | 'TV' | 'PERSONAL';
export type SortMode = 'name' | 'year' | 'recent' | 'popular';

export interface TitleListParams {
  mediaType: MediaType;
  sort?: SortMode;
  ratings?: string[];
  playableOnly?: boolean;
}

export interface FeatureFlags {
  has_personal_videos: boolean;
  has_cameras: boolean;
  has_live_tv: boolean;
  is_admin: boolean;
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
  cast: CastInfo[];
  episodes: EpisodeInfo[];
  seasons: { season_number: number; acquisition_status: string }[];
  family_members: { id: number; name: string }[];
  similar_titles: CarouselTitle[];
  collection: { id: number; name: string } | null;
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
  recently_watched: CarouselTitle[];
  missing_seasons: MissingSeason[];
  features: FeatureFlags;
}

@Injectable({ providedIn: 'root' })
export class CatalogService {
  private readonly http = inject(HttpClient);

  async getHomeFeed(): Promise<HomeFeed> {
    return firstValueFrom(this.http.get<HomeFeed>('/api/v2/catalog/home'));
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
}
