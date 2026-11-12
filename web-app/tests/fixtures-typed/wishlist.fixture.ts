// Typed proto fixtures for WishListService.ListWishes. Mirrors the
// legacy catalog/wishlist.json: one media wish (John Wick, dismissible),
// one transcode wish (The Matrix, ready), one book wish (Dune Messiah).
// No image fields on the wire — the SPA adapter constructs URLs from
// the IDs.

import { create } from '@bufbuild/protobuf';
import {
  AcquisitionStatus,
  MediaType,
  WishLifecycleStage,
} from '../../src/app/proto-gen/common_pb';
import {
  TranscodeWishStatus,
  WishListResponseSchema,
  WishStatus,
  type WishListResponse,
} from '../../src/app/proto-gen/wishlist_pb';

export const wishListFixture: WishListResponse = create(WishListResponseSchema, {
  wishes: [{
    id: 1n,
    tmdbId: 245891,
    mediaType: MediaType.MOVIE,
    title: 'John Wick',
    releaseYear: 2014,
    status: WishStatus.ACTIVE,
    voteCount: 1,
    userVoted: true,
    acquisitionStatus: AcquisitionStatus.UNKNOWN,
    lifecycleStage: WishLifecycleStage.WISHED_FOR,
    dismissible: true,
  }],
  transcodeWishes: [{
    id: 1n,
    titleId: 100n,
    titleName: 'The Matrix',
    status: TranscodeWishStatus.READY,
  }],
  bookWishes: [{
    id: 10n,
    olWorkId: 'OL12345W',
    title: 'Dune Messiah',
    author: 'Frank Herbert',
    seriesNumber: '2',
  }],
  hasAnyMediaWish: true,
});
