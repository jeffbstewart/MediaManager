// Typed proto fixture for CatalogService.GetTagDetail. Mirrors
// catalog/tag-detail.json (the legacy REST fixture) — Comfort
// Watch tag with 2 owned titles (movie + tv), no tracks.

import { create } from '@bufbuild/protobuf';
import {
  ContentRating,
  MediaType,
  TagDetailSchema,
  TitleSchema,
  type TagDetail,
} from '../../src/app/proto-gen/common_pb';

export const tagDetailFixture: TagDetail = create(TagDetailSchema, {
  name: 'Comfort Watch',
  color: { hex: '#1B5E20' },
  titles: [
    create(TitleSchema, {
      id: 100n,
      name: 'The Matrix',
      mediaType: MediaType.MOVIE,
      posterUrl: '/posters/w185/100',
      year: 1999,
      contentRating: ContentRating.R,
      playable: true,
    }),
    create(TitleSchema, {
      id: 200n,
      name: 'Breaking Bad',
      mediaType: MediaType.TV,
      posterUrl: '/posters/w185/200',
      year: 2008,
      contentRating: ContentRating.TV_MA,
      playable: true,
      progressFraction: 0.62,
    }),
  ],
});
