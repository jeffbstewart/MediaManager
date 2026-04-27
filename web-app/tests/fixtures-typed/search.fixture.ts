// Typed proto fixture for CatalogService.Search. Mirrors
// catalog/search.results.json (the legacy REST fixture) — same 9
// rows in the same order, expressed as proto SearchResult messages.

import { create } from '@bufbuild/protobuf';
import {
  SearchResultSchema,
  SearchResultType,
} from '../../src/app/proto-gen/common_pb';
import {
  AdvancedSearchPresetsResponseSchema,
  SearchResponseSchema,
  SearchTracksResponseSchema,
  type AdvancedSearchPresetsResponse,
  type SearchResponse,
  type SearchTracksResponse,
} from '../../src/app/proto-gen/catalog_pb';

export const searchResultsFixture: SearchResponse = create(SearchResponseSchema, {
  query: 'test',
  results: [
    create(SearchResultSchema, {
      resultType: SearchResultType.MOVIE,
      name: 'The Matrix',
      titleId: 100n,
      year: 1999,
      posterUrl: '/posters/w185/100',
    }),
    create(SearchResultSchema, {
      resultType: SearchResultType.SERIES,
      name: 'Breaking Bad',
      titleId: 200n,
      year: 2008,
      posterUrl: '/posters/w185/200',
    }),
    create(SearchResultSchema, {
      resultType: SearchResultType.ALBUM,
      name: 'Kind of Blue',
      titleId: 301n,
      year: 1959,
      posterUrl: '/posters/w185/301',
      albumName: 'Kind of Blue',
    }),
    create(SearchResultSchema, {
      resultType: SearchResultType.ACTOR,
      name: 'Keanu Reeves',
      tmdbPersonId: 6384,
      headshotUrl: '/headshots/6384',
    }),
    create(SearchResultSchema, {
      resultType: SearchResultType.ARTIST,
      name: 'Miles Davis',
      artistId: 1n,
      headshotUrl: '/artist-headshots/1',
    }),
    create(SearchResultSchema, {
      resultType: SearchResultType.AUTHOR,
      name: 'Frank Herbert',
      authorId: 1n,
      headshotUrl: '/author-headshots/1',
    }),
    create(SearchResultSchema, {
      resultType: SearchResultType.COLLECTION,
      name: 'The Matrix Collection',
      tmdbCollectionId: 2,
    }),
    create(SearchResultSchema, {
      resultType: SearchResultType.TAG,
      name: 'Comfort Watch',
      itemId: 1n,
      titleCount: 12,
    }),
    create(SearchResultSchema, {
      resultType: SearchResultType.BOOK,
      name: 'Dune',
      titleId: 300n,
      year: 1965,
      posterUrl: '/posters/w185/300',
    }),
  ],
});

export const advancedSearchPresetsFixture: AdvancedSearchPresetsResponse = create(
  AdvancedSearchPresetsResponseSchema,
  { presets: [] },
);

export const searchTracksEmptyFixture: SearchTracksResponse = create(
  SearchTracksResponseSchema,
  { tracks: [] },
);
