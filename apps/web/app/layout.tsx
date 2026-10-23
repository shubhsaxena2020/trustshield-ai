import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "TrustShield AI",
  description: "Privacy-first multimodal cyber-fraud and phishing risk analyzer. Think Before You Trust.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en-US" className="h-full antialiased">
      <body>{children}</body>
    </html>
  );
}
