import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "TrustShield AI — pause before you pay",
  description:
    "Check suspicious messages, links and QR codes. See the evidence, understand the limits, and decide for yourself.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
