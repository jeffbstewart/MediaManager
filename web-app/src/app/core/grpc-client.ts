import { createClient, type Client } from '@connectrpc/connect';
import { createGrpcWebTransport } from '@connectrpc/connect-web';
import type { DescService } from '@bufbuild/protobuf';

/**
 * Single shared gRPC-Web transport for the SPA.
 *
 * Wire: Connect-Web's gRPC-Web protocol (Content-Type
 * application/grpc-web+proto + 5-byte length-prefix framing).
 * Armeria's gRPC service accepts this natively via the gRPC-Web
 * serialization formats it advertises.
 *
 * Auth: a fetch override forces `credentials: 'include'` so the
 * browser sends the HttpOnly session cookie (AuthService.COOKIE_NAME on
 * the server) automatically. JS never sees the cookie value — same
 * security model as the rest of the SPA's REST calls. The server's
 * AuthInterceptor resolves the cookie to a user just like
 * ArmeriaAuthDecorator does for HTTP servlets.
 *
 * Base URL is "/" so the browser uses the SPA's current origin —
 * works in dev (proxy.conf.js shims through to localhost:8080) AND in
 * production (HAProxy → port 8080 on the NAS).
 */
const transport = createGrpcWebTransport({
  baseUrl: '/',
  fetch: (input, init) => fetch(input, { ...init, credentials: 'include' }),
});

/**
 * Build a typed Connect client for a generated service definition.
 * Returns the lazily-typed `Client<S>` — call methods directly
 * (`client.getTitleDetail({...})`) and the response is a fully-typed
 * proto message instance.
 *
 * Usage:
 * ```ts
 * import { CatalogService } from '../proto-gen/catalog_pb';
 * const client = grpcClient(CatalogService);
 * const detail = await client.getTitleDetail({ titleId: '100' });
 * ```
 */
export function grpcClient<S extends DescService>(service: S): Client<S> {
  return createClient(service, transport);
}
