// Typed proto fixtures for ListCollections + GetCollectionDetail.
// Mirrors fixtures/catalog/collections.list.json and collection-detail.json
// — content matches so SPA tests assert the same outcomes whether the
// requests land on REST or gRPC during the migration window.
//
// Note: the SPA's `c.id` field is now the tmdb_collection_id (which the
// gRPC GetCollectionDetail RPC accepts). The legacy fixture's `"id": 1`
// happened to coincide with the chosen tmdb_collection_id 263; the
// mock-backend dispatch uses 263 so the route /content/collection/263
// resolves to The Dark Knight Trilogy fixture.

import { create } from '@bufbuild/protobuf';
import {
  ContentRating,
  MediaType,
  Quality,
} from '../../src/app/proto-gen/common_pb';
import {
  CollectionListResponseSchema,
  type CollectionListResponse,
} from '../../src/app/proto-gen/catalog_pb';
import {
  CollectionDetailSchema,
  type CollectionDetail,
} from '../../src/app/proto-gen/common_pb';

// Three-way list with mixed ownership ratios. tmdb_collection_id values
// chosen to be visually meaningful and stable.
export const collectionsList: CollectionListResponse = create(CollectionListResponseSchema, {
  collections: [
    { tmdbCollectionId: 263,  name: 'The Dark Knight Trilogy', posterUrl: '/collection-posters/263',  titleCount: 3, totalParts: 3 },
    { tmdbCollectionId: 2344, name: 'The Matrix Collection',   posterUrl: '/collection-posters/2344', titleCount: 2, totalParts: 4 },
    { tmdbCollectionId: 10,   name: 'Star Wars',                                                       titleCount: 5, totalParts: 11 },
  ],
});

// Used for /content/collection/2344 — The Matrix collection with 2 owned + 2 wished.
export const collectionDetail2344: CollectionDetail = create(CollectionDetailSchema, {
  name: 'The Matrix Collection',
  items: [
    { tmdbMovieId: 603,    name: 'The Matrix',                titleId: 500n, posterUrl: '/posters/w185/500', year: 1999, owned: true,  playable: true,  quality: Quality.HD,      contentRating: ContentRating.R },
    { tmdbMovieId: 604,    name: 'The Matrix Reloaded',       titleId: 501n, posterUrl: '/posters/w185/501', year: 2003, owned: true,  playable: false, quality: Quality.HD,      contentRating: ContentRating.R, progressFraction: 0.42 },
    { tmdbMovieId: 605,    name: 'The Matrix Revolutions',                                                    year: 2003, owned: false, playable: false },
    { tmdbMovieId: 624860, name: 'The Matrix Resurrections',                                                  year: 2021, owned: false, playable: false, wished: true },
  ],
});
