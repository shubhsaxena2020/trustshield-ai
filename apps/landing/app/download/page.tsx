"use client";

import Image from "next/image";
import Link from "next/link";
import { QRCodeSVG } from "qrcode.react";
import manifest from "../../public/release-manifest.json";

const qrUrl = manifest.downloadUrl || manifest.releasePageUrl;

export default function DownloadPage() {
  const available = Boolean(manifest.downloadUrl && manifest.sha256 && manifest.sizeBytes);
  return <main className="download-page">
    <header className="download-nav"><Link href="/" aria-label="TrustShield AI home"><Image src="/logo.png" alt="TrustShield AI" width={170} height={44} priority /></Link><Link className="back-link" href="/">← Back to TrustShield AI</Link></header>
    <section className="download-content">
      <div className="download-copy"><p className="eyebrow"><span className="status-dot" />ANDROID APP</p><h1>Trust, then <em>install.</em></h1><p className="download-lede">{available ? "Download the verified Android build, check its SHA-256, and install it on your phone." : "Get the verified release details and install steps here. The public APK is not published yet."}</p><div className="release-state"><span className="state-dot" /><div><strong>{available ? "Release ready" : "Release preparing"}</strong><p>{available ? "Version " + manifest.version + " is the signed Tech Expo demo build. Compare the SHA-256 below with the downloaded file before installing." : "We will enable the download after the APK, file size and SHA-256 are verified."}</p></div></div>
        <a className={`button primary ${available ? "" : "button-disabled"}`} href={available ? manifest.downloadUrl! : undefined} aria-disabled={!available} onClick={(event) => !available && event.preventDefault()}>{available ? "Download verified APK" : "APK not available yet"}<span>↓</span></a>
        <dl className="release-facts"><div><dt>SHA-256</dt><dd>{manifest.sha256 || manifest.sha256Placeholder}</dd></div><div><dt>File size</dt><dd>{manifest.sizeBytes ? `${(manifest.sizeBytes / 1024 / 1024).toFixed(1)} MB` : manifest.sizePlaceholder}</dd></div><div><dt>Minimum Android</dt><dd>{manifest.minimumAndroidVersion} or newer</dd></div><div><dt>Version</dt><dd>{manifest.version || manifest.versionPlaceholder}</dd></div></dl>
      </div>
      <aside className="qr-card"><div className="qr-frame"><QRCodeSVG value={qrUrl} size={190} level="M" includeMargin aria-label={available ? "QR code to download the TrustShield Android APK" : "QR code for the TrustShield Android release page"} /></div><p className="qr-title">{available ? "Scan to download the APK" : "Scan for release details"}</p><p className="qr-note">{available ? "Scan this code with your phone camera to download the verified APK directly." : "This code opens the official release page. It will point directly to the APK once a verified download URL is added to the release manifest."}</p><a href={qrUrl} target="_blank" rel="noreferrer">{available ? "Download APK ↗" : "Open release page ↗"}</a></aside>
    </section>
    <section className="install-section"><div><p className="eyebrow">{available ? "HOW TO INSTALL" : "WHEN THE APK IS AVAILABLE"}</p><h2>Install in a few steps.</h2><p>Only install a package from the official TrustShield release page. Check its SHA-256 against the value shown above before installing.</p></div><ol className="install-steps"><li><span>01</span><div><h3>Download the APK</h3><p>Use the official release link or scan the QR code.</p></div></li><li><span>02</span><div><h3>Allow this installation</h3><p>If Android asks, allow your browser to install unknown apps for this installation.</p></div></li><li><span>03</span><div><h3>Verify, then install</h3><p>Compare the SHA-256 value, open the APK and follow Android’s prompts.</p></div></li></ol></section>
    <footer className="download-footer"><Link href="/">← TrustShield AI</Link><span>Only install verified releases. Results are guidance, not a guarantee.</span></footer>
  </main>;
}
