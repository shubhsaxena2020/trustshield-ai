"""Builds the TrustShield AI presentation (15 slides, speaker notes on every slide). Run: python docs/presentation/build_deck.py"""
from pathlib import Path

from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.util import Inches, Pt

OUT = Path(__file__).with_name("TrustShield_AI_PSIT_TechExpo_2026.pptx")
BG = RGBColor(0x0B, 0x10, 0x1A)
PANEL = RGBColor(0x14, 0x1C, 0x2B)
TEAL = RGBColor(0x2D, 0xD4, 0xBF)
AMBER = RGBColor(0xF5, 0xB8, 0x4B)
WHITE = RGBColor(0xF4, 0xF6, 0xFA)
MUTED = RGBColor(0x9A, 0xA6, 0xBA)
RED = RGBColor(0xF0, 0x6A, 0x6A)

prs = Presentation()
prs.slide_width, prs.slide_height = Inches(13.333), Inches(7.5)
BLANK = prs.slide_layouts[6]


def base(slide_no: int, notes: str):
    s = prs.slides.add_slide(BLANK)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = BG
    bar = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, 0, Inches(0.12), prs.slide_height)
    bar.fill.solid()
    bar.fill.fore_color.rgb = TEAL
    bar.line.fill.background()
    foot = s.shapes.add_textbox(Inches(0.6), Inches(6.9), Inches(12), Inches(0.4))
    p = foot.text_frame.paragraphs[0]
    p.text = f"TrustShield AI  ·  Team Scam Scan  ·  {slide_no:02d} / 15"
    p.font.size, p.font.color.rgb = Pt(11), MUTED
    s.notes_slide.notes_text_frame.text = notes
    return s


def title(s, text, kicker=None):
    if kicker:
        k = s.shapes.add_textbox(Inches(0.6), Inches(0.45), Inches(12), Inches(0.4))
        kp = k.text_frame.paragraphs[0]
        kp.text, kp.font.size, kp.font.color.rgb = kicker.upper(), Pt(13), TEAL
    t = s.shapes.add_textbox(Inches(0.6), Inches(0.85), Inches(12.2), Inches(1.2))
    t.text_frame.word_wrap = True
    p = t.text_frame.paragraphs[0]
    p.text, p.font.size, p.font.bold, p.font.color.rgb = text, Pt(34), True, WHITE


def bullets(s, items, left=0.6, top=2.2, width=12.0, size=20, color=WHITE):
    box = s.shapes.add_textbox(Inches(left), Inches(top), Inches(width), Inches(4.5))
    tf = box.text_frame
    tf.word_wrap = True
    for i, item in enumerate(items):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.text = f"•  {item}"
        p.font.size, p.font.color.rgb = Pt(size), color
        p.space_after = Pt(12)


def card(s, x, y, w, h, head, body, accent=TEAL):
    shape = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(x), Inches(y), Inches(w), Inches(h))
    shape.fill.solid()
    shape.fill.fore_color.rgb = PANEL
    shape.line.color.rgb = accent
    shape.line.width = Pt(1.5)
    tf = shape.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = MSO_ANCHOR.TOP
    tf.margin_left = tf.margin_right = Inches(0.25)
    tf.margin_top = Inches(0.2)
    p = tf.paragraphs[0]
    p.text, p.font.size, p.font.bold, p.font.color.rgb = head, Pt(20), True, accent
    q = tf.add_paragraph()
    q.text, q.font.size, q.font.color.rgb = body, Pt(15), WHITE
    q.space_before = Pt(8)


def big_stat(s, x, y, number, label, color=TEAL):
    n = s.shapes.add_textbox(Inches(x), Inches(y), Inches(3.8), Inches(1.1))
    p = n.text_frame.paragraphs[0]
    p.text, p.font.size, p.font.bold, p.font.color.rgb = number, Pt(54), True, color
    l = s.shapes.add_textbox(Inches(x), Inches(y + 1.1), Inches(3.8), Inches(1.0))
    l.text_frame.word_wrap = True
    q = l.text_frame.paragraphs[0]
    q.text, q.font.size, q.font.color.rgb = label, Pt(16), MUTED


