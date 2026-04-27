// Typed proto fixtures for the gRPC HomeFeed and GetFeatures RPCs.
// Mirrors fixtures/catalog/home.{populated,empty}.json and
// catalog/features.{viewer,admin}.json so SPA tests assert the same
// content whether the home page lands via REST or gRPC during the
// migration window.

import { create } from '@bufbuild/protobuf';
import {
  ContentRating,
  MediaFormat,
  MediaType,
  Quality,
} from '../../src/app/proto-gen/common_pb';
import {
  FeaturesSchema,
  HomeFeedResponseSchema,
  type Features,
  type HomeFeedResponse,
} from '../../src/app/proto-gen/catalog_pb';

const VIEWER_FEATURES_BASE = {
  hasPersonalVideos: true,
  hasBooks: true,
  hasMusic: true,
  hasMusicRadio: true,
  hasCameras: true,
  hasLiveTv: true,
  isAdmin: false,
  wishReadyCount: 0,
  unmatchedCount: 0,
  unmatchedBooksCount: 0,
  unmatchedAudioCount: 0,
  dataQualityCount: 0,
  openReportsCount: 0,
};

export const featuresViewer: Features = create(FeaturesSchema, VIEWER_FEATURES_BASE);

export const featuresAdmin: Features = create(FeaturesSchema, {
  ...VIEWER_FEATURES_BASE,
  isAdmin: true,
  wishReadyCount: 2,
  unmatchedCount: 5,
  unmatchedBooksCount: 1,
  unmatchedAudioCount: 3,
  dataQualityCount: 4,
  openReportsCount: 1,
});

export const homeFeedPopulated: HomeFeedResponse = create(HomeFeedResponseSchema, {
  continueWatching: [
    {
      id: 100n,
      name: 'The Matrix',
      mediaType: MediaType.MOVIE,
      posterUrl: '/posters/w185/100',
      contentRating: ContentRating.R,
      quality: Quality.HD,
      playable: true,
      transcodeId: 1n,
      // resume_position / resume_duration drive the progress bar +
      // "X min left" pill on the continue-watching card.
      resumePosition: { seconds: 600 },
      resumeDuration: { seconds: 8160 },
    },
  ],
  recentlyAdded: [
    { id: 101n, name: 'Inception',    mediaType: MediaType.MOVIE, year: 2010, posterUrl: '/posters/w185/101', quality: Quality.HD, playable: true },
    { id: 102n, name: 'Interstellar', mediaType: MediaType.MOVIE, year: 2014, posterUrl: '/posters/w185/102', quality: Quality.HD, playable: true },
  ],
  recentlyAddedBooks: [
    {
      id: 201n, name: 'Dune', mediaType: MediaType.BOOK, year: 1965,
      posterUrl: '/posters/w185/201', quality: Quality.HD, playable: true,
      authorName: 'Frank Herbert',
    },
  ],
  recentlyAddedAlbums: [
    {
      id: 301n, name: 'Kind of Blue', mediaType: MediaType.ALBUM, year: 1959,
      posterUrl: '/posters/w185/301', quality: Quality.HD, playable: true,
      artistName: 'Miles Davis',
      trackCount: 5,
    },
  ],
  resumeListening: [
    {
      trackId: 4001n,
      trackName: 'So What',
      titleId: 301n,
      titleName: 'Kind of Blue',
      posterUrl: '/posters/w185/301',
      artistName: 'Miles Davis',
      position: { seconds: 60 },
      duration: { seconds: 565 },
      percent: 0.106,
      updatedAt: { secondsSinceEpoch: 1745402400n }, // 2026-04-23T10:00:00Z
    },
  ],
  resumeReading: [
    {
      mediaItemId: 5001n,
      titleId: 201n,
      titleName: 'Dune',
      posterUrl: '/posters/w185/201',
      mediaFormat: MediaFormat.EBOOK_EPUB,
      percent: 0.34,
      updatedAt: { secondsSinceEpoch: 1745355600n }, // 2026-04-22T21:00:00Z
    },
  ],
  recentlyWatched: [
    { id: 103n, name: 'Blade Runner 2049', mediaType: MediaType.MOVIE, year: 2017, posterUrl: '/posters/w185/103', quality: Quality.HD, playable: false },
  ],
  missingSeasons: [
    {
      titleId: 401n,
      titleName: 'Breaking Bad',
      posterUrl: '/posters/w185/401',
      tmdbId: 1396,
      mediaType: MediaType.TV,
      seasons: [{ seasonNumber: 4 }],
    },
  ],
  features: VIEWER_FEATURES_BASE,
});

export const homeFeedEmpty: HomeFeedResponse = create(HomeFeedResponseSchema, {
  features: VIEWER_FEATURES_BASE,
});
