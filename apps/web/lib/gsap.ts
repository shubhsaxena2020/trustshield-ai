"use client";

import gsap from "gsap";
import { Flip } from "gsap/Flip";
import { useGSAP } from "@gsap/react";

// Register once; every component imports gsap + useGSAP from here so plugins are always ready.
gsap.registerPlugin(useGSAP, Flip);

export const prefersReducedMotion = () =>
  typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

export { gsap, Flip, useGSAP };
