import type { NextConfig } from "next";

// The API is a different origin (Render); the browser talks to it directly, so it must be allowed in connect-src.
const apiOrigin = process.env.NEXT_PUBLIC_API_URL ?? "";
const connectSrc = ["'self'", apiOrigin, process.env.NODE_ENV === "production" ? "" : "http://localhost:8000"].filter(Boolean).join(" ");

const securityHeaders = [
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
  { key: "Strict-Transport-Security", value: "max-age=31536000; includeSubDomains" },
  {
    key: "Content-Security-Policy",
    // Next.js inline bootstrap scripts need 'unsafe-inline'; everything else is same-origin. Dev needs eval for HMR.
    value: [
      "default-src 'self'",
      `script-src 'self' 'unsafe-inline'${process.env.NODE_ENV === "production" ? "" : " 'unsafe-eval'"}`,
      "style-src 'self' 'unsafe-inline'",
      "img-src 'self' data: blob:",
      `connect-src ${connectSrc}`,
      "frame-ancestors 'none'",
      "base-uri 'self'",
      "form-action 'self'",
    ].join("; "),
  },
];

const nextConfig: NextConfig = {
  poweredByHeader: false,
  async headers() {
    return [{ source: "/:path*", headers: securityHeaders }];
  },
  // Opt-in, same-process proxy for running frontend + backend on one machine behind a single public entry point (e.g. the
  // local expo setup: Cloudflare Tunnel exposes only this Next.js server; it forwards /api/* server-side to the FastAPI
  // backend on loopback, so the backend's own port is never reachable from outside). No effect unless API_PROXY_TARGET is
  // set; the existing split-origin deployment (NEXT_PUBLIC_API_URL, direct browser-to-API CORS) is unchanged by default.
  async rewrites() {
    const target = process.env.API_PROXY_TARGET;
    return target ? [{ source: "/api/:path*", destination: `${target}/api/:path*` }] : [];
  },
};

export default nextConfig;
