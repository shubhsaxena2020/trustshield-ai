import type { Metadata, Viewport } from "next";
import manifest from "../public/release-manifest.json";
import "./globals.css";

const title = "TrustShield AI — pause before you pay";
const description = "Check suspicious SMS, WhatsApp text, links, screenshots, QR codes, UPI requests and calls. See risk evidence in English, Hindi and Hinglish.";

export const metadata: Metadata = {
  metadataBase: new URL(new URL(manifest.releasePageUrl).origin),
  title,
  description,
  applicationName: "TrustShield AI",
  robots: { index: true, follow: true },
  icons: { icon: "/logo-icon.png", apple: "/logo-icon.png" },
  openGraph: {
    title,
    description,
    type: "website",
    images: [{ url: "/og-image.png", width: 1200, height: 630, alt: "TrustShield AI — pause before you pay" }],
  },
  twitter: { card: "summary_large_image", title, description, images: ["/og-image.png"] },
};

export const viewport: Viewport = { colorScheme: "light" };

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
