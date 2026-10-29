"use client";

import Image from "next/image";
import { useEffect, useRef } from "react";

export default function ShieldHero() {
  const visual = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
    if (reduceMotion.matches) return;
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
    visual.current.style.setProperty("--tilt-x", `${x * 12}deg`);
    visual.current.style.setProperty("--tilt-y", `${-y * 9}deg`);
  }

  function resetPointer() {
    visual.current?.style.setProperty("--tilt-x", "0deg");
    visual.current?.style.setProperty("--tilt-y", "0deg");
  }

  return <div ref={visual} className="shield-visual" onPointerMove={handlePointerMove} onPointerLeave={resetPointer}>
    <Image className="shield-fallback" src="/shield-static.svg" alt="" width={360} height={400} priority />
    <div className="css-bubble bubble-one" aria-hidden="true">OTP?</div>
    <div className="css-bubble bubble-two" aria-hidden="true">PAY</div>
  </div>;
}
