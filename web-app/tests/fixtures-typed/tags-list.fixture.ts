// Typed fixture for the gRPC ListTags response. Mirrors the legacy
// JSON at fixtures/catalog/tags.list.json so tests assert the same
// content whether the page lands via REST or gRPC during the
// migration window.

import { create } from '@bufbuild/protobuf';
import {
  TagListResponseSchema,
  type TagListResponse,
} from '../../src/app/proto-gen/catalog_pb';

export const tagsList: TagListResponse = create(TagListResponseSchema, {
  tags: [
    { id: 1n, name: 'Comfort Watch',  color: { hex: '#1B5E20' }, titleCount: 12 },
    { id: 2n, name: 'For Rainy Days', color: { hex: '#0D47A1' }, titleCount: 7 },
    { id: 3n, name: 'Hidden Gem',     color: { hex: '#BF360C' }, titleCount: 4 },
  ],
});
