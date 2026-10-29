"use client";

import { useEffect, useRef } from "react";

// A shield-with-checkmark drawn as a particle cloud (the same idea as igloo's morphing particle systems, but
// 2D canvas and a few hundred points so it stays cheap). Particles spring toward their home position, get pushed
// away by the pointer, and are tinted by the current mode. Pauses when off-screen or in a hidden tab, and draws
// a single static frame when the user prefers reduced motion.

export type OrbMode = "idle" | "scan" | "low_risk" | "caution" | "suspicious" | "high_risk" | "unable_to_verify";

const TINT: Partial<Record<OrbMode, [number, number, number]>> = {
  low_risk: [22, 163, 74],
  caution: [217, 119, 6],
  suspicious: [234, 88, 12],
  high_risk: [220, 38, 38],
  unable_to_verify: [100, 116, 139],
};

type Dot = { hx: number; hy: number; x: number; y: number; vx: number; vy: number; ph: number; kind: 0 | 1 | 2; r: number };

const COUNTS = {
  hero: { outline: 300, fill: 150, check: 70 },
  small: { outline: 70, fill: 30, check: 18 },
};

function buildTargets(density: "hero" | "small"): Dot[] {
  const N = 110;
  const probe = document.createElement("canvas");
  probe.width = probe.height = N;
  const g = probe.getContext("2d", { willReadFrequently: true });
  if (!g) return [];
  const p = new Path2D();
  const s = N;
  p.moveTo(s * 0.5, s * 0.04);
  p.bezierCurveTo(s * 0.68, s * 0.16, s * 0.82, s * 0.2, s * 0.92, s * 0.2);
  p.bezierCurveTo(s * 0.92, s * 0.62, s * 0.78, s * 0.84, s * 0.5, s * 0.97);
  p.bezierCurveTo(s * 0.22, s * 0.84, s * 0.08, s * 0.62, s * 0.08, s * 0.2);
  p.bezierCurveTo(s * 0.18, s * 0.2, s * 0.32, s * 0.16, s * 0.5, s * 0.04);
  p.closePath();
  g.fill(p);
  const data = g.getImageData(0, 0, N, N).data;
  const inside = (x: number, y: number) => x >= 0 && y >= 0 && x < N && y < N && data[(y * N + x) * 4 + 3] > 128;
  const edge: [number, number][] = [];
  const fill: [number, number][] = [];
  for (let y = 0; y < N; y++) {
    for (let x = 0; x < N; x++) {
      if (!inside(x, y)) continue;
      const isEdge = !inside(x - 1, y) || !inside(x + 1, y) || !inside(x, y - 1) || !inside(x, y + 1);
      (isEdge ? edge : fill).push([x / N, y / N]);
    }
  }
  const pick = <T,>(list: T[], n: number) => {
    const out: T[] = [];
    const step = list.length / n;
    for (let i = 0; i < n && list.length; i++) out.push(list[Math.floor(i * step)]);
    return out;
  };
  const c = COUNTS[density];
  const dots: Dot[] = [];
  const add = (pt: [number, number], kind: 0 | 1 | 2, i: number) =>
    dots.push({ hx: pt[0], hy: pt[1], x: 0, y: 0, vx: 0, vy: 0, ph: i * 1.7, kind, r: kind === 2 ? 1.7 : kind === 0 ? 1.35 : 0.8 });
  pick(edge, c.outline).forEach((pt, i) => add(pt, 0, i));
  pick(fill, c.fill).forEach((pt, i) => add(pt, 1, i));
  // checkmark: two segments
  const a: [number, number] = [0.31, 0.52];
  const b: [number, number] = [0.45, 0.66];
  const d: [number, number] = [0.7, 0.37];
  const per = Math.floor(c.check * 0.4);
  for (let i = 0; i < per; i++) add([a[0] + ((b[0] - a[0]) * i) / per, a[1] + ((b[1] - a[1]) * i) / per], 2, i);
  const per2 = c.check - per;
  for (let i = 0; i < per2; i++) add([b[0] + ((d[0] - b[0]) * i) / per2, b[1] + ((d[1] - b[1]) * i) / per2], 2, i + per);
  return dots;
}

