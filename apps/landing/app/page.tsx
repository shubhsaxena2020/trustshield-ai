"use client";

import Image from "next/image";
import Link from "next/link";
import { useEffect, useState } from "react";
import ShieldHero from "./shield-hero";

type Language = "en" | "hi" | "hinglish";

const copy = {
  en: {
    nav: ["How it works", "Features", "Plans", "FAQ"], language: "Language", theme: "Theme",
    eyebrow: "A calmer second opinion", title: <>Pause before<br />you <em>pay.</em></>,
    intro: "Scam messages are designed to hurry you. TrustShield helps you slow down, see the signals, and choose your next step.",
    try: "Try it now", download: "Download the app", limited: "Results are guidance, not a guarantee.",
    proof: ["Scan the message", "Read the evidence", "Verify elsewhere"],
    featureEyebrow: "A little more clarity", featureTitle: <>See the signal.<br /><em>Keep the choice.</em></>,
    featureIntro: "One place to pause over messages, calls and QR codes that ask you to act fast.",
    features: [
      ["01 · SCAN MESSAGES", "Spot pressure in the words.", "Check text and shared screenshots for urgency, authority claims, secrecy, and requests for money or credentials."],
      ["02 · CALL SHIELD", "Stay present on a pressured call.", "Start a visible session to review speech your microphone can hear. It cannot capture the other side of a phone call."],
      ["03 · QR GUARD", "Check before you scan to pay.", "Review the decoded destination and compare a UPI payee with the one you intended. Confirm the final payment in your UPI app."],
      ["04 · PRIVACY", "Core scans stay on your phone.", "Supported text and QR checks run locally in the Android app. Optional speech recognition or enrichment can depend on device settings."]
    ],
    howEyebrow: "A simple pause", howTitle: <>From “urgent”<br />to <em>understood.</em></>, howIntro: "TrustShield organises the evidence. The decision stays with you.",
    steps: [["01", "Bring it in", "Paste a message, share a screenshot, or point the camera at a QR code."], ["02", "Read the evidence", "See the exact words and signals that prompted the risk indicator."], ["03", "Verify elsewhere", "Contact the organisation using a number you already trust." ]],
    evidence: "EXACT QUOTE", risk: "RISK INDICATOR", high: "More signals", quote: "“Your account will close today. Verify now to keep access.”", signal: "Urgency + credential request",
    privacyEyebrow: "Privacy, in plain language", privacyTitle: <>Know where<br /><em>the check happens.</em></>, privacyCopy: "Core text and QR scans in the Android app run on-device. The web checker sends submitted input to its API, and signed-in history may save scan metadata and results. Avoid sending sensitive content to the web checker.",
    plansEyebrow: "Plans", plansTitle: <>Free today.<br /><em>Clear about tomorrow.</em></>, plansIntro: "No made-up launch dates or prices. Planned tiers are not available yet.",
    plans: [["AVAILABLE", "Individuals", "Free", "For personal use with the current checker."], ["PLANNED", "Family", "Coming later", "Shared protection tools and family setup."], ["PLANNED", "Shop", "Coming later", "Simple checks for small shops and payment counters."], ["PLANNED", "Business", "Coming later", "Team controls and organisation support."]],
    roadmapEyebrow: "On the roadmap", roadmapTitle: <>Useful next steps,<br /><em>clearly labelled.</em></>, roadmapCopy: "These are plans, not current features.", roadmapItem: "Chrome extension", roadmapDesc: "A browser companion for checking suspicious links and messages. Planned; no release date set.",
    teamEyebrow: "The people building it", teamTitle: "Built with care.", team: [["Shubh Saxena", "Team leader"], ["Sugandhi Agrawal", "Team"], ["Varchasva Pratap Singh", "Team"], ["Vaishnavi Shukla", "Team"], ["Stuti Jha", "Team"]],
    faqEyebrow: "FAQ", faqTitle: <>Questions before<br /><em>you try it?</em></>, faqs: [
      ["Does TrustShield guarantee a message is safe?", "No. It highlights patterns that may indicate risk. A low-risk result is not proof that a message, person or payment is safe."],
      ["Does the Android app send every scan to a server?", "The core supported text and QR checks run locally. Optional speech recognition and enrichment can depend on device settings."],
      ["What happens in the web checker?", "Submitted input is sent to the API. Signed-in history can store scan type and result metadata. Avoid submitting sensitive content."],
      ["Can Call Shield hear the other person on a phone call?", "No. It reviews speech that reaches your microphone while you open and start the feature."],
      ["Can I download the Android app today?", "The public APK is not published yet. The download page shows the verified release details when an APK is available."]
    ],
    downloadEyebrow: "Android app", downloadTitle: <>Your next check,<br /><em>one tap away.</em></>, downloadCopy: "See the install steps and release details. We will only show a download when a verified APK is published.",
    footer: "A second opinion, not a guarantee.", privacyLink: "Privacy notice", downloadLink: "Download details", backTop: "Back to top", dark: "Dark", light: "Light"
  },
  hinglish: {
    nav: ["Kaise kaam karta hai", "Features", "Plans", "FAQ"], language: "Bhasha", theme: "Theme",
    eyebrow: "Ek shaant second opinion", title: <>Payment se pehle<br /><em>ruk kar dekho.</em></>,
    intro: "Scam messages aapko jaldi karne par majboor karte hain. TrustShield signals dikhata hai, taaki aap soch kar agla step lein.",
    try: "Abhi try karein", download: "App download karein", limited: "Result guidance hai, guarantee nahi.",
    proof: ["Message scan karein", "Evidence padhein", "Alag se verify karein"],
    featureEyebrow: "Thodi aur clarity", featureTitle: <>Signal samjhein.<br /><em>Faisla aapka.</em></>,
    featureIntro: "Jaldi karne ko kehne wale messages, calls aur QR codes par rukne ki ek jagah.",
    features: [
      ["01 · MESSAGE SCAN", "Words mein pressure dekhein.", "Text ya screenshot mein urgency, authority claims, secrecy, paise ya credentials ki maang check karein."],
      ["02 · CALL SHIELD", "Pressure wali call mein alert rahein.", "Mic tak aane wali speech review karne ke liye session khud start karein. Yeh phone call ke doosre side ko capture nahi karta."],
      ["03 · QR GUARD", "Payment scan se pehle check karein.", "Decoded destination dekhein aur UPI payee ko apne intended payee se milayein. Payment UPI app mein confirm karein."],
      ["04 · PRIVACY", "Core scans aapke phone par rehte hain.", "Android app mein supported text aur QR checks device par chalte hain. Optional speech recognition settings par depend kar sakta hai."]
    ],
    howEyebrow: "Ek chhota pause", howTitle: <>“Urgent” se<br /><em>samajh tak.</em></>, howIntro: "TrustShield evidence samajhne mein madad karta hai. Faisla aapka hai.",
    steps: [["01", "Yahan laayein", "Message paste karein, screenshot share karein, ya QR par camera rakhein."], ["02", "Evidence padhein", "Exact words aur signals dekhein jinke chalte risk indicator dikha."], ["03", "Alag se verify karein", "Organisation ko us number par contact karein jise aap pehle se trust karte hain."]],
    evidence: "EXACT QUOTE", risk: "RISK INDICATOR", high: "Zyada signals", quote: "“Aaj aapka account band ho jayega. Access ke liye abhi verify karein.”", signal: "Urgency + credentials ki maang",
    privacyEyebrow: "Privacy, saaf shabdon mein", privacyTitle: <>Samjhein check<br /><em>kahan hota hai.</em></>, privacyCopy: "Android app mein core text aur QR scans device par chalte hain. Web checker input ko API par bhejta hai aur login history mein scan type aur result metadata save ho sakta hai. Sensitive content web checker par na bhejein.",
    plansEyebrow: "Plans", plansTitle: <>Aaj free.<br /><em>Kal ke baare mein saaf.</em></>, plansIntro: "Koi banayi hui launch date ya price nahi. Planned tiers abhi available nahi hain.",
    plans: [["AVAILABLE", "Individuals", "Free", "Current checker ke saath personal use."], ["PLANNED", "Family", "Baad mein", "Shared protection tools aur family setup."], ["PLANNED", "Shop", "Baad mein", "Chhoti dukaan aur payment counter ke liye checks."], ["PLANNED", "Business", "Baad mein", "Team controls aur organisation support."]],
    roadmapEyebrow: "Roadmap par", roadmapTitle: <>Agla useful step,<br /><em>saaf label ke saath.</em></>, roadmapCopy: "Yeh plans hain, abhi ke features nahi.", roadmapItem: "Chrome extension", roadmapDesc: "Suspicious links aur messages check karne ke liye browser companion. Planned; release date tay nahi.",
    teamEyebrow: "Banane wali team", teamTitle: "Soch samajh kar bana rahe hain.", team: [["Shubh Saxena", "Team leader"], ["Sugandhi Agrawal", "Team"], ["Varchasva Pratap Singh", "Team"], ["Vaishnavi Shukla", "Team"], ["Stuti Jha", "Team"]],
    faqEyebrow: "FAQ", faqTitle: <>Try karne se pehle<br /><em>koi sawaal?</em></>, faqs: [
      ["Kya TrustShield message safe hone ki guarantee deta hai?", "Nahi. Yeh risk ke patterns dikhata hai. Low-risk ka matlab yeh proof nahi ki message, person ya payment safe hai."],
      ["Kya Android app har scan server par bhejta hai?", "Core supported text aur QR checks device par hote hain. Optional speech recognition aur enrichment settings par depend kar sakta hai."],
      ["Web checker mein kya hota hai?", "Input API ko bheja jata hai. Login history scan type aur result metadata rakh sakti hai. Sensitive content submit na karein."],
      ["Kya Call Shield phone call ke doosre person ko sun sakta hai?", "Nahi. Feature khol kar start karne par yeh sirf mic tak pahunchi speech review karta hai."],
      ["Kya Android app ab download kar sakte hain?", "Public APK abhi publish nahi hua. Verified APK available hote hi download page par details dikhengi."]
    ],
    downloadEyebrow: "Android app", downloadTitle: <>Agla check,<br /><em>ek tap door.</em></>, downloadCopy: "Install steps aur release details dekhein. Verified APK publish hone par hi download dikhayenge.",
    footer: "Second opinion, guarantee nahi.", privacyLink: "Privacy notice", downloadLink: "Download details", backTop: "Upar jaayein", dark: "Dark", light: "Light"
  },
  hi: {
    nav: ["कैसे काम करता है", "फ़ीचर्स", "योजनाएँ", "सवाल"], language: "भाषा", theme: "थीम",
    eyebrow: "एक शांत दूसरी राय", title: <>भुगतान से पहले<br /><em>रुककर सोचें।</em></>,
    intro: "धोखाधड़ी वाले संदेश जल्दबाज़ी कराते हैं। TrustShield संकेत दिखाता है, ताकि आप सोचकर अगला कदम चुनें।",
    try: "अभी आज़माएँ", download: "ऐप डाउनलोड करें", limited: "नतीजा केवल मार्गदर्शन है, गारंटी नहीं।",
    proof: ["संदेश जाँचें", "सबूत पढ़ें", "अलग से पुष्टि करें"],
    featureEyebrow: "थोड़ी और स्पष्टता", featureTitle: <>संकेत समझें।<br /><em>फ़ैसला आपका।</em></>,
    featureIntro: "जल्दबाज़ी कराने वाले संदेशों, कॉल और QR कोड पर सोचने की एक जगह।",
    features: [
      ["01 · संदेश जाँच", "शब्दों में दबाव पहचानें।", "टेक्स्ट या स्क्रीनशॉट में जल्दबाज़ी, अधिकार का दावा, गोपनीयता और पैसे या जानकारी की माँग जाँचें।"],
      ["02 · कॉल शील्ड", "दबाव वाली कॉल में सतर्क रहें।", "माइक तक पहुँची आवाज़ देखने के लिए सत्र स्वयं शुरू करें। यह फ़ोन कॉल के दूसरे व्यक्ति को रिकॉर्ड नहीं करता।"],
      ["03 · QR गार्ड", "भुगतान से पहले जाँचें।", "डिकोड किया गया पता देखें और UPI पाने वाले की पुष्टि अपने ऐप में करें।"],
      ["04 · गोपनीयता", "मुख्य जाँच आपके फ़ोन पर रहती है।", "Android ऐप में समर्थित टेक्स्ट और QR जाँच डिवाइस पर होती है। वैकल्पिक speech recognition सेटिंग पर निर्भर हो सकती है।"]
    ],
    howEyebrow: "एक छोटा विराम", howTitle: <>“ज़रूरी” से<br /><em>समझ तक।</em></>, howIntro: "TrustShield सबूत समझने में मदद करता है। निर्णय आपका है।",
    steps: [["01", "यहाँ लाएँ", "संदेश चिपकाएँ, स्क्रीनशॉट साझा करें या कैमरा QR पर रखें।"], ["02", "सबूत पढ़ें", "वे सटीक शब्द और संकेत देखें जिनसे जोखिम सूचक दिखा।"], ["03", "अलग से पुष्टि करें", "पहले से भरोसेमंद नंबर पर संस्था से संपर्क करें।"]],
    evidence: "सटीक उद्धरण", risk: "जोखिम संकेतक", high: "अधिक संकेत", quote: "“आज आपका खाता बंद हो जाएगा। पहुँच बनाए रखने के लिए अभी पुष्टि करें।”", signal: "जल्दबाज़ी + जानकारी की माँग",
    privacyEyebrow: "गोपनीयता, साफ़ शब्दों में", privacyTitle: <>जानें जाँच<br /><em>कहाँ होती है।</em></>, privacyCopy: "Android ऐप की मुख्य टेक्स्ट और QR जाँच डिवाइस पर होती है। वेब checker भेजा गया input API को भेजता है और लॉगिन history में scan का प्रकार व नतीजे का metadata रह सकता है। वेब checker पर संवेदनशील जानकारी न भेजें।",
    plansEyebrow: "योजनाएँ", plansTitle: <>आज मुफ़्त।<br /><em>आगे की बात साफ़।</em></>, plansIntro: "कोई काल्पनिक तारीख़ या कीमत नहीं। योजनाएँ अभी उपलब्ध नहीं हैं।",
    plans: [["उपलब्ध", "व्यक्तिगत", "मुफ़्त", "मौजूदा checker का निजी उपयोग।"], ["योजना", "परिवार", "बाद में", "साझा सुरक्षा और परिवार सेटअप।"], ["योजना", "दुकान", "बाद में", "छोटी दुकानों और payment counter के लिए।"], ["योजना", "व्यवसाय", "बाद में", "टीम नियंत्रण और संगठन सहायता।"]],
    roadmapEyebrow: "आगे की योजना", roadmapTitle: <>अगले उपयोगी कदम,<br /><em>साफ़ तौर पर।</em></>, roadmapCopy: "ये योजनाएँ हैं, अभी के फ़ीचर नहीं।", roadmapItem: "Chrome extension", roadmapDesc: "संदिग्ध link और message जाँचने के लिए browser साथी। योजना में है; तारीख़ तय नहीं।",
    teamEyebrow: "इसे बनाने वाली टीम", teamTitle: "ध्यान से बनाया गया।", team: [["Shubh Saxena", "टीम लीडर"], ["Sugandhi Agrawal", "टीम"], ["Varchasva Pratap Singh", "टीम"], ["Vaishnavi Shukla", "टीम"], ["Stuti Jha", "टीम"]],
    faqEyebrow: "सवाल-जवाब", faqTitle: <>आज़माने से पहले<br /><em>कुछ पूछना है?</em></>, faqs: [
      ["क्या TrustShield संदेश सुरक्षित होने की गारंटी देता है?", "नहीं। यह जोखिम के संकेत दिखाता है। कम जोखिम का नतीजा सुरक्षा का प्रमाण नहीं है।"],
      ["क्या Android ऐप हर जाँच सर्वर पर भेजता है?", "मुख्य समर्थित टेक्स्ट और QR जाँच डिवाइस पर होती है। वैकल्पिक speech recognition सेटिंग पर निर्भर हो सकती है।"],
      ["वेब checker में क्या होता है?", "भेजा गया input API को जाता है। लॉगिन history scan प्रकार और नतीजे का metadata रख सकती है। संवेदनशील जानकारी न भेजें।"],
      ["क्या Call Shield फ़ोन कॉल के दूसरे व्यक्ति को सुन सकता है?", "नहीं। फ़ीचर शुरू करने पर यह केवल माइक तक पहुँची आवाज़ की समीक्षा करता है।"],
      ["क्या Android ऐप अभी डाउनलोड कर सकते हैं?", "सार्वजनिक APK अभी उपलब्ध नहीं है। सत्यापित APK मिलने पर विवरण download पेज पर दिखेंगे।"]
    ],
    downloadEyebrow: "Android ऐप", downloadTitle: <>आपकी अगली जाँच,<br /><em>बस एक टैप दूर।</em></>, downloadCopy: "इंस्टॉल करने के चरण और release विवरण देखें। सत्यापित APK प्रकाशित होने पर ही डाउनलोड मिलेगा।",
    footer: "दूसरी राय, गारंटी नहीं।", privacyLink: "गोपनीयता सूचना", downloadLink: "डाउनलोड विवरण", backTop: "ऊपर जाएँ", dark: "डार्क", light: "लाइट"
  }
} as const;

