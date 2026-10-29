import type { Metadata } from "next";
import "@fontsource-variable/inter";
import "@fontsource/instrument-serif";
import "./globals.css";
import { THEME_BOOT_SCRIPT } from "@/lib/useTheme";

export const metadata: Metadata = {
  title: "TrustShield AI",
  description: "Privacy-first multimodal cyber-fraud and phishing risk analyzer. Think Before You Trust.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en-US" className="h-full antialiased" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: THEME_BOOT_SCRIPT }} />
      </head>
      <body>{children}</body>
    </html>
  );
}
