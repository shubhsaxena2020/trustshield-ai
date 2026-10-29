"use client";

import Image from "next/image";
import { useEffect, useState } from "react";
import ShieldHero from "./shield-hero";
import manifest from "../public/release-manifest.json";

const scannerUrl = "https://trustshield-ai-ecru.vercel.app";
const screens = [
  { id: "check", label: "Check", image: "/screens/check.webp", alt: "TrustShield message check screen" },
  { id: "result", label: "Result", image: "/screens/result.webp", alt: "TrustShield risk result with quoted evidence" },
  { id: "call-shield", label: "Call Shield", image: "/screens/call-shield.webp", alt: "TrustShield Call Shield screen" },
  { id: "qr-guard", label: "QR Guard", image: "/screens/qr-guard.webp", alt: "TrustShield QR Guard payee check screen" },
  { id: "settings", label: "Settings", image: "/screens/settings.webp", alt: "TrustShield accessibility and appearance settings" },
];

const faq = [
  ["Does TrustShield guarantee that something is safe?", "No. It shows risk indicators and evidence to help you decide. A low risk result is not proof that a message, caller, link or payment is safe."],
  ["Does the Android app need internet access to check a message?", "The Android app has no INTERNET permission. Supported verdicts are computed on the phone. The separate web scanner runs online and sends submitted content to its service."],
  ["What does Call Shield listen to?", "With your consent, Call Shield reviews speech captured by the phone microphone during a live session and can speak its verdict. It does not capture the other side of a phone call."],
  ["What is Bomb Shield, and does the app read my SMS or calls?", "Bomb Shield is optional and off by default. It spots a flood of messages or repeated calls (the kind SMS and call bomber sites cause) and alerts you. Only if you switch it on, Android asks for SMS, call-state and call-log access. It counts events in memory and never stores message text. Android does not let apps block numbers, so it guides you to mute and report instead. A demo mode shows it without any permission."],
  ["How does QR Guard help with a payment?","It checks the decoded QR destination, UPI payee details and signs of tampering. Always confirm the payee and amount in your payment app."],
  ["Which languages can I use?", "TrustShield supports English, Hindi and Hinglish."],
];

