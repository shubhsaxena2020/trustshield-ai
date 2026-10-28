"use client";

import { Component, useEffect, useRef, useState, type ReactNode } from "react";

class SceneBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() { return this.state.failed ? null : this.props.children; }
}

export default function ShieldHero() {
  const [Scene, setScene] = useState<React.ComponentType | null>(null);
  const visual = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
    const device = navigator as Navigator & { deviceMemory?: number; connection?: { saveData?: boolean } };
    const lowEnd = device.deviceMemory !== undefined && device.deviceMemory < 4;
    if (reduceMotion.matches || lowEnd || device.connection?.saveData) return;
    try {
      const probe = document.createElement("canvas");
      const context = probe.getContext("webgl2") || probe.getContext("webgl");
      if (!context) return;
      context.getExtension("WEBGL_lose_context")?.loseContext();
    } catch {
      return;
    }
    const timer = window.setTimeout(() => {
      const idleWindow = window as Window & { requestIdleCallback?: (callback: () => void, options?: { timeout: number }) => number };
      const load = () => import("./shield-scene").then((module) => setScene(() => module.default)).catch(() => undefined);
      if (idleWindow.requestIdleCallback) idleWindow.requestIdleCallback(load, { timeout: 1300 });
      else load();
    }, 1100);
    return () => window.clearTimeout(timer);
  }, []);

  useEffect(() => {
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    const update = () => {
      const art = visual.current?.closest(".hero-art") as HTMLElement | null;
      if (!art) return;
      const offset = Math.max(-14, Math.min(14, -art.getBoundingClientRect().top * 0.025));
      art.style.setProperty("--scroll-shift", `${offset}px`);
    };
    window.addEventListener("scroll", update, { passive: true });
    update();
    return () => window.removeEventListener("scroll", update);
  }, []);

  function handlePointerMove(event: React.PointerEvent<HTMLDivElement>) {
    if (event.pointerType === "touch" || window.matchMedia("(prefers-reduced-motion: reduce)").matches || !visual.current) return;
    const bounds = event.currentTarget.getBoundingClientRect();
    const x = (event.clientX - bounds.left) / bounds.width - 0.5;
    const y = (event.clientY - bounds.top) / bounds.height - 0.5;
    visual.current.style.transform = `translate3d(${x * 8}px, ${y * 6}px, 0)`;
  }

  function resetPointer() {
    if (visual.current) visual.current.style.transform = "translate3d(0,0,0)";
  }

  return <div className="shield-visual" onPointerMove={handlePointerMove} onPointerLeave={resetPointer}>
    <div className="css-shield" aria-hidden="true"><span>✓</span></div>
    <div className="css-bubble bubble-one" aria-hidden="true">OTP?</div>
    <div className="css-bubble bubble-two" aria-hidden="true">PAY</div>
    <div ref={visual} className="shield-motion" aria-hidden="true">{Scene && <SceneBoundary><Scene /></SceneBoundary>}</div>
  </div>;
}
