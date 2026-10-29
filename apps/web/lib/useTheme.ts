"use client";

import { useCallback, useEffect, useSyncExternalStore } from "react";

export type Theme = "light" | "dark";
const KEY = "trustshield-v2-theme";
const EVENT = "trustshield:theme";

function read(): Theme {
  try {
    const stored = window.localStorage.getItem(KEY);
    if (stored === "light" || stored === "dark") return stored;
  } catch {
    // storage blocked: fall through to the system preference
  }
  return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

function subscribe(onChange: () => void) {
  const mq = window.matchMedia("(prefers-color-scheme: dark)");
  window.addEventListener("storage", onChange);
  window.addEventListener(EVENT, onChange);
  mq.addEventListener("change", onChange);
  return () => {
    window.removeEventListener("storage", onChange);
    window.removeEventListener(EVENT, onChange);
    mq.removeEventListener("change", onChange);
  };
}

type ViewTransitionDoc = Document & { startViewTransition?: (cb: () => void) => { ready: Promise<void> } };

export function useTheme() {
  const theme = useSyncExternalStore<Theme>(subscribe, read, () => "light");

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);

  const setTheme = useCallback((next: Theme) => {
    try {
      window.localStorage.setItem(KEY, next);
    } catch {
      // storage blocked: the choice just won't persist
    }
    window.dispatchEvent(new Event(EVENT));
  }, []);

  // Circular reveal from the clicked button via the View Transitions API; plain swap where it is unsupported
  // or the user prefers reduced motion.
  const toggle = useCallback(
    (origin?: { x: number; y: number }) => {
      const next: Theme = read() === "dark" ? "light" : "dark";
      const apply = () => {
        document.documentElement.dataset.theme = next;
        setTheme(next);
      };
      const doc = document as ViewTransitionDoc;
      if (!doc.startViewTransition || !origin || window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
        apply();
        return;
      }
      const radius = Math.hypot(Math.max(origin.x, window.innerWidth - origin.x), Math.max(origin.y, window.innerHeight - origin.y));
      doc
        .startViewTransition(apply)
        .ready.then(() => {
          document.documentElement.animate(
            { clipPath: [`circle(0px at ${origin.x}px ${origin.y}px)`, `circle(${radius}px at ${origin.x}px ${origin.y}px)`] },
            { duration: 750, easing: "cubic-bezier(0.22, 1, 0.36, 1)", pseudoElement: "::view-transition-new(root)" },
          );
        })
        .catch(() => undefined);
    },
    [setTheme],
  );

  return { theme, setTheme, toggle };
}

// Runs before first paint (inlined in <head>) so the page never flashes the wrong theme.
export const THEME_BOOT_SCRIPT = `(function(){try{var s=localStorage.getItem("${KEY}");var d=s?s==="dark":window.matchMedia("(prefers-color-scheme: dark)").matches;document.documentElement.dataset.theme=d?"dark":"light"}catch(e){}})()`;
