import type { NextConfig } from "next";

// The backend is reached through a same-origin proxy, so the browser needs no CORS configuration.
const backend = process.env.BACKEND_URL ?? "http://localhost:8081";

const nextConfig: NextConfig = {
  // The browser tests build into their own folder so they never disturb a dev/prod server using ".next".
  distDir: process.env.NEXT_DIST_DIR || ".next",
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${backend}/api/:path*` }];
  },
};

export default nextConfig;
