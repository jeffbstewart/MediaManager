// Typed proto fixtures for the gRPC ListTitles response, keyed by
// MediaType. mock-backend's gRPC dispatch decodes the request,
// reads the type filter, and returns the corresponding fixture.
//
// Mirrors fixtures/catalog/titles.{movies,tv,books}.json so the
// suite asserts the same content whether the page lands via REST
// or gRPC during the migration window.

import { create } from '@bufbuild/protobuf';
import {
  ContentRating,
  MediaType,
  Quality,
} from '../../src/app/proto-gen/common_pb';
import {
  TitlePageResponseSchema,
  type TitlePageResponse,
} from '../../src/app/proto-gen/catalog_pb';

export const moviesPage: TitlePageResponse = create(TitlePageResponseSchema, {
  titles: [
    { id: 100n, name: 'The Matrix',         mediaType: MediaType.MOVIE, year: 1999, posterUrl: '/posters/w185/100', contentRating: ContentRating.R,     quality: Quality.HD, playable: true },
    { id: 101n, name: 'Inception',          mediaType: MediaType.MOVIE, year: 2010, posterUrl: '/posters/w185/101', contentRating: ContentRating.PG_13, quality: Quality.HD, playable: true, progressFraction: 0.34 },
    { id: 102n, name: 'Interstellar',       mediaType: MediaType.MOVIE, year: 2014, posterUrl: '/posters/w185/102', contentRating: ContentRating.PG_13, quality: Quality.HD, playable: true },
    { id: 103n, name: 'Blade Runner 2049',  mediaType: MediaType.MOVIE, year: 2017, posterUrl: undefined,           contentRating: ContentRating.R,     quality: Quality.HD, playable: false },
  ],
  pagination: { total: 4, page: 1, limit: 60, totalPages: 1 },
  availableRatings: ['G', 'PG', 'PG-13', 'R'],
});

export const tvPage: TitlePageResponse = create(TitlePageResponseSchema, {
  titles: [
    { id: 200n, name: 'Breaking Bad', mediaType: MediaType.TV, year: 2008, posterUrl: '/posters/w185/200', contentRating: ContentRating.TV_MA, quality: Quality.HD, playable: true, progressFraction: 0.62 },
    { id: 201n, name: 'The Expanse',  mediaType: MediaType.TV, year: 2015, posterUrl: '/posters/w185/201', contentRating: ContentRating.TV_14, quality: Quality.HD, playable: true },
    { id: 202n, name: 'Severance',    mediaType: MediaType.TV, year: 2022, posterUrl: '/posters/w185/202', contentRating: ContentRating.TV_MA, quality: Quality.HD, playable: true },
  ],
  pagination: { total: 3, page: 1, limit: 60, totalPages: 1 },
  availableRatings: ['TV-G', 'TV-PG', 'TV-14', 'TV-MA'],
});

export const booksPage: TitlePageResponse = create(TitlePageResponseSchema, {
  titles: [
    { id: 300n, name: 'Dune',                       mediaType: MediaType.BOOK, year: 1965, posterUrl: '/posters/w185/300', quality: Quality.HD, playable: true, authorName: 'Frank Herbert' },
    { id: 301n, name: 'The Left Hand of Darkness',  mediaType: MediaType.BOOK, year: 1969, posterUrl: '/posters/w185/301', quality: Quality.HD, playable: true, progressFraction: 0.12, authorName: 'Ursula K. Le Guin' },
  ],
  pagination: { total: 2, page: 1, limit: 60, totalPages: 1 },
});
