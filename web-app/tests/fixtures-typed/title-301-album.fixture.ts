// Typed fixture for the gRPC GetTitleDetail response — album branch.
// Mirrors tests/fixtures/catalog/title.album.json (legacy REST shape):
// Miles Davis, "Kind of Blue", 2 of the 5 tracks, no track tags or
// per-track artists, empty personnel.

import { create } from '@bufbuild/protobuf';
import {
  ArtistType,
  MediaType,
  TitleDetailSchema,
  type TitleDetail,
} from '../../src/app/proto-gen/common_pb';

export const titleAlbum301: TitleDetail = create(TitleDetailSchema, {
  title: {
    id: 301n,
    name: 'Kind of Blue',
    mediaType: MediaType.ALBUM,
    year: 1959,
    description: 'Seminal modal jazz album.',
    playable: true,
  },
  genres: [{ id: 30n, name: 'Jazz' }],
  album: {
    albumArtists: [
      { id: 1n, name: 'Miles Davis', artistType: ArtistType.PERSON },
    ],
    tracks: [
      {
        id: 4001n,
        titleId: 301n,
        trackNumber: 1,
        discNumber: 1,
        name: 'So What',
        duration: { seconds: 565 },
        playable: true,
      },
      {
        id: 4002n,
        titleId: 301n,
        trackNumber: 2,
        discNumber: 1,
        name: 'Freddie Freeloader',
        duration: { seconds: 589 },
        playable: true,
      },
    ],
    trackCount: 2,
    totalDuration: { seconds: 1154 },
    label: 'Columbia',
    musicbrainzReleaseGroupId: 'c96c4a9b-9464-3e7c-9ed5-b8d7d9ca4cf4',
  },
});
