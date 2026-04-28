// Typed proto fixture for CatalogService.ListFamilyVideos. Mirrors
// catalog/family-videos.list.json: two videos (Beach Trip with poster
// + members + tag, Holiday Recital partly watched, no poster). No URL
// fields on the wire — adapter constructs covers from local_image_id
// or title_id.

import { create } from '@bufbuild/protobuf';
import { Month } from '../../src/app/proto-gen/time_pb';
import {
  FamilyVideosResponseSchema,
  type FamilyVideosResponse,
} from '../../src/app/proto-gen/catalog_pb';

export const familyVideosFixture: FamilyVideosResponse = create(FamilyVideosResponseSchema, {
  videos: [
    {
      titleId: 700n,
      titleName: 'Family Beach Trip 2024',
      // local_image_id populated → SPA serves /local-images/700-uuid
      // (test asserts on /local-images/<uuid>).
      localImageId: '700',
      eventDate: { year: 2024, month: Month.JULY, day: 12 },
      description: 'A week at the Outer Banks.',
      playable: true,
      familyMembers: [
        { id: 1n, name: 'Alex' },
        { id: 2n, name: 'Jamie' },
      ],
      tags: [
        { id: 10n, name: 'Vacation', color: { hex: '#1d4ed8' } },
      ],
    },
    {
      titleId: 701n,
      titleName: 'Holiday Recital',
      eventDate: { year: 2023, month: Month.DECEMBER, day: 15 },
      playable: false,
      // 60% watched: position 60s of 100s.
      playbackPosition: { seconds: 60 },
      playbackDuration: { seconds: 100 },
      familyMembers: [
        { id: 1n, name: 'Alex' },
      ],
    },
  ],
  total: 2,
  familyMembers: [
    { id: 1n, name: 'Alex' },
    { id: 2n, name: 'Jamie' },
  ],
});
