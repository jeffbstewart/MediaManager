// Typed proto fixtures for LiveService. Mirrors catalog/cameras.json
// + catalog/tv-channels.json (legacy REST fixtures) in proto shape.

import { create } from '@bufbuild/protobuf';
import { Quality } from '../../src/app/proto-gen/common_pb';
import {
  CameraListResponseSchema,
  TvChannelListResponseSchema,
  type CameraListResponse,
  type TvChannelListResponse,
} from '../../src/app/proto-gen/live_pb';

export const camerasList: CameraListResponse = create(CameraListResponseSchema, {
  cameras: [
    { id: 1n, name: 'Front Door',  streamUrl: '' },
    { id: 2n, name: 'Driveway',    streamUrl: '' },
    { id: 3n, name: 'Back Yard',   streamUrl: '' },
  ],
});

// Reception quality 5 in the legacy fixture maps to Quality.UHD on
// the proto (the SPA's PROTO_QUALITY_TO_RECEPTION_LEVEL adapter
// reverses it); 4 also maps to UHD.
export const tvChannelsList: TvChannelListResponse = create(TvChannelListResponseSchema, {
  channels: [
    { id: 1n, name: 'WTTW HD', number: '5.1', networkAffiliation: 'PBS',         quality: Quality.UHD, streamUrl: '' },
    { id: 2n, name: 'WLS HD',  number: '7.1', networkAffiliation: 'ABC',         quality: Quality.UHD, streamUrl: '' },
    { id: 3n, name: 'WGN HD',  number: '9.1', networkAffiliation: 'Independent', quality: Quality.UHD, streamUrl: '' },
  ],
});
