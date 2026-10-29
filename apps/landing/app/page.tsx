"use client";

import Image from "next/image";
import dynamic from "next/dynamic";
import { useEffect, useRef, useState } from "react";
import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { SplitText } from "gsap/SplitText";
import Lenis from "lenis";
import manifest from "../public/release-manifest.json";
import "./experience.css";

const Scene3D = dynamic(() => import("./scene3d"), { ssr: false });

const scannerUrl = "https://trustshield-ai-ecru.vercel.app";

const screens = [
  { id: "check", label: "Check", image: "/screens/check.webp", alt: "TrustShield message check screen", title: "Check what arrived.", text: "Review suspicious SMS, WhatsApp text, links and screenshots. The Android app computes supported checks on your device." },
  { id: "result", label: "Result", image: "/screens/result.webp", alt: "TrustShield risk result with quoted evidence", title: "Understand the signal.", text: "Risk indicators come with exact quotes as evidence. Use them as guidance, then verify important requests independently." },
  { id: "call-shield", label: "Call Shield", image: "/screens/call-shield.webp", alt: "TrustShield Call Shield screen", title: "Stay aware on a call.", text: "With your consent, Call Shield listens through the microphone during a live session and can speak a verdict." },
  { id: "qr-guard", label: "QR Guard", image: "/screens/qr-guard.webp", alt: "TrustShield QR Guard payee check screen", title: "Check before you pay.", text: "QR Guard checks the decoded destination, UPI payee and tampering signals before you continue in your payment app." },
  { id: "settings", label: "Settings", image: "/screens/settings.webp", alt: "TrustShield accessibility and appearance settings", title: "Make it yours.", text: "TalkBack support helps make the app more accessible." },
];

const faq = [
  ["Does TrustShield guarantee that something is safe?", "No. It shows risk indicators and evidence to help you decide. A low risk result is not proof that a message, caller, link or payment is safe."],
  ["Does the Android app need internet access to check a message?", "The Android app has no INTERNET permission. Supported verdicts are computed on the phone. The separate web scanner runs online and sends submitted content to its service."],
  ["What does Call Shield listen to?", "With your consent, Call Shield reviews speech captured by the phone microphone during a live session and can speak its verdict. It does not capture the other side of a phone call."],
  ["What is Bomb Shield, and does the app read my SMS or calls?", "Bomb Shield is optional and off by default. It spots a flood of messages or repeated calls (the kind SMS and call bomber sites cause) and alerts you. Only if you switch it on, Android asks for SMS, call-state and call-log access. It counts events in memory and never stores message text. Android does not let apps block numbers, so it guides you to mute and report instead. A demo mode shows it without any permission."],
  ["How does QR Guard help with a payment?", "It checks the decoded QR destination, UPI payee details and signs of tampering. Always confirm the payee and amount in your payment app."],
  ["Which languages can I use?", "TrustShield supports English, Hindi and Hinglish."],
];

