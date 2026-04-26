// Typed fixture for the gRPC GetTitleDetail response. The
// `: TitleDetail` annotation is the contract guard — TypeScript fails
// the build the moment the proto schema drifts away from this shape.
//
// Built via protobuf-es's `create(Schema, partial)` so the result is a
// real Message<"mediamanager.TitleDetail"> brand (not just a structural
// match). At test runtime, mock-backend additionally round-trips the
// fixture through `toBinary` / `fromBinary` to catch any encoding-time
// issues TS can't see.
//
// Mirrors tests/fixtures/catalog/title.movie.json (legacy REST shape)
// while the migration is in flight.

import { create } from '@bufbuild/protobuf';
import {
  ContentRating,
  MediaFormat,
  MediaType,
  Quality,
  TitleDetailSchema,
  type TitleDetail,
} from '../../src/app/proto-gen/common_pb';

export const titleMovie100: TitleDetail = create(TitleDetailSchema, {
  title: {
    id: 100n,
    name: 'The Matrix',
    mediaType: MediaType.MOVIE,
    year: 1999,
    description:
      'A computer hacker learns from mysterious rebels about the true ' +
      'nature of his reality and his role in the war against its controllers.',
    contentRating: ContentRating.R,
    quality: Quality.HD,
    playable: true,
    transcodeId: 1001n,
  },
  cast: [
    { tmdbPersonId: 6384, name: 'Keanu Reeves',       characterName: 'Neo',      headshotUrl: '/headshots/1', order: 1 },
    { tmdbPersonId: 2975, name: 'Laurence Fishburne', characterName: 'Morpheus', headshotUrl: '/headshots/2', order: 2 },
    { tmdbPersonId: 530,  name: 'Carrie-Anne Moss',   characterName: 'Trinity',  headshotUrl: '/headshots/3', order: 3 },
  ],
  genres: [
    { id: 10n, name: 'Action' },
    { id: 11n, name: 'Science Fiction' },
  ],
  tags: [
    { id: 1n, name: 'Comfort Watch', color: { hex: '#1B5E20' } },
  ],
  transcodes: [
    {
      id: 1001n,
      mediaFormat: MediaFormat.BLURAY,
      quality: Quality.HD,
      playable: true,
    },
  ],
  similarTitles: [
    { titleId: 102n, titleName: 'Interstellar',      releaseYear: 2014 },
    { titleId: 103n, titleName: 'Blade Runner 2049', releaseYear: 2017 },
  ],
  displayFormats: ['Blu-ray', '4K UHD'],
  adminMediaItems: [
    { mediaItemId: 5001n, mediaFormat: MediaFormat.BLURAY,     upc: '888574293321' },
    { mediaItemId: 5002n, mediaFormat: MediaFormat.UHD_BLURAY, upc: '883929199854' },
  ],
});
