// Typed proto fixture for CatalogService.GetBookSeriesDetail.
// Mirrors catalog/series.json (the legacy REST fixture): one owned
// volume (Dune) + one missing volume (Dune Messiah). No URL fields
// on the wire — the SPA adapter constructs same-origin URLs from
// title_id / ol_work_id.

import { create } from '@bufbuild/protobuf';
import {
  BookSeriesDetailSchema,
  type BookSeriesDetail,
} from '../../src/app/proto-gen/common_pb';

export const seriesDetailFixture: BookSeriesDetail = create(BookSeriesDetailSchema, {
  id: 1n,
  name: 'Dune',
  description: "Frank Herbert's desert-planet saga.",
  // Cover served via /proxy/ol/isbn/0345391802/M when set; the
  // legacy REST fixture used /posters/w500/300 (volume fallback).
  // The SPA adapter falls back to the first volume's title_id when
  // cover_isbn is empty, which renders the same poster.
  author: { id: 1n, name: 'Frank Herbert' },
  volumes: [
    {
      titleId: 300n,
      titleName: 'Dune',
      seriesNumber: '1',
      firstPublicationYear: 1965,
      owned: true,
    },
  ],
  missingVolumes: [
    {
      olWorkId: 'OL12345W',
      title: 'Dune Messiah',
      seriesNumber: '2',
      year: 1969,
      alreadyWished: false,
    },
  ],
  canFillGaps: true,
});
