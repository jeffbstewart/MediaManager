const target = process.env.MM_API_TARGET || "http://localhost:9090";

module.exports = {
  "/api/**": { target, secure: false, changeOrigin: true },
};
