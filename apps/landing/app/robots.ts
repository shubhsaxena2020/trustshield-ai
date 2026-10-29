import type { MetadataRoute } from "next";
import manifest from "../public/release-manifest.json";

export default function robots(): MetadataRoute.Robots {
  const origin = new URL(manifest.releasePageUrl).origin;
  return {
    rules: { userAgent: "*", allow: "/" },
    sitemap: `${origin}/sitemap.xml`,
  };
}