# 1 ── Title
s = base(1, "Open with the problem in one line: scams now reach people through the apps they use every day, and most people can't "
            "spot them in a few seconds. Introduce the team (Scam Scan) and the event (PSIT Tech Expo 2026). Tagline: Think Before You Trust.")
t = s.shapes.add_textbox(Inches(0.9), Inches(2.0), Inches(11.5), Inches(1.6))
t.text_frame.word_wrap = True
p = t.text_frame.paragraphs[0]
p.text, p.font.size, p.font.bold, p.font.color.rgb = "TrustShield AI", Pt(66), True, WHITE
p2 = t.text_frame.add_paragraph()
p2.text, p2.font.size, p2.font.color.rgb = "Think Before You Trust.", Pt(28), TEAL
sub = s.shapes.add_textbox(Inches(0.9), Inches(4.4), Inches(11.5), Inches(1.4))
sub.text_frame.word_wrap = True
for i, line in enumerate(["A privacy-first analyser for scam messages, screenshots, links and QR codes",
                          "Team Scam Scan  ·  PSIT Tech Expo 2026  ·  Artificial Intelligence & Machine Learning, Cybersecurity Innovations, Edge AI"]):
    q = sub.text_frame.paragraphs[0] if i == 0 else sub.text_frame.add_paragraph()
    q.text, q.font.size, q.font.color.rgb = line, Pt(20 if i == 0 else 15), WHITE if i == 0 else MUTED

# 2 ── Problem
s = base(2, "Use the figures as reported, and name the source when asked. Reports of 2025 differ: one says over 24 lakh complaints and Rs 22,495 "
            "crore; another cites I4C data of about 21.8 lakh complaints and Rs 19,813 crore. The 2021 figure is 2.63 lakh. Say the point clearly: "
            "the problem is not a lack of warnings, it is that people must spot the signs in seconds.")
title(s, "Scams are growing faster than awareness", "The problem")
big_stat(s, 0.6, 2.2, "2.63 L", "financial cyber-fraud complaints in 2021")
big_stat(s, 4.7, 2.2, "24 L+", "complaints in 2025 (government data, PIB/NCRP)", RED)
big_stat(s, 8.8, 2.2, "Rs 22,495 Cr", "reported in 2025", AMBER)
bullets(s, ["Scams arrive where people already are: WhatsApp, SMS, email, screenshots, QR codes and shortened links.",
            "Most people cannot judge a message in a few seconds, on a small screen, while busy."], top=4.6, size=18)

# 3 ── Idea
s = base(3, "Our idea in one sentence: before you click, pay or share, check it. Show the four ways in: paste text, upload a screenshot, paste a "
            "link, or upload a QR code. Stress that every answer comes with the evidence behind it.")
title(s, "Check before you click, pay or share", "The idea")
for i, (h, b) in enumerate([("Paste", "Text from WhatsApp, SMS or email"), ("Screenshot", "Upload a screenshot; text is read locally"),
                            ("Link", "Paste a URL; its structure is analysed"), ("QR code", "Upload a QR; the destination is read, never opened")]):
    card(s, 0.6 + i * 3.1, 2.3, 2.85, 2.6, h, b)
bullets(s, ["Every result shows the evidence, not just a score."], top=5.4, size=18)

# 4 ── Users
s = base(4, "Who it is for: people who are not technical. The design choice is plain language, short answers, and a clear next step.")
title(s, "Built for people who are not technical", "Who it is for")
bullets(s, ["Students, parents and older adults who receive suspicious messages every week.",
            "Anyone who has been asked for an OTP, a payment or a 'verification' link.",
            "Plain language, short answers, and one clear safe action at the end of every result."], size=22)

# 5 ── How it works
s = base(5, "Walk through the six steps left to right. The key point for judges: extraction and rules are deterministic and run locally. "
            "The machine-learning classifier adds a calibrated score, and the verdict comes only from the rules and classifier.")
title(s, "How it works, step by step", "Pipeline")
steps = ["Input\ntext · screenshot · link · QR", "Local extraction\nOCR (Tesseract) · QR decoder",
         "Deterministic rules\nlinks · lookalikes · tunnels · OTP · domain check", "ML classifier\ntrustshield_text_v3 + confidence",
         "Risk verdict\nrules + classifier only", "Safe action\nverify · never share OTP · 1930 / NCRP"]