export function ShieldOrb({ mode = "idle", density = "hero", className = "" }: { mode?: OrbMode; density?: "hero" | "small"; className?: string }) {
  const ref = useRef<HTMLCanvasElement>(null);
  const modeRef = useRef<OrbMode>(mode);

  useEffect(() => {
    modeRef.current = mode;
  }, [mode]);

  useEffect(() => {
    const canvas = ref.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const dots = buildTargets(density);
    let W = 0;
    let H = 0;
    let dpr = 1;
    let box = { s: 0, ox: 0, oy: 0 };
    let color: [number, number, number] = [53, 102, 240];
    let target: [number, number, number] = color;
    let frame = 0;
    let raf = 0;
    let visible = true;
    let intro = reduce ? 1 : 0;
    const pointer = { x: -999, y: -999 };
    const t0 = performance.now();

    const readColor = () => {
      const m = modeRef.current;
      const tint = TINT[m];
      if (tint) {
        target = tint;
        return;
      }
      const raw = getComputedStyle(canvas).getPropertyValue("--orb").trim();
      const parts = raw.split(",").map((n) => Number(n));
      if (parts.length === 3 && parts.every((n) => Number.isFinite(n))) target = [parts[0], parts[1], parts[2]];
    };

    const place = (scatter: boolean) => {
      for (const d of dots) {
        const hx = box.ox + d.hx * box.s;
        const hy = box.oy + d.hy * box.s;
        if (scatter && !reduce) {
          const ang = (d.ph * 12.9898) % (Math.PI * 2);
          const rad = box.s * (0.7 + ((d.ph * 7.31) % 1) * 0.6);
          d.x = W / 2 + Math.cos(ang) * rad;
          d.y = H / 2 + Math.sin(ang) * rad;
        } else {
          d.x = hx;
          d.y = hy;
        }
        d.vx = d.vy = 0;
      }
    };

    const resize = () => {
      const r = canvas.getBoundingClientRect();
      dpr = Math.min(window.devicePixelRatio || 1, 2);
      W = Math.max(1, r.width);
      H = Math.max(1, r.height);
      canvas.width = Math.round(W * dpr);
      canvas.height = Math.round(H * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      const s = Math.min(W, H) * 0.92;
      box = { s, ox: (W - s) / 2, oy: (H - s) / 2 };
      place(frame === 0);
      if (reduce) draw(0);
    };

    const draw = (time: number) => {
      ctx.clearRect(0, 0, W, H);
      const m = modeRef.current;
      const scanning = m === "scan";
      const sweep = scanning ? ((time * 0.00075) % 1.5) - 0.25 : -9;
      const k = 0.055 * intro;
      const amp = (scanning ? 0.012 : 0.006) * box.s;
      const reach = box.s * 0.2;
      for (const d of dots) {
        const hx = box.ox + d.hx * box.s + Math.sin(time * 0.0011 + d.ph) * amp;
        const hy = box.oy + d.hy * box.s + Math.cos(time * 0.0009 + d.ph * 1.3) * amp;
        let fx = (hx - d.x) * k;
        let fy = (hy - d.y) * k;
        const dx = d.x - pointer.x;
        const dy = d.y - pointer.y;
        const dist2 = dx * dx + dy * dy;
        if (dist2 < reach * reach && dist2 > 0.01) {
          const dist = Math.sqrt(dist2);
          const push = (1 - dist / reach) * box.s * 0.012;
          fx += (dx / dist) * push;
          fy += (dy / dist) * push;
        }
        d.vx = (d.vx + fx) * 0.84;
        d.vy = (d.vy + fy) * 0.84;
        d.x += d.vx;
        d.y += d.vy;
        const ny = d.hy;
        const lit = scanning ? Math.max(0, 1 - Math.abs(ny - sweep) / 0.13) : 0;
        const base = d.kind === 2 ? 1 : d.kind === 0 ? 0.9 : 0.34;
        const alpha = Math.min(1, (base + lit * 0.5) * Math.min(1, intro * 1.4));
        const r = d.r * (1 + lit * 0.9) * (box.s > 120 ? 1 : 0.8);
        ctx.fillStyle = `rgba(${color[0] | 0},${color[1] | 0},${color[2] | 0},${alpha.toFixed(3)})`;
        ctx.beginPath();
        ctx.arc(d.x, d.y, r, 0, 6.2832);
        ctx.fill();
      }
    };

    const loop = (now: number) => {
      raf = 0;
      if (!visible || document.hidden) return;
      frame++;
      if (intro < 1) intro = Math.min(1, (now - t0) / 1400);
      if (frame % 20 === 1) readColor();
      color = [color[0] + (target[0] - color[0]) * 0.08, color[1] + (target[1] - color[1]) * 0.08, color[2] + (target[2] - color[2]) * 0.08];
      draw(now);
      raf = requestAnimationFrame(loop);
    };
    const start = () => {
      if (!raf && !reduce && visible && !document.hidden) raf = requestAnimationFrame(loop);
    };

    readColor();
    color = target;
    resize();
    const ro = new ResizeObserver(resize);
    ro.observe(canvas);
    const io = new IntersectionObserver(([entry]) => {
      visible = entry.isIntersecting;
      if (visible) start();
    });
    io.observe(canvas);
    const onMove = (e: PointerEvent) => {
      const r = canvas.getBoundingClientRect();
      pointer.x = e.clientX - r.left;
      pointer.y = e.clientY - r.top;
    };
    const onLeave = () => {
      pointer.x = pointer.y = -999;
    };
    const onVis = () => start();
    window.addEventListener("pointermove", onMove, { passive: true });
    document.addEventListener("pointerleave", onLeave);
    document.addEventListener("visibilitychange", onVis);
    start();
    return () => {
      if (raf) cancelAnimationFrame(raf);
      ro.disconnect();
      io.disconnect();
      window.removeEventListener("pointermove", onMove);
      document.removeEventListener("pointerleave", onLeave);
      document.removeEventListener("visibilitychange", onVis);
    };
  }, [density]);

  return <canvas ref={ref} className={className} aria-hidden="true" />;
}

export default ShieldOrb;
