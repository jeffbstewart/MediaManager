// Dev-server proxy. ng serve forwards every prefix below to the
// upstream MM server (HAProxy in prod, raw Armeria locally) so the
// SPA can talk to its real backend without a separate origin.
//
// MM_API_TARGET is set by lifecycle/run-angular-dev.sh from
// secrets/deploy.agent_visible_env (HAPROXY_URL).
//
// Every URL prefix the SPA fetches at runtime needs to be listed —
// anything missing 404s against ng serve's static handler. Symptoms:
//   - sidenav items missing (GetFeatures gRPC RPC 404s)
//   - bare-text material icons (vendor fonts under /app/ 404)
//   - empty author / artist cards (/{author,artist}-headshots/:id)
const target = process.env.MM_API_TARGET || "http://localhost:9090";

// The gRPC AuthInterceptor on the backend enforces a same-origin CSRF
// gate: when a request carries an `Origin` header it MUST match the
// request `:authority`. In dev, the browser sends
// `Origin: http://localhost:4200`, but `changeOrigin: true` rewrites
// the upstream `Host` to the production hostname — the gate then
// rejects every cookie-authenticated gRPC call with grpc-status 16
// (UNAUTHENTICATED). Rewriting Origin to the upstream's origin makes
// the gate see a same-host pair and accept the cookie. This is a
// dev-only shim; production browsers hit HAProxy directly so Origin
// and :authority share a host.
const upstreamOrigin = (() => {
  try { return new URL(target).origin; } catch { return null; }
})();

function rewriteOrigin(proxyReq) {
  if (upstreamOrigin) proxyReq.setHeader("origin", upstreamOrigin);
}

const proxyConfig = {
  target,
  secure: false,
  changeOrigin: true,
  onProxyReq: rewriteOrigin,
};

// gRPC paths look like `/mediamanager.CatalogService/GetFeatures`.
// Connect-Web posts to them with `application/grpc-web+proto`. Forward
// the whole `/mediamanager.*/...` family to the upstream so the gRPC
// surface works in dev exactly like prod (HAProxy already routes
// these to port 9090; locally Armeria handles both REST and gRPC on
// one port).
//
// `/app/**` covers the production-mounted SPA prefix — index.html
// references vendor assets as /app/vendor/fonts/... so HAProxy can
// strip the prefix in prod. In dev, we don't strip; we just forward
// the full path so HAProxy still does the right thing.
module.exports = {
  // gRPC services (one entry; matches every /mediamanager.X/Y).
  "/mediamanager.*/**": proxyConfig,

  // REST + auth.
  "/api/**": proxyConfig,

  // Production /app/ prefix (vendor fonts + any future static assets).
  "/app/**": proxyConfig,

  // Image / asset endpoints.
  "/posters/**": proxyConfig,
  "/headshots/**": proxyConfig,
  "/author-headshots/**": proxyConfig,
  "/artist-headshots/**": proxyConfig,
  "/backdrops/**": proxyConfig,
  "/collection-posters/**": proxyConfig,
  "/local-images/**": proxyConfig,
  "/ownership-photos/**": proxyConfig,
  "/public/**": proxyConfig,
  "/proxy/**": proxyConfig,

  // Streaming / progress / camera / live TV.
  "/stream/**": proxyConfig,
  "/ebook/**": proxyConfig,
  "/playback-progress/**": proxyConfig,
  "/cam/**": proxyConfig,
  "/live-tv-stream/**": proxyConfig,
};
