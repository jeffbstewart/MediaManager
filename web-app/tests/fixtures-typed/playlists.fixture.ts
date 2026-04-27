// Typed proto fixtures for PlaylistService reads. Mirrors
// catalog/playlists.list.json + catalog/playlist.json +
// catalog/smart-playlist.json.

import { create } from '@bufbuild/protobuf';
import { Quality } from '../../src/app/proto-gen/common_pb';
import {
  ListPlaylistsResponseSchema,
  ListSmartPlaylistsResponseSchema,
  PlaylistDetailSchema,
  SmartPlaylistDetailSchema,
  type ListPlaylistsResponse,
  type ListSmartPlaylistsResponse,
  type PlaylistDetail,
  type SmartPlaylistDetail,
} from '../../src/app/proto-gen/playlist_pb';

const ROAD_TRIP = {
  id: 1n,
  name: 'Road Trip',
  description: 'For long drives',
  ownerUserId: 1n,
  ownerUsername: 'testuser',
  isOwner: true,
  isPrivate: false,
  heroTitleId: 301n,
  heroTrackId: 4001n,
  // 2026-04-20T10:00:00Z
  updatedAt: { secondsSinceEpoch: 1745143200n },
};

const COFFEE_SHOP = {
  id: 2n,
  name: 'Coffee Shop',
  ownerUserId: 1n,
  ownerUsername: 'testuser',
  isOwner: true,
  isPrivate: true,
  // 2026-04-18T14:30:00Z
  updatedAt: { secondsSinceEpoch: 1744986600n },
};

const BORROWED_MIX = {
  id: 3n,
  name: 'Borrowed Mix',
  description: "Someone else's playlist",
  ownerUserId: 7n,
  ownerUsername: 'alice',
  isOwner: false,
  isPrivate: false,
  heroTitleId: 302n,
  // 2026-04-15T09:00:00Z
  updatedAt: { secondsSinceEpoch: 1744707600n },
};

export const playlistsList: ListPlaylistsResponse = create(ListPlaylistsResponseSchema, {
  playlists: [ROAD_TRIP, COFFEE_SHOP, BORROWED_MIX],
});

export const smartPlaylistsList: ListSmartPlaylistsResponse = create(ListSmartPlaylistsResponseSchema, {
  playlists: [
    {
      key: 'recently-played',
      name: 'Recently Played',
      description: "Songs you've played in the last 7 days.",
      trackCount: 42,
      heroTitleId: 501n,
    },
    {
      key: 'most-played',
      name: 'Most Played',
      description: 'Your top 50 most-played tracks.',
      trackCount: 50,
      heroTitleId: 502n,
    },
  ],
});

export const playlistDetailRoadTrip: PlaylistDetail = create(PlaylistDetailSchema, {
  summary: ROAD_TRIP,
  totalDurationSeconds: 1154,
  tracks: [
    {
      playlistTrackId: 100n,
      position: 0,
      track: {
        id: 4001n, titleId: 301n, trackNumber: 1, discNumber: 1,
        name: 'So What', duration: { seconds: 565 }, playable: true,
      },
      titleId: 301n,
      titleName: 'Kind of Blue',
    },
    {
      playlistTrackId: 101n,
      position: 1,
      track: {
        id: 4002n, titleId: 301n, trackNumber: 2, discNumber: 1,
        name: 'Freddie Freeloader', duration: { seconds: 589 }, playable: true,
      },
      titleId: 301n,
      titleName: 'Kind of Blue',
    },
  ],
});

// catalog/smart-playlist.json — recently-played with 1 track. Same
// shape as a regular playlist detail but the summary is a smart one.
export const smartPlaylistDetail: SmartPlaylistDetail = create(SmartPlaylistDetailSchema, {
  summary: {
    key: 'recently-played',
    name: 'Recently Played',
    description: "Songs you've played in the last 7 days.",
    trackCount: 1,
    heroTitleId: 501n,
  },
  totalDurationSeconds: 565,
  tracks: [
    {
      playlistTrackId: 0n,
      position: 0,
      track: {
        id: 4001n, titleId: 301n, trackNumber: 1, discNumber: 1,
        name: 'So What', duration: { seconds: 565 }, playable: true,
      },
      titleId: 301n,
      titleName: 'Kind of Blue',
    },
  ],
});
