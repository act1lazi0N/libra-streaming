import type { NextConfig } from "next";
import { PHASE_DEVELOPMENT_SERVER } from "next/constants";

const contentSecurityPolicy = [
  "default-src 'self'",
  "script-src 'self' 'unsafe-inline'",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob:",
  "media-src 'self' blob:",
  "connect-src 'self'",
  "worker-src 'self' blob:",
  "frame-ancestors 'none'",
  "base-uri 'self'",
  "form-action 'self'",
].join("; ");

export default function config(phase: string): NextConfig {
  const development = phase === PHASE_DEVELOPMENT_SERVER;
  const ingress = process.env.LIBRA_DEV_INGRESS_URL?.replace(/\/+$/, "");

  return {
    output: "standalone",
    async headers() {
      if (development) {
        return [];
      }
      return [
        {
          source: "/:path*",
          headers: [
            { key: "Content-Security-Policy", value: contentSecurityPolicy },
            { key: "X-Content-Type-Options", value: "nosniff" },
            { key: "Referrer-Policy", value: "same-origin" },
          ],
        },
      ];
    },
    async rewrites() {
      if (!development || !ingress) {
        return [];
      }
      return [
        { source: "/api/core/:path*", destination: `${ingress}/api/core/:path*` },
        { source: "/api/media/:path*", destination: `${ingress}/api/media/:path*` },
      ];
    },
  };
}
