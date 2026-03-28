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

export interface HomeFeed {
  continue_watching: ContinueWatchingItem[];
  recently_added: CarouselTitle[];
  recently_watched: CarouselTitle[];
  missing_seasons: MissingSeason[];
}

@Injectable({ providedIn: 'root' })
export class CatalogService {
  private readonly http = inject(HttpClient);

  async getHomeFeed(): Promise<HomeFeed> {
    return firstValueFrom(this.http.get<HomeFeed>('/api/v2/catalog/home'));
  }
}
