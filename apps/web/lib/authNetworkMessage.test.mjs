import test from "node:test";
import assert from "node:assert/strict";
import { authNetworkMessage } from "./authNetworkMessage.mjs";

test("online auth network failures blame the unavailable account service", () => {
  assert.equal(authNetworkMessage(true), "The account service is temporarily unavailable. You can keep checking messages without an account; please try again later.");
});

test("offline auth failures keep the user's connection message", () => {
  assert.equal(authNetworkMessage(false), "Could not reach the server. Check your connection and try again.");
});
