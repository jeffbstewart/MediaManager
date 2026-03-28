const target = process.env.MM_API_TARGET || "http://localhost:9090";

module.exports = {
  "/api/v2/*": { target, secure: false, changeOrigin: true },
  "/stream/*": { target, secure: false, changeOrigin: true },
  "/posters/*": { target, secure: false, changeOrigin: true },
  "/headshots/*": { target, secure: false, changeOrigin: true },
  "/backdrops/*": { target, secure: false, changeOrigin: true },
  "/collection-posters/*": { target, secure: false, changeOrigin: true },
  "/local-images/*": { target, secure: false, changeOrigin: true },
  "/ownership-photos/*": { target, secure: false, changeOrigin: true },
  "/cam/*": { target, secure: false, changeOrigin: true },
  "/live-tv-stream/*": { target, secure: false, changeOrigin: true },
  "/health": { target, secure: false, changeOrigin: true },
};
