"use client";

import Image from "next/image";
import { useEffect, useState } from "react";

const faqs = [
  ["Does TrustShield guarantee a message is safe?", "No. It looks for signals that may indicate risk and shows why they matter. A low-risk result is not proof that a message, person or payment is safe."],
  ["Does Android send my message to a server?", "The Android app's local scan path runs on your device. Optional backend enrichment is off by default. If it is enabled in a future build, the app must explain what is sent before you choose it."],
  ["What happens when I use the web checker?", "The web checker sends submitted analysis input to its API. Signed-in history may store a summary and result. Review the privacy policy before submitting sensitive content."],
  ["Can Call Shield hear the other person on a phone call?", "No. It analyses speech that reaches your microphone while you open and start the feature. Speech recognition availability varies by device and may use a network service."],
  ["Is the Android app available to download?", "A public release link is not available yet. We will add one here when a build is ready to share."],
];

export default function Home() {
  const [dark, setDark] = useState(false);
  useEffect(() => {
    const observer = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add("in-view");
          observer.unobserve(entry.target);
        }
      });
    }, { threshold: 0.12 });
    document.querySelectorAll(".reveal").forEach((node) => observer.observe(node));
    return () => observer.disconnect();
  }, []);

  return (
    <main className={dark ? "site dark" : "site"}>
      <header className="nav wrap">
        <a className="brand" href="#top" aria-label="TrustShield AI home"><Image src="/logo.png" alt="TrustShield AI" width={172} height={44} priority /></a>
        <nav aria-label="Main navigation"><a href="#features">What it checks</a><a href="#how">How it works</a><a href="#pricing">Plans</a></nav>
        <button className="theme-toggle" onClick={() => setDark(!dark)} aria-label={`Switch to ${dark ? "light" : "dark"} theme`}>{dark ? "☼" : "☾"}<span>{dark ? "Light" : "Dark"}</span></button>
      </header>

      <section className="hero wrap" id="top">
        <div className="hero-copy reveal">
          <p className="eyebrow"><span className="status-dot" /> A calmer second opinion</p>
          <h1>Pause before<br />you <em>pay.</em></h1>
          <p className="lede">A message can look official and still ask for the wrong thing. Check the signals, read the evidence, then choose your next step.</p>
          <div className="hero-actions"><a className="button primary" href="https://trustshield-ai-ecru.vercel.app" target="_blank" rel="noreferrer">Try it now <span>↗</span></a><a className="button secondary" href="#download">Download the app <span>↓</span></a></div>
          <p className="microcopy">Android release link coming soon · Results are guidance, not a guarantee</p>
        </div>
        <div className="hero-art" aria-label="Illustration of a message being checked for warning signs">
          <div className="orbit orbit-one" /><div className="orbit orbit-two" />
          <div className="phone-scene"><div className="phone-frame"><div className="phone-speaker" /><div className="phone-head"><Image src="/logo-icon.png" alt="" width={34} height={34} /><span>TrustShield check</span><b>•••</b></div><div className="message-card"><small>MESSAGE PREVIEW</small><p>“Your account will close today. Verify now to keep access.”</p><div className="message-link">secure-bank.help <span>↗</span></div></div><div className="scan-line" /><div className="evidence"><span className="evidence-icon">!</span><div><b>Urgency + credential request</b><small>Two signals found in this message</small></div><span className="evidence-arrow">↗</span></div><div className="risk"><div><small>RISK INDICATOR</small><strong>High signal</strong></div><div className="risk-meter"><i /><i /><i /><i /><i /><i /></div></div><div className="phone-foot">A signal to pause and verify</div></div></div>
          <div className="float-note note-a"><span>↗</span> Link shown as text</div><div className="float-note note-b"><i /> Evidence, not a verdict</div>
        </div>
        <div className="hero-bottom"><span>Built for the moment before you tap</span><span className="hero-rule" /><span>Hinglish · Hindi · English</span></div>
      </section>

      <section className="trust-strip"><div className="wrap strip-inner"><span>LOOK FOR THE ASK</span><span>READ THE REASON</span><span>VERIFY OUTSIDE THE MESSAGE</span></div></section>

      <section className="section wrap" id="features">
        <div className="section-heading reveal"><p className="eyebrow">What you can check</p><h2>Small signals.<br /><em>Clearer choices.</em></h2><p>One place to pause over the things that arrive in a hurry.</p></div>
        <div className="feature-grid">
          <article className="feature-card feature-wide reveal"><div className="feature-copy"><span className="feature-index">01 / MESSAGE</span><h3>Spot the pressure<br />in the words.</h3><p>Check pasted text and shared screenshots for urgency, authority claims, secrecy and requests for money or credentials.</p><a href="#how">See the steps <span>↗</span></a></div><div className="feature-graphic text-graphic"><div className="quote-line">“Your account closes <mark>in 10 minutes</mark>.”</div><div className="signal-label"><i /> Urgency signal</div><div className="quote-line second">“Share the <mark>OTP now</mark> to cancel.”</div><div className="signal-label"><i /> Credential request</div></div></article>
          <article className="feature-card reveal"><span className="feature-index">02 / LINK</span><div className="mini-icon link-icon">↗</div><h3>Inspect the link.<br />Don’t open it.</h3><p>Static checks look at the address itself. The checker does not visit the destination just to analyse it.</p><div className="url-pill">bank-help.example <span>not opened</span></div></article>
          <article className="feature-card reveal"><span className="feature-index">03 / QR</span><div className="mini-icon qr-icon">▦</div><h3>Check before<br />you scan to pay.</h3><p>Compare a decoded UPI payee with the payee you registered. A QR label alone does not confirm who gets paid.</p><div className="qr-row"><span className="qr-sample">▦</span><span>Payee check<br /><b>Confirm in your UPI app</b></span></div></article>
          <article className="feature-card feature-call reveal"><span className="feature-index">04 / CALL SHIELD</span><div className="mini-icon mic-icon">◖</div><h3>Stay present<br />during a pressured call.</h3><p>Start a visible microphone session to review audible speech. It does not capture the other side of a phone call.</p><span className="call-caption">You start it. You can stop it.</span></article>
        </div>
      </section>

      <section className="how-section" id="how"><div className="wrap how-wrap"><div className="section-heading reveal"><p className="eyebrow">A simple pause</p><h2>From “urgent”<br />to <em>understood.</em></h2><p>TrustShield helps organise the evidence. The decision stays with you.</p></div><div className="steps"><article className="step reveal"><span className="step-number">01</span><div><h3>Bring it in</h3><p>Paste a message, share a screenshot, or point the camera at a QR code.</p></div><span className="step-line" /></article><article className="step reveal"><span className="step-number">02</span><div><h3>Review the signals</h3><p>See the patterns the checker found, with the evidence that triggered them.</p></div><span className="step-line" /></article><article className="step reveal"><span className="step-number">03</span><div><h3>Verify elsewhere</h3><p>Call the organisation using a number you already trust. Never use contact details from the suspicious message.</p></div><span className="step-line" /></article></div></div></section>

      <section className="privacy-section wrap" id="privacy"><div className="privacy-panel reveal"><div className="privacy-mark"><span>◌</span><span>DEVICE / SERVER</span></div><div><p className="eyebrow">Privacy, without the blanket claim</p><h2>Know where<br /><em>the check happens.</em></h2><p className="privacy-copy">Android’s local scan path analyses supported text and QR inputs on your device. Optional backend enrichment is off by default. Some speech recognisers may use a network service depending on device capability.</p><p className="privacy-copy">The web checker sends submitted input to its API. Signed-in web history may save a summary and result. Read the <a href="https://trustshield-ai-ecru.vercel.app/privacy" target="_blank" rel="noreferrer">web privacy details ↗</a> before sharing sensitive content.</p></div></div></section>

      <section className="roadmap-section" id="roadmap"><div className="wrap roadmap-wrap"><div className="section-heading reveal"><p className="eyebrow">In progress</p><h2>Built in the<br /><em>open, step by step.</em></h2><p>Roadmap items are plans, not current capabilities.</p></div><div className="roadmap-list"><div className="roadmap-item current"><span className="roadmap-dot" /><div><small>NOW · IN DEVELOPMENT</small><h3>Android scan and evidence flows</h3><p>Building out the core checks, local analysis and clear limits.</p></div></div><div className="roadmap-item"><span className="roadmap-dot" /><div><small>PLANNED</small><h3>Language coverage and accessibility</h3><p>More complete Hindi and Hinglish experiences across the app.</p></div></div><div className="roadmap-item"><span className="roadmap-dot" /><div><small>PLANNED · SUBJECT TO REVIEW</small><h3>Release and maintenance</h3><p>Release channel, support process and update cadence to be confirmed.</p></div></div><p className="inspiration-todo">Design research source: TODO — add the owner’s friend’s GitHub URL when provided.</p></div></div></section>

      <section className="plans-section wrap" id="pricing"><div className="section-heading center reveal"><p className="eyebrow">Plans</p><h2>Honest about <em>what’s next.</em></h2><p>No paid plan is available today. These tiers are ideas only.</p></div><div className="plans"><article className="plan-card reveal"><small>AVAILABLE NOW</small><h3>Preview</h3><p className="plan-price">Free <span>while in preview</span></p><p>Current web checker access, subject to availability and its own service limits.</p><span className="plan-tag">Availability may change</span></article><article className="plan-card plan-featured reveal"><small>PLANNED · NOT FOR SALE</small><h3>Personal</h3><p className="plan-price">TBD <span>pricing not set</span></p><p>Possible future features for individual users. Scope and privacy terms are not final.</p><span className="plan-tag">Roadmap concept</span></article><article className="plan-card reveal"><small>PLANNED · NOT FOR SALE</small><h3>Community</h3><p className="plan-price">TBD <span>pricing not set</span></p><p>Possible future tools for organisations. No commitments or launch date yet.</p><span className="plan-tag">Roadmap concept</span></article></div></section>

      <section className="team-section"><div className="wrap team-wrap"><div><p className="eyebrow">The people behind it</p><h2>Made with care.<br /><em>Names coming soon.</em></h2></div><p>Team names and roles will be added when confirmed by the owner. We won’t invent credentials or affiliations to fill the space.</p><span className="team-todo">TODO · OWNER TO PROVIDE APPROVED NAMES + ROLES</span></div></section>

      <section className="faq-section wrap" id="faq"><div className="section-heading reveal"><p className="eyebrow">Good questions</p><h2>Before you <em>check.</em></h2></div><div className="faq-list">{faqs.map(([question, answer]) => <details key={question} className="faq-item"><summary>{question}<span>＋</span></summary><p>{answer}</p></details>)}</div></section>

      <section className="download-section" id="download"><div className="wrap download-wrap reveal"><div><p className="eyebrow">Android app</p><h2>Take a breath.<br /><em>Then take a look.</em></h2><p>A public APK or store listing is not ready to share yet. We’ll post the official download link here when it is.</p></div><a className="button download-button" href="#download" aria-disabled="true" onClick={(event) => event.preventDefault()}>Download link coming soon <span>↗</span></a><div className="download-orbit" /></div></section>

      <footer className="footer"><div className="wrap footer-top"><a className="brand" href="#top"><Image src="/logo.png" alt="TrustShield AI" width={172} height={44} /></a><p>Think before you trust.<br /><span>Keep the final check in your hands.</span></p><div className="footer-links"><a href="#privacy">Privacy</a><a href="#faq">FAQ</a><a href="#roadmap">Roadmap</a><a href="https://github.com/shubhsaxena2020/trustshield-ai" target="_blank" rel="noreferrer">GitHub ↗</a></div></div><div className="wrap footer-bottom"><span>© 2026 TrustShield AI · Project information may change during development.</span><span>Risk signals are guidance, not certainty.</span></div></footer>
    </main>
  );
}

