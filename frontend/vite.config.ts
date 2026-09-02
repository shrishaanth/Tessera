import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

// The dev server proxies the API and the live WebSocket to the Spring Boot
// backend (default :8080; override with TESSERA_BACKEND, e.g. if port 8080 is
// taken), so the browser talks to a single origin and the session cookie just
// works. `npm run build` emits into ../backend/.../static so the backend serves
// the SPA in production.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const backend = env.TESSERA_BACKEND || "http://localhost:8080";
  const wsBackend = backend.replace(/^http/, "ws");
  return {
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": { target: backend, changeOrigin: true },
      "/ws": { target: wsBackend, ws: true, changeOrigin: true },
    },
  },
  build: {
    outDir: "../backend/src/main/resources/static",
    emptyOutDir: true,
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
  },
  };
});