function formatBytes(bytes: number) {
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

export default function Home() {
  const [screen, setScreen] = useState(0);
  const [sampleShown, setSampleShown] = useState(false);

  useEffect(() => {
    const stories = document.querySelectorAll<HTMLElement>("[data-screen-story]");
    const observer = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) setScreen(Number((entry.target as HTMLElement).dataset.screenStory));
      });
    }, { rootMargin: "-36% 0px -44% 0px" });
    stories.forEach((story) => observer.observe(story));
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const revealItems = document.querySelectorAll<HTMLElement>("[data-reveal]");
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      revealItems.forEach((item) => item.classList.add("is-visible"));
      return;
    }
    const observer = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add("is-visible");
          observer.unobserve(entry.target);
        }
      });
    }, { rootMargin: "0px 0px -8% 0px", threshold: 0.08 });
    revealItems.forEach((item) => observer.observe(item));
    return () => observer.disconnect();
  }, []);

  function selectScreen(index: number) {
    setScreen(index);
    const behavior = window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth";
    document.querySelector(`[data-screen-story="${index}"]`)?.scrollIntoView({ behavior, block: "center" });
  }

  return (
    <main id="top">
      <header className="nav-shell">
        <a className="brand" href="#top" aria-label="TrustShield AI home"><Image src="/logo.png" alt="TrustShield AI" width={154} height={42} priority /></a>
        <nav className="desktop-nav" aria-label="Main navigation">
          <a href="#how-it-works">How it works</a><a href="#features">Features</a><a href="#privacy">Privacy</a><a href="#faq">FAQ</a>
        </nav>
        <div className="nav-actions">
          <a className="nav-download" href={manifest.downloadUrl} download>Get the app <span aria-hidden="true">↓</span></a>
        </div>
      </header>

      <section className="hero" aria-labelledby="hero-title">
        <div className="hero-copy">
          <p className="eyebrow"><span className="live-dot" />A second look at suspicious messages</p>
          <h1 id="hero-title">Pause before you pay.</h1>
          <p className="hero-lede">Check a message, link, QR code, payment request or call. See the risk and the exact words behind it.</p>
          <div className="hero-actions">
            <a className="button button-primary" href={scannerUrl} rel="noopener">Use online scanner <span aria-hidden="true">↗</span></a>
            <a className="button button-secondary" href={manifest.downloadUrl} download>Download APK for Android <span aria-hidden="true">↓</span></a>
          </div>
          <p className="hero-note">English · हिंदी · Hinglish <span aria-hidden="true">/</span> Android checks run on your phone</p>
        </div>
        <div className="hero-art" aria-hidden="true"><ShieldHero /><div className="hero-label label-one">Exact words, made visible</div><div className="hero-label label-two">Your next step stays yours</div></div>
        <div className="hero-bottomline"><span>TrustShield AI</span><span>Research / Tech Expo prototype</span></div>
      </section>

      <section className="release-strip" aria-label="Android release details">
        <div className="release-item"><span className="release-kicker">ANDROID RELEASE</span><strong>Version {manifest.version}</strong></div>
        <div className="release-item"><span className="release-kicker">DOWNLOAD SIZE</span><strong>{formatBytes(manifest.sizeBytes)}</strong></div>
        <div className="release-hash"><span className="release-kicker">SHA-256</span><code>{manifest.sha256}</code></div>
        <a href={manifest.downloadUrl} download aria-label="Download TrustShield AI Android APK">Download the APK <span aria-hidden="true">↓</span></a>
      </section>

      <section className="intro-section content-width" id="how-it-works" data-reveal>
        <div className="section-heading"><p className="eyebrow">A clearer pause</p><h2>See what raised the flag.</h2><p>TrustShield checks suspicious content and points to the signals it found, so you can slow down and verify through a channel you already trust.</p></div>
        <div className="steps-grid">
          <article className="step-card"><span className="step-index">01</span><div className="step-glyph" aria-hidden="true">＋</div><h3>Bring it in</h3><p>Paste a text or link, share a screenshot, scan a QR code, or review a payment request or call.</p></article>
          <article className="step-card"><span className="step-index">02</span><div className="step-glyph" aria-hidden="true">“ ”</div><h3>Read the evidence</h3><p>See the risk level with exact quotes and a plain explanation of the signals.</p></article>
          <article className="step-card"><span className="step-index">03</span><div className="step-glyph" aria-hidden="true">↗</div><h3>Verify independently</h3><p>Contact the organisation or person using details you looked up yourself.</p></article>
        </div>
      </section>

      <section className="screens-section" id="features" data-reveal>
        <div className="screens-head content-width"><div className="section-heading"><p className="eyebrow">On your Android phone</p><h2>Protection that stays close.</h2><p>Explore the main screens from the Android app. Choose a view to see how each part works.</p></div><div className="screens-caption">REAL APP SCREENS <span>·</span> ENGLISH</div></div>
        <div className="screens-showcase content-width">
          <div className="phone-column">
          <div className="phone-frame"><div className="phone-notch" /><div className="phone-screen"><Image key={screens[screen].image} src={screens[screen].image} alt={screens[screen].alt} width={760} height={1689} sizes="(max-width: 700px) 62vw, 330px" /></div></div>
            <div className="screen-tabs" role="group" aria-label="Android app screens">{screens.map((item, index) => <button type="button" key={item.id} aria-pressed={screen === index} className={screen === index ? "screen-tab active" : "screen-tab"} onClick={() => selectScreen(index)}>{item.label}</button>)}</div>
          </div>
          <div className="screen-details" aria-label="Android app features">
            {[
              ["Check what arrived.", "Review suspicious SMS, WhatsApp text, links and screenshots. The Android app computes supported checks on your device."],
              ["Understand the signal.", "Risk indicators come with exact quotes as evidence. Use them as guidance, then verify important requests independently."],
              ["Stay aware on a call.", "With your consent, Call Shield listens through the microphone during a live session and can speak a verdict."],
              ["Check before you pay.", "QR Guard checks the decoded destination, UPI payee and tampering signals before you continue in your payment app."],
              ["Make it yours.", "TalkBack support helps make the app more accessible."],
            ].map(([title, description], index) => <article className="screen-story" data-screen-story={index} key={title}>
              <span className="screen-count">0{index + 1} / 05</span><h3>{title}</h3><p>{description}</p>
            </article>)}
            <div className="screen-controls"><button type="button" onClick={() => selectScreen((screen + screens.length - 1) % screens.length)} aria-label="Previous app screen">←</button><button type="button" onClick={() => selectScreen((screen + 1) % screens.length)} aria-label="Next app screen">→</button></div>
          </div>
        </div>
      </section>

      <section className="stats-band" aria-label="TrustShield at a glance"><div className="content-width stats-grid"><div><strong>0</strong><span>INTERNET permission<br />in the Android app</span></div><div><strong>3</strong><span>languages<br />English · Hindi · Hinglish</span></div><div><strong>2</strong><span>ways to check<br />Android app and web</span></div><p>Android verdicts are computed on-device. The web scanner runs online.</p></div></section>

      <section className="sample-section content-width" aria-labelledby="sample-title" data-reveal>
        <div className="sample-copy"><p className="eyebrow">A local example · no network</p><h2 id="sample-title">Evidence you can point to.</h2><p>This canned message shows how TrustShield can connect a risk indicator to the words that triggered it. It does not make a real assessment.</p><button className="button button-primary" type="button" onClick={() => setSampleShown(!sampleShown)}>{sampleShown ? "Hide the example" : "Try a sample"} <span aria-hidden="true">{sampleShown ? "−" : "+"}</span></button></div>
        <div className="sample-card"><div className="sample-card-head"><span>MESSAGE EXAMPLE</span><span className="sample-pill">{sampleShown ? "EVIDENCE SHOWN" : "TAP TO REVEAL"}</span></div><p className="sample-message">“Your bank account will be blocked today. Verify your details now to keep access.”</p>{sampleShown ? <div className="evidence-result"><span className="risk-dot" /><div><strong>Risk signals in this example</strong><p><mark>“blocked today”</mark> adds urgency. <mark>“Verify your details”</mark> asks for sensitive information.</p></div></div> : <div className="sample-hint">Select “Try a sample” to reveal the quoted signals.</div>}<span className="sample-disclaimer">Illustrative example. No content is sent or analysed.</span></div>
      </section>

      <section className="feature-grid-section content-width" id="tools" data-reveal>
        <div className="feature-intro"><p className="eyebrow">One product, focused tools</p><h2>From a message to a payment.</h2><p>Built to help you inspect the request before you act.</p></div>
        <article className="feature-card feature-large"><span className="feature-symbol" aria-hidden="true">“</span><span className="feature-label">MESSAGE + SCREENSHOT</span><h3>Read the words that matter.</h3><p>Check suspicious SMS, WhatsApp text, links and screenshots. See exact quotes alongside the risk evidence.</p></article>
        <article className="feature-card"><span className="feature-symbol" aria-hidden="true">◉</span><span className="feature-label">CALL SHIELD</span><h3>Live listening, with consent.</h3><p>During a live session, review speech the microphone can hear and get a spoken verdict.</p></article>
        <article className="feature-card"><span className="feature-symbol" aria-hidden="true">⌗</span><span className="feature-label">QR GUARD</span><h3>Check the payee first.</h3><p>Inspect decoded QR details, UPI payee information and tampering signals before paying.</p></article>
        <article className="feature-card"><span className="feature-symbol" aria-hidden="true">◐</span><span className="feature-label">PAYMENT ADVISORY</span><h3>A pause before money moves.</h3><p>Review payment requests and the signals that may deserve another look.</p></article>
        <article className="feature-card feature-bomb"><span className="feature-symbol" aria-hidden="true">⌁</span><span className="feature-label">OPTIONAL · OFF BY DEFAULT</span><h3>Bomb Shield spots message and call floods.</h3><p>If enabled, it can request SMS, call-state and call-log access. It counts events in memory and does not store message text. Android does not let apps block numbers.</p></article>
      </section>

      <section className="privacy-section" id="privacy" data-reveal><div className="privacy-inner content-width"><div className="privacy-mark" aria-hidden="true"><Image src="/logo-icon.png" alt="" width={92} height={92} /></div><div><p className="eyebrow">Android privacy</p><h2>Your core check stays on your phone.</h2><p>The Android app has no INTERNET permission, so it cannot send your scans to a server. Verdicts are computed on-device. The separate web scanner is online and processes what you submit. SMS and call access is requested only when you opt in to Bomb Shield.</p></div><span className="privacy-seal">NO INTERNET<br />PERMISSION</span></div></section>

      <section className="language-section content-width" data-reveal><div><p className="eyebrow">Made for the way you speak</p><h2>English. हिंदी. Hinglish.</h2><p>Check suspicious content in English, Hindi and Hinglish, with the evidence shown in context.</p></div><div className="language-specimens" aria-label="Supported languages"><span>“Verify now”</span><span>“अभी पुष्टि करें”</span><span>“Abhi verify karo”</span></div></section>

      <section className="faq-section content-width" id="faq" data-reveal><div className="section-heading"><p className="eyebrow">Good to know</p><h2>Questions, answered.</h2></div><div className="faq-list">{faq.map(([question, answer]) => <details className="faq-item" key={question}><summary>{question}<span aria-hidden="true">＋</span></summary><p>{answer}</p></details>)}</div></section>

      <section className="install-band"><div className="install-inner content-width"><div><p className="eyebrow">Android app · version {manifest.version}</p><h2>Take the pause with you.</h2><p>Download the APK directly. Compare its SHA-256 with the release details before installing.</p><a className="button button-primary" href={manifest.downloadUrl} download>Download APK for Android <span aria-hidden="true">↓</span></a></div><div><ol className="install-steps"><li><span>01</span><p>Download the APK from this page.</p></li><li><span>02</span><p>If Android asks, allow your browser to install apps for this installation.</p></li><li><span>03</span><p>Compare the file’s SHA-256 below, then follow Android’s install prompts.</p></li></ol><dl className="install-facts"><div><dt>File size</dt><dd>{formatBytes(manifest.sizeBytes)}</dd></div><div><dt>Minimum Android</dt><dd>{manifest.minimumAndroidVersion} or newer</dd></div><div><dt>SHA-256</dt><dd>{manifest.sha256}</dd></div></dl></div></div></section>

      <footer className="site-footer"><div className="footer-main content-width"><a className="footer-brand" href="#top"><Image src="/logo.png" alt="TrustShield AI" width={146} height={40} /></a><p>Research / Tech Expo prototype.<br />Results are guidance, not a guarantee.</p><nav aria-label="Footer navigation"><a href="#how-it-works">How it works</a><a href="#features">Features</a><a href="#privacy">Privacy</a><a href="#faq">FAQ</a></nav></div><div className="footer-bottom content-width"><span>© TrustShield AI</span><span>Pause. Read. Verify independently.</span><a href="#top">Back to top ↑</a></div></footer>
    </main>
  );
}
