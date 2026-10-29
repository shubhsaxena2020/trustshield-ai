import type { MetadataRoute } from "next";
import manifest from "../public/release-manifest.json";

export default function sitemap(): MetadataRoute.Sitemap {
  const origin = new URL(manifest.releasePageUrl).origin;
  return [
    { url: `${origin}/`, changeFrequency: "monthly", priority: 1 },
    { url: manifest.releasePageUrl, changeFrequency: "monthly", priority: 0.8 },
  ];
}
