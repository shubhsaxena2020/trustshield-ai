import type { Metadata } from "next";
import "./globals.css";

const title = "TrustShield AI — pause before you pay";
const description = "Check suspicious SMS, WhatsApp text, links, screenshots, QR codes, UPI requests and calls. See risk evidence in English, Hindi and Hinglish.";

export const metadata: Metadata = {
  title,
  description,
  applicationName: "TrustShield AI",
  icons: { icon: "/logo-icon.png", apple: "/logo-icon.png" },
  openGraph: {
    title,
    description,
    type: "website",
    images: [{ url: "/og-image.png", width: 1200, height: 630, alt: "TrustShield AI — pause before you pay" }],
  },
  twitter: { card: "summary_large_image", title, description, images: ["/og-image.png"] },
};

const themeScript = `try{var t=localStorage.getItem('trustshield-theme');document.documentElement.dataset.theme=t==='light'||t==='dark'?t:(matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light')}catch(e){document.documentElement.dataset.theme=matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light'}`;

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head><script dangerouslySetInnerHTML={{ __html: themeScript }} /></head>
      <body>{children}</body>
    </html>
  );
}
