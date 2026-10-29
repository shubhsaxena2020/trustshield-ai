"use client";

import { useRef } from "react";
import { LABELS, SEV_BADGE, pretty } from "@/components/utils";
import { gsap, useGSAP, prefersReducedMotion } from "@/lib/gsap";
import { Icon } from "./icons";
import type { ChatAnalysis } from "./types";

// Risk (how suspicious) and confidence (how much evidence supports the assessment) are shown separately on purpose.
const CONFIDENCE_TEXT = { high: "High", medium: "Medium", low: "Low" } as const;
const R = 30;
const CIRC = 2 * Math.PI * R;

function Section({ title, children, defaultOpen = false }: { title: string; children: React.ReactNode; defaultOpen?: boolean }) {
  return (
    <details open={defaultOpen} className="group/sec mt-2 rounded-xl border border-ts-border bg-ts-bg/40 [&[open]]:bg-ts-bg/60">
      <summary className="flex min-h-11 cursor-pointer list-none items-center justify-between gap-2 rounded-xl px-3.5 py-2.5 text-sm font-medium transition-colors hover:bg-ts-hover focus-visible:outline-2 [&::-webkit-details-marker]:hidden">
        {title}
        <Icon name="down" size={16} className="text-ts-muted transition-transform duration-300 group-open/sec:rotate-180" />
      </summary>
      {children}
    </details>
  );
}

