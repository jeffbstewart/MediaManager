/**
 * How the SPA names itself to the server. Every gRPC call carries
 * `x-client-platform: web` (see grpc-client.ts), which the server's
 * legal gate reads to pick the terms-of-use document that applies. Both
 * values must match the server (CLIENT_PLATFORM_KEY in AuthInterceptor.kt
 * and LegalRequirements.PLATFORM_WEB); an id the server does not know is
 * held to the strictest terms version.
 */
export const CLIENT_PLATFORM_HEADER = 'x-client-platform';
export const CLIENT_PLATFORM = 'web';
