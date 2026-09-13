import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

// The dev server proxies /api and /ws to the reporting API, so the browser talks
// to a single origin in development exactly as it does in a build — no CORS, no
// base URL to configure, and nothing that works in one environment and not the
// other.
//
// `npm run build` emits into the reporting API's static resources, so Spring Boot
// serves the dashboard and the API from one port and one container. That does mean
// the frontend must be built before the jar is packaged; see the README.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const backend = env.TESSERA_BACKEND || "http://localhost:8090";
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
    outDir: "../reporting-api/src/main/resources/static",
    emptyOutDir: true,
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
  },
  };
});
