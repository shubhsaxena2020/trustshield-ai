import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

const riskCard = await readFile(new URL("../components/chat/RiskCard.tsx", import.meta.url), "utf8");
const chat = await readFile(new URL("../components/chat/Chat.tsx", import.meta.url), "utf8");

test("unable-to-verify results do not render a zero score or progress bar", () => {
  assert.match(riskCard, /analysis\.risk_level === "unable_to_verify" \? "not available"/);
  assert.match(riskCard, /analysis\.risk_level !== "unable_to_verify" && \(/);
});

test("chat UI caps combined attachments and handles platform 413 responses", () => {
  assert.match(chat, /const MAX_TOTAL_FILE_BYTES = 4 \* 1024 \* 1024/);
  assert.match(chat, /Attachments must total 4 MB or less/);
  assert.match(chat, /res\.status === 413 \? "The upload is too large/);
});
