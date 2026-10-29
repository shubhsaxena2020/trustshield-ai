import assert from "node:assert/strict";
import test from "node:test";
import { userFacingRequestMessage } from "./userFacingError.mjs";

test("keeps safe validation feedback and maps common request statuses", () => {
  assert.equal(userFacingRequestMessage(422, "Text cannot be blank."), "Text cannot be blank.");
  assert.match(userFacingRequestMessage(413), /upload is too large/i);
  assert.match(userFacingRequestMessage(429), /Too many requests/i);
  assert.match(userFacingRequestMessage(503), /service is busy/i);
});

test("hides internal server details and unknown errors", () => {
  assert.match(userFacingRequestMessage(500, "Traceback: private path C:\\service\\main.py"), /couldn't be completed/i);
  assert.match(userFacingRequestMessage(422, "database error from Supabase"), /couldn't be completed/i);
  assert.match(userFacingRequestMessage(400, "unexpected backend detail"), /couldn't be completed/i);
});
