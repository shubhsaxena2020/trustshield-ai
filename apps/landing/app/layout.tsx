import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "TrustShield AI — pause before you pay",
  description:
    "Pause over suspicious messages, calls and QR codes. Read the evidence, understand the limits, and choose your next step.",
  applicationName: "TrustShield AI",
  icons: { icon: "/logo-icon.png", apple: "/logo-icon.png" },
  openGraph: {
    title: "TrustShield AI — pause before you pay",
    description: "A calmer second opinion for suspicious messages, calls and QR codes.",
    type: "website",
  },
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
