"use client";

import { useRef } from "react";
import { gsap, useGSAP, prefersReducedMotion } from "@/lib/gsap";
import { Icon } from "./icons";
import { ShieldOrb } from "./ShieldOrb";

interface EmptyStateProps {
  starters: string[];
  onPick: (starter: string) => void;
  /** True once the first message is sent: plays the exit animation, then calls onExited. */
  leaving: boolean;
  onExited: () => void;
}

const STARTER_ICONS = ["chat", "upi", "phone", "qr"];
const WORDS = ["message", "link", "QR code", "payment request", "phone call"];

function splitWords(text: string) {
  return text.split(" ").map((w, i) => (
    <span key={i} className="inline-block overflow-hidden pb-[0.14em] align-bottom">
      <span data-word className="inline-block will-change-transform">{w}&nbsp;</span>
    </span>
  ));
}

function spotlight(e: React.PointerEvent<HTMLElement>) {
  const r = e.currentTarget.getBoundingClientRect();
  e.currentTarget.style.setProperty("--mx", `${e.clientX - r.left}px`);
  e.currentTarget.style.setProperty("--my", `${e.clientY - r.top}px`);
}

export function EmptyState({ starters, onPick, leaving, onExited }: EmptyStateProps) {
  const root = useRef<HTMLDivElement>(null);
  const slot = useRef<HTMLSpanElement>(null);
  const strip = useRef<HTMLSpanElement>(null);

  // Intro: orb blooms, headline words rise, hint fades in, starter chips cascade.
  useGSAP(
    () => {
      if (prefersReducedMotion()) return;
      const tl = gsap.timeline({ defaults: { ease: "power4.out" } });
      tl.from("[data-orb]", { scale: 0.55, opacity: 0, duration: 1.2, ease: "expo.out" })
        .from("[data-word]", { yPercent: 115, duration: 0.95, stagger: 0.07 }, "-=0.8")
        .from("[data-sub]", { opacity: 0, y: 12, duration: 0.8, stagger: 0.1 }, "-=0.65")
        .from("[data-chip]", { opacity: 0, y: 26, scale: 0.97, duration: 0.85, stagger: 0.08 }, "-=0.55");
    },
    { scope: root },
  );

  // The rotating word: a vertical strip that steps up one row at a time and resizes its slot to the next word.
  useGSAP(
    () => {
      const el = strip.current;
      const box = slot.current;
      if (!el || !box || prefersReducedMotion()) return;
      const rows = Array.from(el.children) as HTMLElement[];
      const widths = rows.map((r) => r.offsetWidth);
      gsap.set(box, { width: widths[0] });
      const n = WORDS.length;
      const tl = gsap.timeline({ repeat: -1, delay: 2.6 });
      for (let i = 1; i <= n; i++) {
        tl.to(el, { yPercent: (-100 * i) / (n + 1), duration: 0.75, ease: "power3.inOut" }, i === 1 ? 0 : "+=1.9")
          .to(box, { width: widths[i % n], duration: 0.75, ease: "power3.inOut" }, "<");
      }
      tl.set(el, { yPercent: 0 });
    },
    { scope: root },
  );

  useGSAP(
    () => {
      if (!leaving) return;
      if (prefersReducedMotion()) {
        onExited();
        return;
      }
      gsap.to("[data-exit]", { opacity: 0, y: -18, scale: 0.98, duration: 0.45, stagger: 0.04, ease: "power3.in", onComplete: onExited });
    },
    { scope: root, dependencies: [leaving] },
  );

  return (
    <div ref={root} className="pointer-events-none absolute inset-0 z-[1]">
      <section
        aria-label="Welcome"
        className="absolute inset-x-0 flex flex-col items-center px-5 text-center"
        style={{ bottom: "calc(100% - var(--empty-top, 50%) + 22px)" }}
      >
        <div data-orb data-exit className="ts-orb pointer-events-auto mb-3 h-[88px] w-[88px] min-[801px]:h-[116px] min-[801px]:w-[116px]">
          <ShieldOrb mode="idle" className="h-full w-full" />
        </div>
        <h1 data-exit className="font-display text-[34px] leading-[1.1] tracking-[-0.01em] text-ts-text min-[801px]:text-[46px]">
          {splitWords("What should I check for you?")}
        </h1>
        <p data-sub data-exit className="mt-3 flex items-baseline justify-center gap-[0.4em] text-[17px] text-ts-muted min-[801px]:text-[19px]">
          <span>Paste a suspicious</span>
          <span ref={slot} className="relative inline-block h-[1.4em] overflow-hidden text-left align-bottom font-medium text-ts-accent">
            <span ref={strip} className="flex flex-col">
              {[...WORDS, WORDS[0]].map((w, i) => (
                <span key={i} className="block h-[1.4em] self-start whitespace-nowrap leading-[1.4em]">{w}</span>
              ))}
            </span>
          </span>
        </p>
        <p data-sub data-exit className="mt-1.5 text-[13px] text-ts-muted max-[800px]:hidden">
          I&rsquo;ll show the risk evidence &mdash; never a guarantee of safety.
        </p>
      </section>

      <div
        className="ts-chips pointer-events-auto absolute inset-x-0 mx-auto grid w-full max-w-[800px] grid-cols-1 gap-2.5 px-4 min-[801px]:grid-cols-2"
        style={{ top: "calc(var(--empty-top, 50%) + var(--comp-h, 64px) + 16px)" }}
      >
        {starters.map((starter, index) => (
          <button
            key={`${starter}-${index}`}
            data-chip
            data-exit
            type="button"
            onClick={() => onPick(starter)}
            onPointerMove={spotlight}
            className="ts-chip"
          >
            <span className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-ts-hover text-ts-accent">
              <Icon name={STARTER_ICONS[index % STARTER_ICONS.length]} size={16} />
            </span>
            <span className="line-clamp-2 min-w-0 flex-1 text-ts-text">{starter}</span>
            <Icon name="arrow" size={15} className="shrink-0 text-ts-muted" />
          </button>
        ))}
      </div>
    </div>
  );
}

export default EmptyState;
