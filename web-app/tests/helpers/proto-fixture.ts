// Helper for serving proto-typed fixtures from Playwright route mocks.
//
// Why this helper exists: TypeScript catches most contract drift between
// proto and fixture (the `: TitleDetail` annotation requires every field
// to match), but encoding-time issues sneak past — wrong bigint width,
// nested-message schema mismatches, oneof arms that don't round-trip.
//
// Pattern: round-trip the fixture through `toJson(Schema, msg)` →
// `fromJson(Schema, json)` → `toBinary(Schema, msg)`. ts-tested in this
// order:
//   - TypeScript: did the fixture even compile?
//   - JSON round-trip: do all enums / oneofs / nested types parse?
//   - Binary encode: would the wire bytes the server sees be valid?
//
// In production this same code path validates real server responses
// before they reach the application — the Connect transport runs the
// generated Schema's decode on every response, throwing on mismatch.

import type { Route } from '@playwright/test';
import type { DescMessage, MessageShape } from '@bufbuild/protobuf';
import { fromJson, toBinary, toJson } from '@bufbuild/protobuf';

/**
 * Validate a typed fixture by round-tripping through JSON. Throws if the
 * value doesn't survive the round trip — anything thrown here means the
 * fixture has a structural issue the type checker missed.
 */
export function canonicaliseProto<S extends DescMessage>(
  schema: S,
  fixture: MessageShape<S>,
): MessageShape<S> {
  // toJson + fromJson is the strict path: it normalises enum encoding,
  // validates oneof arms, and rejects unknown fields. The result is a
  // freshly-constructed message instance with the canonical shape.
  return fromJson(schema, toJson(schema, fixture));
}

/**
 * Fulfill a Playwright route with a proto-typed fixture, encoded as
 * gRPC-Web framed binary — the wire format the SPA's Connect-Web client
 * expects. Frames consist of:
 *   - 5-byte message frame: 1-byte flag (0x00 = uncompressed) + 4-byte
 *     big-endian length + payload bytes.
 *   - 5-byte trailer frame: 1-byte flag (0x80 = trailer) + 4-byte length
 *     + ASCII trailers (`grpc-status: 0\r\n` for success).
 *
 * Without the trailer frame Connect-Web treats the response as
 * incomplete and surfaces an error instead of the message.
 */
export function fulfillProto<S extends DescMessage>(
  route: Route,
  schema: S,
  fixture: MessageShape<S>,
): Promise<void> {
  const validated = canonicaliseProto(schema, fixture);
  const payload = toBinary(schema, validated);

  // Message frame: 0x00 + uint32 length + payload.
  const msgFrame = new Uint8Array(5 + payload.length);
  new DataView(msgFrame.buffer).setUint32(1, payload.length, /*littleEndian=*/false);
  msgFrame.set(payload, 5);

  // Trailer frame: 0x80 + uint32 length + ASCII "grpc-status: 0\r\n".
  const trailerText = 'grpc-status: 0\r\n';
  const trailerBytes = new TextEncoder().encode(trailerText);
  const trailerFrame = new Uint8Array(5 + trailerBytes.length);
  trailerFrame[0] = 0x80;
  new DataView(trailerFrame.buffer).setUint32(1, trailerBytes.length, /*littleEndian=*/false);
  trailerFrame.set(trailerBytes, 5);

  const body = new Uint8Array(msgFrame.length + trailerFrame.length);
  body.set(msgFrame, 0);
  body.set(trailerFrame, msgFrame.length);

  return route.fulfill({
    status: 200,
    contentType: 'application/grpc-web+proto',
    body: Buffer.from(body),
  });
}

/**
 * Strip a gRPC-Web request frame to recover the inner protobuf payload.
 * Used by mock-backend route handlers to decode the request body so they
 * can dispatch on its content (e.g. by titleId). Connect-Web wraps every
 * request in a single message frame; we just skip the 5-byte header.
 */
export function unframeGrpcWebRequest(body: Buffer | null): Uint8Array {
  if (!body || body.length < 5) return new Uint8Array(0);
  const view = new DataView(body.buffer, body.byteOffset, body.byteLength);
  const len = view.getUint32(1, /*littleEndian=*/false);
  return new Uint8Array(body.buffer, body.byteOffset + 5, len);
}