export function RiskCard({ analysis }: { analysis: ChatAnalysis }) {
  const label = LABELS[analysis.risk_level] ?? LABELS.unable_to_verify;
  const used = analysis.capabilities.filter((c) => c.used);
  const unused = analysis.capabilities.filter((c) => !c.used);
  const root = useRef<HTMLElement>(null);
  const score = Math.min(100, Math.max(0, analysis.risk_score));

  // Gauge sweeps to the score and the number counts up with it; sections below cascade in.
  useGSAP(
    () => {
      const arc = root.current?.querySelector<SVGCircleElement>("[data-arc]");
      const num = root.current?.querySelector<HTMLElement>("[data-num]");
      const reduce = prefersReducedMotion();
      if (arc) {
        const to = CIRC * (1 - score / 100);
        if (reduce) arc.style.strokeDashoffset = String(to);
        else gsap.fromTo(arc, { strokeDashoffset: CIRC }, { strokeDashoffset: to, duration: 1.5, ease: "expo.out", delay: 0.15 });
      }
      if (num) {
        if (reduce) num.textContent = String(score);
        else {
          const counter = { v: 0 };
          gsap.to(counter, { v: score, duration: 1.5, ease: "expo.out", delay: 0.15, onUpdate: () => { num.textContent = String(Math.round(counter.v)); } });
        }
      }
      if (!reduce) gsap.from("[data-rise]", { opacity: 0, y: 14, duration: 0.7, stagger: 0.07, ease: "power3.out", delay: 0.25, clearProps: "all" });
    },
    { scope: root, dependencies: [analysis.analysis_id, score] },
  );

  return (
    <section ref={root} aria-label="Risk assessment" className={`ts-card ts-level-${analysis.risk_level} mt-3 p-4 min-[801px]:p-5`}>
      <div className="flex flex-wrap items-center gap-x-5 gap-y-4">
        {analysis.risk_level !== "unable_to_verify" && (
          <div className="relative h-[76px] w-[76px] shrink-0" role="img" aria-label={`Risk score ${analysis.risk_score} out of 100`}>
            <svg viewBox="0 0 76 76" className="h-full w-full -rotate-90">
              <circle cx="38" cy="38" r={R} fill="none" stroke="var(--ts-border)" strokeWidth="6" />
              <circle data-arc cx="38" cy="38" r={R} fill="none" stroke="var(--lv)" strokeWidth="6" strokeLinecap="round" strokeDasharray={CIRC} strokeDashoffset={CIRC} />
            </svg>
            <div className="absolute inset-0 grid place-items-center">
              <div className="text-center leading-none">
                <span data-num className="text-[22px] font-semibold tabular-nums">0</span>
                <span className="block pt-0.5 text-[10px] text-ts-muted">/100</span>
              </div>
            </div>
          </div>
        )}
        <div className="min-w-0 flex-1">
          <span data-rise className="ts-verdict">
            <span aria-hidden="true" className="grid h-5 w-5 place-items-center rounded-full bg-[var(--lv)] text-[11px] font-bold leading-none text-white dark:text-black">
              {analysis.risk_level === "low_risk" ? "✓" : label.icon}
            </span>
            {label.text}
          </span>
          <dl data-rise className="mt-2.5 flex flex-wrap gap-x-6 gap-y-1 text-sm">
            <div className="flex gap-1.5">
              <dt className="text-ts-muted">Risk score</dt>
              <dd className="font-semibold">{analysis.risk_level === "unable_to_verify" ? "not available" : `${analysis.risk_score}/100`}</dd>
            </div>
            <div className="flex gap-1.5">
              <dt className="text-ts-muted">Confidence</dt>
              <dd className="font-semibold">{CONFIDENCE_TEXT[analysis.confidence]}</dd>
            </div>
          </dl>
        </div>
      </div>

      {analysis.qr_payload && (
        <p data-rise className="mt-4 break-all text-sm">
          <span className="font-medium">QR code contains (not opened): </span>
          <code className="ts-mono rounded-md bg-ts-hover px-1.5 py-0.5 text-[13px]">{analysis.qr_payload}</code>
        </p>
      )}

      {analysis.safe_actions.length > 0 && (
        <div data-rise className="mt-4 rounded-2xl bg-[rgba(var(--lv-rgb),0.08)] p-3.5 text-sm">
          <p className="font-semibold">Safer next steps</p>
          <ul className="mt-2 space-y-1.5">
            {analysis.safe_actions.map((a, i) => (
              <li key={i} className="flex gap-2.5">
                <Icon name="check" size={16} className="mt-0.5 shrink-0 text-emerald-600 dark:text-emerald-400" strokeWidth={2.4} />
                <span>{a}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {analysis.evidence.length > 0 && (
        <div data-rise>
          <Section title={`Evidence (${analysis.evidence.length})`}>
            <ul className="divide-y divide-ts-border">
              {analysis.evidence.map((e) => (
                <li key={e.id} className="px-3.5 py-3 text-sm">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className={`rounded-md px-1.5 py-0.5 text-xs font-semibold ${SEV_BADGE[e.severity] ?? SEV_BADGE.informational}`}>
                      <span className="sr-only">Severity: </span>
                      {pretty(e.severity)}
                    </span>
                    <span className="font-medium">{e.title}</span>
                    <span className="text-xs text-ts-muted">{pretty(e.source_module)}</span>
                  </div>
                  <p className="mt-1.5 text-ts-muted">{e.explanation}</p>
                  {e.raw_signal && Object.keys(e.raw_signal).length > 0 && (
                    <code className="ts-mono mt-1.5 block break-all text-xs text-ts-muted">
                      {Object.entries(e.raw_signal).map(([k, v]) => `${k}: ${String(v)}`).join(" · ")}
                    </code>
                  )}
                </li>
              ))}
            </ul>
          </Section>
        </div>
      )}

      {analysis.extracted_content && (
        <div data-rise>
          <Section title="Extracted text (untrusted, shown as data)">
            <pre className="ts-mono max-h-48 overflow-auto whitespace-pre-wrap break-words px-3.5 pb-3.5 text-xs">{analysis.extracted_content}</pre>
          </Section>
        </div>
      )}

      <div data-rise>
        <Section title="Analysis coverage">
          <div className="grid gap-3 px-3.5 pb-3.5 text-sm sm:grid-cols-2">
            <div>
              <p className="font-medium">Used</p>
              <ul className="mt-1 list-disc pl-5 text-ts-muted">{used.map((c) => <li key={c.name}>{c.description}</li>)}</ul>
            </div>
            <div>
              <p className="font-medium">Not used</p>
              <ul className="mt-1 list-disc pl-5 text-ts-muted">{unused.map((c) => <li key={c.name}>{c.description} — {c.reason}</li>)}</ul>
            </div>
          </div>
        </Section>
      </div>

      {(analysis.uncertainty.length > 0 || analysis.warnings.length > 0) && (
        <ul data-rise className="mt-3 list-disc pl-5 text-sm text-ts-muted">
          {[...analysis.warnings, ...analysis.uncertainty].map((u, i) => <li key={i}>{u}</li>)}
        </ul>
      )}
    </section>
  );
}
