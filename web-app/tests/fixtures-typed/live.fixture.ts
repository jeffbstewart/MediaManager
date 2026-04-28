// Typed proto fixtures for LiveService. Mirrors catalog/cameras.json
// (legacy REST fixture) — same three rows in proto shape.

import { create } from '@bufbuild/protobuf';
import {
  CameraListResponseSchema,
  type CameraListResponse,
} from '../../src/app/proto-gen/live_pb';

export const camerasList: CameraListResponse = create(CameraListResponseSchema, {
  cameras: [
    { id: 1n, name: 'Front Door',  streamUrl: '' },
    { id: 2n, name: 'Driveway',    streamUrl: '' },
    { id: 3n, name: 'Back Yard',   streamUrl: '' },
  ],
});
