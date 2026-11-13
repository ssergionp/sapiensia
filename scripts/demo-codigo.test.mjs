import assert from "node:assert/strict";
import { test } from "node:test";
import { totpCode } from "./demo-codigo.mjs";

test("RFC 6238 vector: SHA1, 6 digits, T=59 s", () => {
  assert.equal(totpCode("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 1), "287082");
});

test("always returns 6 digits", () => {
  assert.match(totpCode("JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP", 0), /^\d{6}$/);
});
