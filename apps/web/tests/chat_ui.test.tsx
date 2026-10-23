/**
 * Lightweight Chat UI smoke test placeholder.
 *
 * React Testing Library / Jest / Vitest are not installed in apps/web, so this
 * file intentionally avoids those dependencies and stays type-safe under
 * `npm run typecheck` (tsc --noEmit). It documents the manual verification
 * steps that would otherwise be covered by component tests.
 *
 * Manual verification checklist:
 *  - Chat shell renders at 390x844, 834x1112, and 1440x900 without horizontal overflow.
 *  - Sidebar drawer opens/closes with Escape and returns focus to its trigger.
 *  - Composer submits on Enter, inserts newline on Shift+Enter, and stays accessible.
 *  - Empty state, loading, streaming, and error states render without layout shift.
 *  - Reduced-motion preference disables non-essential animation.
 *  - No console errors during the flows above.
 */

/* eslint-disable */

type TestCallback = () => void | Promise<void>;

interface TestSuite {
  (name: string, cb: TestCallback): void;
  skip: (name: string, cb: TestCallback) => void;
  only: (name: string, cb: TestCallback) => void;
}

function makeSuite(): TestSuite {
  const run = ((_name: string, _cb: TestCallback) => {}) as unknown as TestSuite;
  run.skip = (_name: string, _cb: TestCallback) => {};
  run.only = (_name: string, _cb: TestCallback) => {};
  return run;
}

const describe = makeSuite();
const it = makeSuite();
const test = it;

describe.skip("TrustShield chat UI (manual smoke)", () => {
  it("renders the chat shell without horizontal overflow", () => {
    // Manual: inspect at 390/834/1440 widths.
  });

  it("opens and closes the responsive drawer with Escape", () => {
    // Manual: keyboard + focus-return check.
  });

  it("keeps streaming layout stable", () => {
    // Manual: observe no unexpected scroll jumps.
  });
});

// Type-level smoke: keep the chat entry point importable.
import { Chat } from "@/components/chat/Chat";

const _chatUiSmoke = Chat;
void _chatUiSmoke;

export {};