function formatBytes(bytes: number) {
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

type Env = { quality: "high" | "low"; reduced: boolean };

export default function Home() {
  const root = useRef<HTMLElement>(null);
  const lenisRef = useRef<Lenis | null>(null);
  const [env, setEnv] = useState<Env | null>(null);
  const [scene, setScene] = useState<"pending" | "ready" | "off">("pending");
  const [sampleShown, setSampleShown] = useState(false);
  const [activeTab, setActiveTab] = useState(0);

  /* decide quality / motion once, after first paint */
  useEffect(() => {
    const id = window.setTimeout(() => {
      const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      const nav = navigator as Navigator & { deviceMemory?: number };
      const weak = window.innerWidth < 760 || (navigator.hardwareConcurrency || 8) <= 4 || (nav.deviceMemory ?? 8) <= 4;
      setEnv({ quality: weak ? "low" : "high", reduced });
    }, 60);
    return () => window.clearTimeout(id);
  }, []);

  /* scroll choreography */
  useEffect(() => {
    if (!env || !root.current) return;
    const { reduced } = env;
    gsap.registerPlugin(ScrollTrigger, SplitText);
    const el = root.current;
    let lenis: Lenis | null = null;
    let tick: ((t: number) => void) | null = null;
    if (!reduced) {
      lenis = new Lenis({ lerp: 0.1, wheelMultiplier: 0.95, anchors: { offset: -72 } });
      lenisRef.current = lenis;
      lenis.on("scroll", ScrollTrigger.update);
      tick = (t: number) => lenis!.raf(t * 1000);
      gsap.ticker.add(tick);
      gsap.ticker.lagSmoothing(0);
    }

    const ctx = gsap.context(() => {
      el.classList.add("ex-js");
      const bar = el.querySelector<HTMLElement>(".ex-progress span");
      ScrollTrigger.create({
        start: 0, end: "max",
        onUpdate: (s) => { if (bar) bar.style.transform = `scaleX(${s.progress})`; },
      });

      if (!reduced) {
        const h1 = el.querySelector<HTMLElement>("[data-hero-title]");
        if (h1) {
          SplitText.create(h1, {
            type: "words", mask: "words", autoSplit: true,
            onSplit: (self) => gsap.from(self.words, { yPercent: 115, rotate: 4, duration: 1.1, stagger: 0.09, ease: "power4.out", delay: 0.15 }),
          });
        }
        gsap.from("[data-hero-in]", { y: 28, opacity: 0, duration: 1, stagger: 0.1, ease: "power3.out", delay: 0.55 });
        gsap.from(".ex-nav", { y: -30, opacity: 0, duration: 0.9, ease: "power3.out", delay: 0.1 });

        el.querySelectorAll<HTMLElement>("[data-split]").forEach((t) => {
          SplitText.create(t, {
            type: "lines", mask: "lines", autoSplit: true,
            onSplit: (self) => gsap.from(self.lines, { yPercent: 110, duration: 1, stagger: 0.09, ease: "power4.out", scrollTrigger: { trigger: t, start: "top 86%", once: true } }),
          });
        });
        gsap.utils.toArray<HTMLElement>("[data-rv]").forEach((n) => {
          gsap.from(n, { y: 46, opacity: 0, duration: 0.95, ease: "power3.out", scrollTrigger: { trigger: n, start: "top 88%", once: true } });
        });
        gsap.utils.toArray<HTMLElement>(".ex-copy").forEach((n) => {
          gsap.fromTo(n, { y: 80, rotate: n.classList.contains("ex-copy--r") ? 1.4 : -1.4 }, {
            y: -40, rotate: 0, ease: "none",
            scrollTrigger: { trigger: n, start: "top bottom", end: "bottom top", scrub: 0.6 },
          });
        });
        gsap.utils.toArray<HTMLElement>("[data-parallax]").forEach((n) => {
          const k = Number(n.dataset.parallax) || 0.2;
          gsap.to(n, { yPercent: -100 * k, ease: "none", scrollTrigger: { trigger: n, start: "top bottom", end: "bottom top", scrub: true } });
        });

        /* tilt cards and magnetic buttons (pointer devices only) */
        if (window.matchMedia("(hover: hover)").matches) {
          el.querySelectorAll<HTMLElement>("[data-tilt]").forEach((card) => {
            const rx = gsap.quickTo(card, "rotationX", { duration: 0.5, ease: "power3.out" });
            const ry = gsap.quickTo(card, "rotationY", { duration: 0.5, ease: "power3.out" });
            gsap.set(card, { transformPerspective: 900 });
            card.addEventListener("pointermove", (e) => {
              const r = card.getBoundingClientRect();
              ry(((e.clientX - r.left) / r.width - 0.5) * 12);
              rx(-((e.clientY - r.top) / r.height - 0.5) * 10);
            });
            card.addEventListener("pointerleave", () => { rx(0); ry(0); });
          });
          el.querySelectorAll<HTMLElement>("[data-magnetic]").forEach((b) => {
            const mx = gsap.quickTo(b, "x", { duration: 0.4, ease: "power3.out" });
            const my = gsap.quickTo(b, "y", { duration: 0.4, ease: "power3.out" });
            b.addEventListener("pointermove", (e) => {
              const r = b.getBoundingClientRect();
              mx((e.clientX - (r.left + r.width / 2)) * 0.22);
              my((e.clientY - (r.top + r.height / 2)) * 0.28);
            });
            b.addEventListener("pointerleave", () => { mx(0); my(0); });
          });
        }
      }

      /* the pinned app-screens story: text slides up/down, phone crossfades */
      const track = el.querySelector<HTMLElement>(".ex-screens-track");
      if (track) {
        const stories = gsap.utils.toArray<HTMLElement>(".ex-story", track);
        const shots = gsap.utils.toArray<HTMLElement>(".ex-shot", track);
        const phone = track.querySelector<HTMLElement>(".ex-phone");
        const n = stories.length;
        const travel = reduced ? 0 : 64;
        let shown = -1;
        const paint = (p: number) => {
          const a = p * (n - 1);
          stories.forEach((s, i) => {
            const o = i - a;
            const v = Math.max(0, Math.min(1, 1 - Math.abs(o) * 1.7));
            s.style.opacity = String(v);
            s.style.transform = `translate3d(0, ${o * travel}px, 0)`;
            s.style.pointerEvents = v > 0.5 ? "auto" : "none";
            s.setAttribute("aria-hidden", v > 0.5 ? "false" : "true");
          });
          shots.forEach((s, i) => {
            const o = i - a;
            const k = Math.max(0, Math.min(1, (Math.abs(o) - 0.2) / 0.6));
            const v = 1 - k * k * (3 - 2 * k);
            s.style.opacity = String(v);
            s.style.transform = `translate3d(${reduced ? 0 : o * 28}px, 0, 0) scale(${1 - Math.min(1, Math.abs(o)) * 0.05})`;
          });
          if (phone && !reduced) phone.style.transform = `rotateY(${(p - 0.5) * -16}deg) rotateX(${Math.sin(p * Math.PI) * 4}deg)`;
          const idx = Math.round(a);
          if (idx !== shown) { shown = idx; setActiveTab(idx); }
        };
        paint(0);
        ScrollTrigger.create({ trigger: track, start: "top top", end: "bottom bottom", onUpdate: (s) => paint(s.progress), onRefresh: (s) => paint(s.progress) });
      }
    }, el);

    const refresh = window.setTimeout(() => ScrollTrigger.refresh(), 800);
    return () => {
      window.clearTimeout(refresh);
      ctx.revert();
      el.classList.remove("ex-js");
      if (tick) gsap.ticker.remove(tick);
      lenis?.destroy();
      lenisRef.current = null;
    };
  }, [env]);

  function goToScreen(i: number) {
    const track = root.current?.querySelector<HTMLElement>(".ex-screens-track");
    if (!track) return;
    const st = ScrollTrigger.getAll().find((s) => s.trigger === track);
    if (!st) return;
    const target = st.start + (i / (screens.length - 1)) * (st.end - st.start);
    if (lenisRef.current) lenisRef.current.scrollTo(target, { duration: 1.4 });
    else window.scrollTo({ top: target, behavior: "auto" });
  }

  const status = scene === "ready" ? "ex-3d-on" : scene === "off" ? "ex-3d-off" : "ex-3d-wait";

  return (
    <main id="top" ref={root} className={`ex-root ${status}`}>
      <div className="ex-progress" aria-hidden="true"><span /></div>
      {env && scene !== "off" ? (
        <Scene3D quality={env.quality} reduced={env.reduced} onReady={() => setScene("ready")} onFail={() => setScene("off")} />
      ) : null}
      <div className="ex-fallback" aria-hidden="true">
        <Image src="/gemini/shield-cutout.webp" alt="" width={719} height={849} priority sizes="(max-width: 900px) 62vw, 34vw" />
      </div>

      <header className="ex-nav">
        <a className="ex-brand" href="#top" aria-label="TrustShield AI home"><Image src="/logo.png" alt="TrustShield AI" width={154} height={42} priority /></a>
        <nav className="ex-links" aria-label="Main navigation">
          <a href="#story">How it works</a><a href="#screens">Screens</a><a href="#privacy">Privacy</a><a href="#faq">FAQ</a>
        </nav>
        <a className="ex-pill" href={manifest.downloadUrl} download data-magnetic>Get the app <span aria-hidden="true">↓</span></a>
      </header>

      {/* 0 · hero */}
      <section className="ex-hero ex-chap" data-chapter="0" aria-labelledby="hero-title">
        <div className="ex-hero-copy">
          <p className="ex-eyebrow" data-hero-in><span className="ex-dot" />A second look at suspicious messages</p>
          <h1 id="hero-title" data-hero-title>Pause before you pay.</h1>
          <p className="ex-lede" data-hero-in>Check a message, link, QR code, payment request or call. See the risk and the exact words behind it.</p>
          <div className="ex-actions" data-hero-in>
            <a className="ex-btn ex-btn-primary" href={scannerUrl} rel="noopener" data-magnetic>Use online scanner <span aria-hidden="true">↗</span></a>
            <a className="ex-btn ex-btn-ghost" href={manifest.downloadUrl} download data-magnetic>Download APK for Android <span aria-hidden="true">↓</span></a>
          </div>
          <p className="ex-note" data-hero-in>English · हिंदी · Hinglish <span aria-hidden="true">/</span> Android checks run on your phone</p>
        </div>
        <div className="ex-hero-foot" data-hero-in>
          <span>Version {manifest.version} · {formatBytes(manifest.sizeBytes)} · signed</span>
          <span className="ex-scrollhint" aria-hidden="true"><i />Scroll</span>
        </div>
      </section>

      {/* ribbon */}
      <div className="ex-ribbon" aria-hidden="true">
        <div className="ex-ribbon-track">
          {[0, 1].map((k) => (
            <span key={k}>Pause. Read. Verify. <b>✦</b> रुकिए · पढ़िए · जाँचिए <b>✦</b> Ruko. Padho. Verify karo. <b>✦</b> Pause. Read. Verify. <b>✦</b> रुकिए · पढ़िए · जाँचिए <b>✦</b> Ruko. Padho. Verify karo. <b>✦</b></span>
          ))}
        </div>
      </div>

      <div id="story" />
      {/* 1 · messages */}
      <section className="ex-chap ex-chap--l" data-chapter="1" aria-labelledby="c1">
        <div className="ex-copy">
          <p className="ex-eyebrow">01 · Messages and screenshots</p>
          <h2 id="c1" data-split>Read the words that matter.</h2>
          <p>Paste a text or link, or share a screenshot. TrustShield shows the risk level with exact quotes and a plain explanation of the signals, so you can slow down and verify through a channel you already trust.</p>
          <ul className="ex-chips" aria-label="Examples of quoted signals"><li>“blocked today”</li><li>“share your OTP”</li><li>“verify your details”</li></ul>
        </div>
      </section>

      {/* 2 · QR guard */}
      <section className="ex-chap ex-chap--r" data-chapter="2" aria-labelledby="c2">
        <div className="ex-copy ex-copy--r">
          <p className="ex-eyebrow">02 · QR Guard</p>
          <h2 id="c2" data-split>Check the payee first.</h2>
          <p>Inspect decoded QR details, UPI payee information and tampering signals before paying. A sticker pasted over a shop&apos;s code is a common trick, so always confirm the payee and amount in your payment app.</p>
        </div>
      </section>

      {/* 3 · call shield */}
      <section className="ex-chap ex-chap--l" data-chapter="3" aria-labelledby="c3">
        <div className="ex-copy">
          <p className="ex-eyebrow">03 · Call Shield</p>
          <h2 id="c3" data-split>Live listening, with consent.</h2>
          <p>During a live session, Call Shield reviews the speech the phone microphone can hear and can speak a verdict. It does not capture the other side of a phone call.</p>
        </div>
      </section>

      {/* 4 · bomb shield */}
      <section className="ex-chap ex-chap--r" data-chapter="4" aria-labelledby="c4">
        <div className="ex-copy ex-copy--r">
          <p className="ex-eyebrow">04 · Optional · off by default</p>
          <h2 id="c4" data-split>Bomb Shield spots message and call floods.</h2>
          <p>If you switch it on, it can request SMS, call-state and call-log access. It counts events in memory and does not store message text. Android does not let apps block numbers, so it guides you to mute and report.</p>
        </div>
      </section>

      {/* 5 · privacy */}
      <section className="ex-chap ex-chap--l" id="privacy" data-chapter="5" aria-labelledby="c5">
        <div className="ex-copy">
          <p className="ex-eyebrow">Android privacy</p>
          <h2 id="c5" data-split>Your core check stays on your phone.</h2>
          <p>The Android app has no INTERNET permission, so it cannot send your scans to a server. Verdicts are computed on-device. The separate web scanner is online and processes what you submit. SMS and call access is requested only when you opt in to Bomb Shield.</p>
          <span className="ex-seal">NO INTERNET PERMISSION</span>
        </div>
      </section>

      {/* 6 · screens + sample + languages + faq */}
      <div data-chapter="6">
        <section className="ex-screens" id="screens" aria-label="App screens">
          <div className="ex-screens-track">
            <div className="ex-screens-stage">
              <div className="ex-screens-head">
                <p className="ex-eyebrow">Real app screens · English</p>
                <h2 data-split>Protection that stays close.</h2>
              </div>
              <div className="ex-screens-grid">
                <div className="ex-stories" aria-live="polite">
                  {screens.map((s, i) => (
                    <article className="ex-story" key={s.id}>
                      <span className="ex-count">0{i + 1} / 0{screens.length}</span>
                      <h3>{s.title}</h3>
                      <p>{s.text}</p>
                    </article>
                  ))}
                </div>
                <div className="ex-phone-wrap">
                  <div className="ex-phone" role="img" aria-label="TrustShield Android app screens">
                    <div className="ex-phone-notch" />
                    <div className="ex-phone-screen">
                      {screens.map((s, i) => (
                        <div className="ex-shot" key={s.id} style={{ opacity: i === 0 ? 1 : 0 }}>
                          <Image src={s.image} alt={s.alt} width={760} height={1689} sizes="(max-width: 900px) 52vw, 320px" priority={i === 0} />
                        </div>
                      ))}
                    </div>
                  </div>
                </div>
              </div>
              <div className="ex-tabs" role="group" aria-label="Jump to an app screen">
                {screens.map((s, i) => <button type="button" key={s.id} aria-pressed={activeTab === i} className={activeTab === i ? "on" : ""} onClick={() => goToScreen(i)}>{s.label}</button>)}
              </div>
            </div>
          </div>
        </section>

        <section className="ex-sample ex-wrap" aria-labelledby="sample-title">
          <div className="ex-sample-copy" data-rv>
            <p className="ex-eyebrow">A local example · no network</p>
            <h2 id="sample-title">Evidence you can point to.</h2>
            <p>This canned message shows how TrustShield can connect a risk indicator to the words that triggered it. It does not make a real assessment.</p>
            <button className="ex-btn ex-btn-primary" type="button" onClick={() => setSampleShown(!sampleShown)} data-magnetic>{sampleShown ? "Hide the example" : "Try a sample"} <span aria-hidden="true">{sampleShown ? "−" : "+"}</span></button>
          </div>
          <div className="ex-sample-card" data-tilt data-rv>
            <div className="ex-sample-head"><span>MESSAGE EXAMPLE</span><span className="ex-pillbadge">{sampleShown ? "EVIDENCE SHOWN" : "TAP TO REVEAL"}</span></div>
            <p className="ex-sample-msg">“Your bank account will be blocked today. Verify your details now to keep access.”</p>
            {sampleShown ? (
              <div className="ex-evidence"><span className="ex-risk" /><div><strong>Risk signals in this example</strong><p><mark>“blocked today”</mark> adds urgency. <mark>“Verify your details”</mark> asks for sensitive information.</p></div></div>
            ) : (
              <div className="ex-hint">Select “Try a sample” to reveal the quoted signals.</div>
            )}
            <span className="ex-fine">Illustrative example. No content is sent or analysed.</span>
          </div>
        </section>

        <section className="ex-lang ex-wrap" aria-labelledby="lang-title">
          <div data-rv>
            <p className="ex-eyebrow">Made for the way you speak</p>
            <h2 id="lang-title">English. हिंदी. Hinglish.</h2>
            <p>Check suspicious content in English, Hindi and Hinglish, with the evidence shown in context.</p>
          </div>
          <div className="ex-specimens" aria-label="Supported languages">
            <span data-tilt data-rv>“Verify now”</span><span data-tilt data-rv>“अभी पुष्टि करें”</span><span data-tilt data-rv>“Abhi verify karo”</span>
          </div>
        </section>

        <section className="ex-faq ex-wrap" id="faq" aria-labelledby="faq-title">
          <div data-rv><p className="ex-eyebrow">Good to know</p><h2 id="faq-title">Questions, answered.</h2></div>
          <div className="ex-faq-list">
            {faq.map(([q, a]) => (
              <details className="ex-faq-item" key={q} data-rv>
                <summary>{q}<span aria-hidden="true">＋</span></summary>
                <p>{a}</p>
              </details>
            ))}
          </div>
        </section>
      </div>

      {/* 7 · install */}
      <section className="ex-install" data-chapter="7" aria-labelledby="install-title">
        <div className="ex-install-bg" aria-hidden="true"><Image src="/gemini/hero-glacier.webp" alt="" fill sizes="100vw" data-parallax="0.12" /></div>
        <div className="ex-install-inner ex-wrap">
          <div className="ex-install-copy" data-rv>
            <p className="ex-eyebrow ex-eyebrow-light">Android app · version {manifest.version}</p>
            <h2 id="install-title">Take the pause with you.</h2>
            <p>Download the APK directly. Compare its SHA-256 with the release details before installing.</p>
            <div className="ex-actions">
              <a className="ex-btn ex-btn-light" href={manifest.downloadUrl} download data-magnetic>Download APK for Android <span aria-hidden="true">↓</span></a>
              <a className="ex-btn ex-btn-outline" href="/download" data-magnetic>Release details and QR →</a>
            </div>
          </div>
          <div className="ex-install-card" data-rv>
            <ol className="ex-steps">
              <li><span>01</span><p>Download the APK from this page.</p></li>
              <li><span>02</span><p>If Android asks, allow your browser to install apps for this installation.</p></li>
              <li><span>03</span><p>Compare the file’s SHA-256 below, then follow Android’s install prompts.</p></li>
            </ol>
            <dl className="ex-facts">
              <div><dt>File size</dt><dd>{formatBytes(manifest.sizeBytes)}</dd></div>
              <div><dt>Minimum Android</dt><dd>{manifest.minimumAndroidVersion} or newer</dd></div>
              <div className="ex-hash"><dt>SHA-256</dt><dd>{manifest.sha256}</dd></div>
            </dl>
          </div>
        </div>
      </section>

      <footer className="ex-footer">
        <div className="ex-wrap ex-footer-main">
          <a href="#top" aria-label="Back to top"><Image src="/logo.png" alt="TrustShield AI" width={146} height={40} /></a>
          <p>Research / Tech Expo prototype.<br />Results are guidance, not a guarantee.</p>
          <nav aria-label="Footer navigation"><a href="#story">How it works</a><a href="#screens">Screens</a><a href="#privacy">Privacy</a><a href="#faq">FAQ</a></nav>
        </div>
        <div className="ex-wrap ex-footer-bottom"><span>© TrustShield AI</span><span>Pause. Read. Verify independently.</span><a href="#top">Back to top ↑</a></div>
      </footer>
    </main>
  );
}