const teamInitials = ["SS", "SA", "VP", "VS", "SJ"];

function Reveal({ children, className = "" }: { children: React.ReactNode; className?: string }) {
  return <div className={`reveal ${className}`}>{children}</div>;
}

export default function Home() {
  const [language, setLanguage] = useState<Language>("en");
  const [dark, setDark] = useState(false);
  const t = copy[language];
  useEffect(() => {
    const nodes = document.querySelectorAll(".reveal");
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      nodes.forEach((node) => node.classList.add("in-view"));
      return;
    }
    const observer = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add("in-view");
          observer.unobserve(entry.target);
        }
      });
    }, { threshold: 0.12 });
    document.documentElement.classList.add("reveal-ready");
    nodes.forEach((node) => observer.observe(node));
    return () => {
      observer.disconnect();
      document.documentElement.classList.remove("reveal-ready");
    };
  }, []);
  useEffect(() => {
    document.documentElement.lang = language === "hi" ? "hi" : "en";
  }, [language]);
  const chooseLanguage = (next: Language) => {
    setLanguage(next);
  };

  return (
    <main className={dark ? "site dark" : "site"} id="top">
      <header className="nav wrap">
        <Link className="brand" href="#top" aria-label="TrustShield AI home"><Image src="/logo.png" alt="TrustShield AI" width={176} height={45} priority /></Link>
        <nav aria-label="Main navigation">{t.nav.map((label, i) => <a key={label} href={["#how", "#features", "#pricing", "#faq"][i]}>{label}</a>)}</nav>
        <div className="nav-actions">
          <label className="language-toggle"><span className="sr-only">{t.language}</span><select value={language} onChange={(event) => chooseLanguage(event.target.value as Language)} aria-label={t.language}><option value="en">EN</option><option value="hinglish">Hinglish</option><option value="hi">हि</option></select></label>
          <button className="theme-toggle" onClick={() => setDark(!dark)} aria-label={`${t.theme}: ${dark ? t.light : t.dark}`}>{dark ? "☼" : "◐"}<span>{dark ? t.light : t.dark}</span></button>
        </div>
      </header>

      <section className="hero wrap">
        <Reveal className="hero-copy">
          <p className="eyebrow"><span className="status-dot" />{t.eyebrow}</p>
          <h1>{t.title}</h1>
          <p className="lede">{t.intro}</p>
          <div className="hero-actions"><a className="button primary" href="https://trustshield-ai-ecru.vercel.app/login">{t.try}<span aria-hidden="true">↗</span></a><Link className="button secondary" href="/download">{t.download}<span aria-hidden="true">↓</span></Link></div>
          <p className="microcopy">{t.limited}</p>
          <div className="proof-row">{t.proof.map((label, i) => <span key={label}><b>0{i + 1}</b>{label}</span>)}</div>
        </Reveal>
        <div className="hero-art">
          <div className="hero-glow" />
          <ShieldHero />
          <div className="hero-chip chip-top"><span className="chip-icon">↗</span><span>secure-bank.help<small>{language === "hi" ? "लिंक नहीं खोला गया" : language === "hinglish" ? "link open nahi hua" : "Link not opened"}</small></span></div>
          <div className="hero-chip chip-bottom"><span className="chip-icon warning">!</span><span>{t.signal}<small>{language === "hi" ? "ठीक उद्धरण के साथ" : language === "hinglish" ? "exact quote ke saath" : "With an exact quote"}</small></span></div>
        </div>
      </section>

      <section className="ticker" aria-label="TrustShield principles"><div className="wrap ticker-inner">{t.proof.map((label, i) => <span key={label}><i>✳</i> 0{i + 1} &nbsp; {label}</span>)}</div></section>

      <section className="section wrap" id="features">
        <Reveal className="section-heading"><p className="eyebrow">{t.featureEyebrow}</p><h2>{t.featureTitle}</h2><p>{t.featureIntro}</p></Reveal>
        <div className="feature-grid">{t.features.map((item, i) => <Reveal className={`feature-card feature-${i + 1}`} key={item[0]}><span className="feature-index">{item[0]}</span><div className="feature-symbol">{["⌕", "◉", "▦", "⌂"][i]}</div><h3>{item[1]}</h3><p>{item[2]}</p>{i === 0 && <div className="quote-demo"><span>{t.evidence}</span><p>{t.quote}</p><b>↳ &nbsp;{t.signal}</b></div>}</Reveal>)}</div>
      </section>

      <section className="how-section" id="how"><div className="wrap how-wrap">
        <Reveal className="section-heading"><p className="eyebrow">{t.howEyebrow}</p><h2>{t.howTitle}</h2><p>{t.howIntro}</p><div className="risk-demo"><div><span>{t.risk}</span><strong>{t.high}</strong></div><div className="risk-bars"><i /><i /><i /><i /><i /><i /></div><small>{t.signal}</small></div></Reveal>
        <div className="steps">{t.steps.map(([number, title, desc]) => <Reveal className="step" key={number}><span className="step-number">{number}</span><div><h3>{title}</h3><p>{desc}</p></div><span className="step-rule" /></Reveal>)}</div>
      </div></section>

      <section className="privacy-section wrap" id="privacy"><Reveal className="privacy-panel"><div className="privacy-stamp"><Image src="/logo-icon.png" alt="" width={54} height={54} /><span>LOCAL FIRST</span></div><div><p className="eyebrow">{t.privacyEyebrow}</p><h2>{t.privacyTitle}</h2><p>{t.privacyCopy}</p><Link href="/download" className="text-link">{t.downloadLink} <span>↗</span></Link></div></Reveal></section>

      <section className="plans-section" id="pricing"><div className="wrap"><Reveal className="section-heading"><p className="eyebrow">{t.plansEyebrow}</p><h2>{t.plansTitle}</h2><p>{t.plansIntro}</p></Reveal><div className="plans">{t.plans.map(([status, name, price, desc], i) => <Reveal className={`plan-card ${i === 0 ? "plan-current" : ""}`} key={name}><span className="plan-status">{status}</span><h3>{name}</h3><p className="plan-price">{price}</p><p className="plan-description">{desc}</p>{i === 0 && <a href="https://trustshield-ai-ecru.vercel.app/login" className="text-link">{t.try} ↗</a>}</Reveal>)}</div></div></section>

      <section className="roadmap-section"><div className="wrap roadmap-wrap"><Reveal className="section-heading"><p className="eyebrow">{t.roadmapEyebrow}</p><h2>{t.roadmapTitle}</h2><p>{t.roadmapCopy}</p></Reveal><Reveal className="roadmap-card"><span className="roadmap-label">PLANNED · NO DATE SET</span><div className="roadmap-icon">⌘</div><h3>{t.roadmapItem}</h3><p>{t.roadmapDesc}</p></Reveal></div></section>

      <section className="team-section wrap"><Reveal className="section-heading"><p className="eyebrow">{t.teamEyebrow}</p><h2>{t.teamTitle}</h2></Reveal><div className="team-grid">{t.team.map(([name, role], i) => <Reveal className="team-card" key={name}><span className={`avatar avatar-${i + 1}`}>{teamInitials[i]}</span><h3>{name}</h3><p>{role}</p></Reveal>)}</div></section>

      <section className="faq-section wrap" id="faq"><Reveal className="section-heading"><p className="eyebrow">{t.faqEyebrow}</p><h2>{t.faqTitle}</h2></Reveal><div className="faq-list">{t.faqs.map(([question, answer]) => <details className="faq-item" key={question}><summary>{question}<span aria-hidden="true">+</span></summary><p>{answer}</p></details>)}</div></section>

      <section className="download-band"><div className="wrap download-wrap"><div><p className="eyebrow">{t.downloadEyebrow}</p><h2>{t.downloadTitle}</h2><p>{t.downloadCopy}</p></div><Link className="button download-button" href="/download">{t.download}<span aria-hidden="true">↗</span></Link><span className="download-orbit" /></div></section>

      <footer className="footer"><div className="wrap footer-top"><Link className="footer-brand" href="#top"><Image src="/logo.png" alt="TrustShield AI" width={164} height={42} /></Link><p>{t.footer}</p><nav aria-label="Footer"><Link href="/download">{t.downloadLink}</Link><a href="#privacy">{t.privacyLink}</a><a href="#top">{t.backTop} ↑</a></nav></div><div className="wrap footer-bottom"><span>© 2026 TrustShield AI</span><span>{t.limited}</span></div></footer>
    </main>
  );
}