for i, txt in enumerate(steps):
    x = 0.6 + (i % 3) * 4.1
    y = 2.3 + (i // 3) * 2.2
    card(s, x, y, 3.7, 1.9, f"{i + 1}", txt, accent=AMBER if i == 4 else TEAL)

# 6 ── Architecture
s = base(6, "Explain the key design decision: the language model is the chat assistant, and the scanner is a tool it can call. The scanner's "
            "score is authoritative. The model can explain it but can never change it, so the verdict stays evidence-based.")
title(s, "The model talks; the scanner decides", "Architecture")
card(s, 0.6, 2.3, 3.9, 2.4, "Chat assistant", "A language model answers questions and explains results in plain words.")
card(s, 4.7, 2.3, 3.9, 2.4, "Scanner (tool)", "Rules plus trustshield_text_v3. Runs only on content the user shared.", AMBER)
card(s, 8.8, 2.3, 3.9, 2.4, "Guardrails", "Score must match the scanner; no invented facts; no certainty claims.", RED)
bullets(s, ["Questions and requests never reach the scanner.", "Pasted content, links and screenshots do."], top=5.1, size=18)

# 7 ── Privacy
s = base(7, "Privacy is a feature: text is extracted and QR codes are decoded on the device or server, without opening links. TrustShield "
            "keeps no permanent history; the conversation lives in the browser tab. Be honest about the limit: the model call is sent to the model host.")
title(s, "Privacy first by design", "Privacy")
bullets(s, ["Screenshots: text is read locally with Tesseract OCR.",
            "QR codes: the destination is decoded but never opened.",
            "Links: analysed from their structure only; the page is never requested.",
            "Online domain check: only the registered domain (never the message or the full link) is sent to Google Safe Browsing and a public certificate log.",
            "No permanent history on our side; chats stay in your browser.",
            "Honest limit: message text is sent to the language-model host to produce the chat reply."], size=20)

# 8 ── Detection
s = base(8, "Explain the layers: deterministic rules catch known patterns (OTP and KYC requests, lookalike domains, IP-address links). The "
            "classifier adds a calibrated confidence. The rules and classifier decide; the output is risk evidence, never a guarantee.")
title(s, "What the scanner looks for", "Detection")
card(s, 0.6, 2.3, 5.9, 3.9, "Deterministic rules",
     "Link structure, lookalike and shortened domains, temporary tunnel links (trycloudflare, ngrok), IP-address URLs, urgency, "
     "impersonation, payment and OTP requests, and location or camera lures.")
card(s, 0.6, 6.4, 5.9, 0.9, "Online domain check", "Google Safe Browsing listing and public certificate history, for the domain only.", TEAL)
card(s, 6.8, 2.3, 5.9, 3.9, "Machine-learning classifier",
     "trustshield_text_v3, threshold 0.16, with a calibrated confidence score. Promoted after a one-time sealed-holdout test.", AMBER)

# 9 ── Demo
s = base(9, "Live demo script (about 3 minutes): (1) ask 'what is phishing?' and show a clear answer with no scan; (2) paste the SBI example "
            "with the OTP link and show the high-risk card with evidence; (3) upload a screenshot or ask 'what should I do if I shared my OTP?'. "
            "Have a backup recording ready in case the network fails.")
title(s, "Live demo: three things to try", "Demo")
bullets(s, ["Ask 'what is phishing?'  →  a plain answer, no scanner.",
            "Paste: 'URGENT your SBI account is blocked, share OTP at http://sbi-kyc-update.xyz/login'  →  high-risk card with the evidence.",
            "Ask 'what should I do if I already shared my OTP?'  →  clear safe steps and the 1930 helpline."], size=20)

# 10 ── Testing
s = base(10, "Testing evidence: 765 automated unit and integration tests pass. A routing audit of 180 messages gave 96.1% correct, with 0 of 79 "
             "scams missed and 7 false alarms. A 24-request stream stress test: all succeeded, median first response about 0.37 s, 95th percentile about 7.1 s.")
title(s, "Tested, and measured", "Quality")
big_stat(s, 0.6, 2.2, "765", "automated tests passing (unit + integration)")
big_stat(s, 4.7, 2.2, "0 / 79", "scam messages missed in the routing audit")
big_stat(s, 8.8, 2.2, "24 / 24", "stress-test requests succeeded (p50 0.37 s first reply)", AMBER)
bullets(s, ["Routing audit: 180 messages, 96.1% correct, 7 false alarms.", "Stress test p95 first reply: about 7.1 s."], top=5.0, size=18)

# 11 ── Honest results
s = base(11, "Be upfront: the sealed-holdout results are recall 82.1% and specificity 78.3%. The 90% targets were not met. These are one-time "
             "measurements, and real-world performance varies by source. Say this before a judge asks.")
title(s, "Honest results: close, not finished", "Accuracy")
big_stat(s, 0.6, 2.2, "82.1%", "recall on the sealed holdout (one-time test)")
big_stat(s, 4.7, 2.2, "78.3%", "specificity on the sealed holdout", AMBER)
big_stat(s, 8.8, 2.2, "90%", "our target, not yet met", RED)
bullets(s, ["We report the miss rather than hide it; it is the first item on the roadmap.",
            "Results vary by source and domain; treat every score as evidence, not a verdict."], top=5.0, size=18)

# 12 ── Guardrails
s = base(12, "Safety of the assistant itself: it never says a message is 100% safe or definitely fraud, it never claims to have opened a link, "
             "and it checks its own replies against the scanner score. Mention that a grounding check sometimes replaces a bad reply with a neutral answer.")
title(s, "Guardrails that protect users", "Safety")
bullets(s, ["Never says 'guaranteed safe' or 'definitely fraud'.",
            "Never claims to have opened a link, and never invents a domain-check result: those come from the scanner.",
            "A reply that quotes a different score from the scanner is rejected.",
            "Never asks for a password, OTP, PIN or card number.",
            "Scope: answers scam and cybersecurity questions; declines creative or coding tasks."], size=20)

# 13 ── Challenges
s = base(13, "Show the engineering story: early the assistant sent questions to the scanner; we moved every message through the model first, and the "
             "scanner now runs only on shared content. Stalls were fixed with timeouts and retries, and switching chats no longer cancels a reply.")
title(s, "What we broke, and how we fixed it", "Engineering")
bullets(s, ["Questions were scanned as content → the model now answers first; the scanner runs only on shared content.",
            "Long model stalls showed errors → a silence timeout and retries replace them.",
            "Switching chats cancelled replies → each conversation keeps its own stream.",
            "Scam wording was sometimes refused as 'claims' → the grounding check now recognises refusals and scam descriptions."], size=19)

# 14 ── Roadmap
s = base(14, "Roadmap, in order of value: sign-in with history across devices, Hindi OCR, more reputation sources, and a larger validation set with a fresh "
              "sealed holdout. Be clear that items marked planned are not active in the current build.")
title(s, "Where we go next", "Roadmap")
card(s, 0.6, 2.3, 3.9, 3.6, "Accounts", "Sign-in and history across devices (planned).")
card(s, 4.7, 2.3, 3.9, 3.6, "Languages", "Hindi and more OCR via a second provider (planned).", AMBER)
card(s, 8.8, 2.3, 3.9, 3.6, "Accuracy", "Bigger dataset and a fresh sealed holdout to beat 90% recall.", RED)

# 15 ── Team
s = base(15, "Close by thanking the judges and naming each team member. Repeat the tagline: Think Before You Trust.")
title(s, "Team Scam Scan", "Thank you")
bullets(s, ["Team leader: SHUBH SAXENA",
            "Members: SUGANDHI AGARWAL · STUTTI JHA · SHRUTI SHUKLA · VAISHNAVI MISHRA"], size=22)
t = s.shapes.add_textbox(Inches(0.6), Inches(5.2), Inches(12), Inches(1.0))
p = t.text_frame.paragraphs[0]
p.text, p.font.size, p.font.bold, p.font.color.rgb = "Think Before You Trust.", Pt(32), True, TEAL

prs.save(OUT)
print("saved", OUT)
