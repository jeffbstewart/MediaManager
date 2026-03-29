const target = process.env.MM_API_TARGET || "http://localhost:9090";

const proxyConfig = { target, secure: false, changeOrigin: true };

module.exports = {
  "/api/**": proxyConfig,
  "/posters/**": proxyConfig,
  "/headshots/**": proxyConfig,
  "/backdrops/**": proxyConfig,
  "/collection-posters/**": proxyConfig,
  "/local-images/**": proxyConfig,
  "/stream/**": proxyConfig,
  "/playback-progress/**": proxyConfig,
  "/cam/**": proxyConfig,
};
