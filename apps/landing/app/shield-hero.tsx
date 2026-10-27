"use client";

import { useEffect, useState } from "react";

export default function ShieldHero() {
  const [Scene, setScene] = useState<React.ComponentType | null>(null);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
      const canvas = document.createElement("canvas");
      const gl = canvas.getContext("webgl2") || canvas.getContext("webgl");
      if (!gl) return;
      gl.getExtension("WEBGL_lose_context")?.loseContext();
      import("./shield-scene").then((module) => setScene(() => module.default)).catch(() => undefined);
    }, 5000);
    return () => window.clearTimeout(timer);
  }, []);

  return <div className="shield-visual" aria-label="A shield blocks suspicious message signals">
    <div className="css-shield" aria-hidden="true"><span>✓</span></div>
    <div className="css-bubble bubble-one" aria-hidden="true">OTP?</div>
    <div className="css-bubble bubble-two" aria-hidden="true">PAY</div>
    {Scene && <Scene />}
  </div>;
}
