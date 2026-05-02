// Typed proto fixtures for ArtistService.GetArtistDetail and
// GetAuthorDetail. Mirror catalog/artist.json + catalog/author.json.
//
// These are the defaults mock-backend's gRPC dispatch returns; per-test
// overrides clone the base via overrideArtistDetail / overrideAuthorDetail
// (in the spec files) and tweak fields before fulfilling.

import { create } from '@bufbuild/protobuf';
import {
  ArtistType,
  MediaFormat,
  MediaType,
  Quality,
  ReleaseGroupType,
} from '../../src/app/proto-gen/common_pb';
import {
  ArtistDetailSchema,
  AuthorDetailSchema,
  type ArtistDetail,
  type AuthorDetail,
} from '../../src/app/proto-gen/common_pb';
import {
  ArtistListResponseSchema,
  AuthorListResponseSchema,
  type ArtistListResponse,
  type AuthorListResponse,
} from '../../src/app/proto-gen/artist_pb';
import { Month } from '../../src/app/proto-gen/time_pb';

export const artistMilesDavis: ArtistDetail = create(ArtistDetailSchema, {
  artist: {
    id: 1n,
    name: 'Miles Davis',
    sortName: 'Davis, Miles',
    artistType: ArtistType.PERSON,
    musicbrainzArtistId: '561d854a-6a28-4aa7-8c99-323e6ce46c2a',
    beginYear: 1926,
    endYear: 1991,
    beginDate: { year: 1926, month: Month.MAY, day: 26 },
    endDate: { year: 1991, month: Month.SEPTEMBER, day: 28 },
    hasHeadshot: true,
  },
  biography: 'American jazz trumpeter, bandleader, and composer.',
  ownedAlbums: [
    {
      id: 301n,
      name: 'Kind of Blue',
      mediaType: MediaType.ALBUM,
      year: 1959,
      posterUrl: '/posters/w185/301',
      quality: Quality.HD,
      playable: true,
      trackCount: 5,
    },
  ],
  otherWorks: [
    {
      musicbrainzReleaseGroupId: 'abc-123',
      name: 'Bitches Brew',
      year: 1970,
      releaseGroupType: ReleaseGroupType.ALBUM,
      isCompilation: false,
      secondaryTypes: [],
      alreadyWished: false,
    },
  ],
});

// Default ListAuthors payload. The Books landing page renders an
// author exploration grid; this fixture has four authors with mixed
// owned-book counts and headshot availability so the spec can cover
// pluralization, hero-image fallback, and the placeholder branch.
export const authorsListFixture: AuthorListResponse = create(AuthorListResponseSchema, {
  authors: [
    { id: 1n, name: 'Frank Herbert',     ownedBookCount: 6, hasHeadshot: true  },
    { id: 2n, name: 'Ursula K. Le Guin', ownedBookCount: 4, hasHeadshot: true  },
    { id: 3n, name: 'Isaac Asimov',      ownedBookCount: 2, hasHeadshot: false },
    { id: 4n, name: 'Solo Author',       ownedBookCount: 1, hasHeadshot: false },
  ],
  pagination: { total: 4, page: 1, limit: 50, totalPages: 1 },
});

// Mirrors fixtures/catalog/artists.list.json. The legacy fixture
// emits headshot_url URLs directly; the proto carries has_headshot
// instead and the SPA rebuilds the URL from id+flag client-side.
export const artistsListFixture: ArtistListResponse = create(ArtistListResponseSchema, {
  artists: [
    {
      id: 1n, name: 'Miles Davis', sortName: 'Davis, Miles',
      artistType: ArtistType.PERSON, ownedAlbumCount: 8,
      fallbackAlbumTitleId: 500n, hasHeadshot: true,
    },
    {
      id: 2n, name: 'Radiohead', sortName: 'Radiohead',
      artistType: ArtistType.GROUP, ownedAlbumCount: 9, hasHeadshot: true,
    },
    {
      id: 3n, name: 'Björk', sortName: 'Björk',
      artistType: ArtistType.PERSON, ownedAlbumCount: 3,
      fallbackAlbumTitleId: 501n, hasHeadshot: false,
    },
    {
      id: 4n, name: 'Lonesome Crew', sortName: 'Lonesome Crew',
      artistType: ArtistType.GROUP, ownedAlbumCount: 1, hasHeadshot: false,
    },
  ],
  pagination: { total: 4, page: 1, limit: 50, totalPages: 1 },
});

export const authorFrankHerbert: AuthorDetail = create(AuthorDetailSchema, {
  author: {
    id: 1n,
    name: 'Frank Herbert',
    biography: 'American science fiction author best known for Dune.',
    openlibraryId: 'OL27349A',
    birthYear: 1920,
    deathYear: 1986,
    birthDate: { year: 1920, month: Month.OCTOBER, day: 8 },
    deathDate: { year: 1986, month: Month.FEBRUARY, day: 11 },
    hasHeadshot: true,
  },
  ownedBooks: [
    {
      id: 300n,
      name: 'Dune',
      mediaType: MediaType.BOOK,
      year: 1965,
      posterUrl: '/posters/w185/300',
      quality: Quality.HD,
      playable: true,
      seriesName: 'Dune',
      seriesNumber: '1',
    },
  ],
  otherWorks: [
    {
      openlibraryWorkId: 'OL892412W',
      name: 'Dune Messiah',
      year: 1969,
      seriesRaw: 'Dune (2)',
      alreadyWished: false,
    },
  ],
});
