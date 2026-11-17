import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { refundEvent } from "./demo-estornar.mjs";

test("refundEvent is a PAYMENT_REFUNDED event for the charge", () => {
  const event = refundEvent("pay_123");
  assert.equal(event.event, "PAYMENT_REFUNDED");
  assert.equal(event.payment.status, "REFUNDED");
  assert.equal(event.payment.id, "pay_123");
  assert.equal(event.payment.object, "payment");
  assert.equal(event.payment.billingType, "PIX");
  assert.match(event.id, /^evt_demo_[0-9a-f]{16}$/);
  assert.match(event.dateCreated, /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
});

test("refundEvent gets a new id on every call", () => {
  assert.notEqual(refundEvent("pay_123").id, refundEvent("pay_123").id);
});

test("the script imports the demo-pagar helpers instead of copying them", () => {
  const source = readFileSync(
    new URL("./demo-estornar.mjs", import.meta.url),
    "utf8",
  );
  assert.match(source, /from "\.\/demo-pagar\.mjs"/);
  for (const name of [
    "parseOrderId",
    "apiBaseUrl",
    "findChargeId",
    "LOCAL_WEBHOOK_TOKEN",
  ]) {
    assert.ok(
      source.includes(`${name},`) || source.includes(`${name} }`),
      `${name} is imported`,
    );
    assert.ok(!source.includes(`function ${name}`), `${name} is not redefined`);
    assert.ok(!source.includes(`const ${name}`), `${name} is not redefined`);
  }
});
