// Typed fixture for the gRPC GetTitleDetail response — TV branch.
// Mirrors tests/fixtures/catalog/title.tv.json (legacy REST shape) so
// the SPA renders identically whether the request lands on REST or
// gRPC during the migration window.
//
// Episode 1 has no resume position (proto sends seconds=0; the adapter
// in catalog.service.ts treats 0 the same as null because every UI
// consumer falsy-checks the value). Episode 2 has a saved offset.

import { create } from '@bufbuild/protobuf';
import {
  AcquisitionStatus,
  ContentRating,
  MediaFormat,
  MediaType,
  Quality,
  TitleDetailSchema,
  type TitleDetail,
} from '../../src/app/proto-gen/common_pb';

export const titleTv200: TitleDetail = create(TitleDetailSchema, {
  title: {
    id: 200n,
    name: 'Breaking Bad',
    mediaType: MediaType.TV,
    year: 2008,
    description:
      'A high school chemistry teacher turned methamphetamine manufacturer.',
    contentRating: ContentRating.TV_MA,
    quality: Quality.HD,
    playable: true,
  },
  isFavorite: true,
  cast: [
    { tmdbPersonId: 17419, name: 'Bryan Cranston', characterName: 'Walter White',  headshotUrl: '/headshots/10', order: 1 },
    { tmdbPersonId: 84497, name: 'Aaron Paul',     characterName: 'Jesse Pinkman', headshotUrl: '/headshots/11', order: 2 },
  ],
  genres: [
    { id: 20n, name: 'Drama' },
    { id: 21n, name: 'Crime' },
  ],
  seasons: [
    { seasonNumber: 1, episodeCount: 2, acquisitionStatus: AcquisitionStatus.OWNED },
    { seasonNumber: 2, episodeCount: 1, acquisitionStatus: AcquisitionStatus.OWNED },
  ],
  episodes: [
    {
      episodeId: 1n,
      transcodeId: 2001n,
      seasonNumber: 1,
      episodeNumber: 1,
      name: 'Pilot',
      quality: Quality.HD,
      playable: true,
      resumePosition: { seconds: 0 },
      duration: { seconds: 3480 },
    },
    {
      episodeId: 2n,
      transcodeId: 2002n,
      seasonNumber: 1,
      episodeNumber: 2,
      name: "Cat's in the Bag…",
      quality: Quality.HD,
      playable: true,
      resumePosition: { seconds: 600 },
      duration: { seconds: 2880 },
    },
    {
      episodeId: 3n,
      transcodeId: 2003n,
      seasonNumber: 2,
      episodeNumber: 1,
      name: 'Seven Thirty-Seven',
      quality: Quality.HD,
      playable: true,
      resumePosition: { seconds: 0 },
      duration: { seconds: 2820 },
    },
  ],
  similarTitles: [
    { titleId: 100n, titleName: 'The Matrix', releaseYear: 1999 },
  ],
  displayFormats: ['Blu-ray'],
  adminMediaItems: [
    { mediaItemId: 6001n, mediaFormat: MediaFormat.BLURAY, upc: '794043145063' },
  ],
});
