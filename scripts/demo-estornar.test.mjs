import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { notPaidMessage, refundEvent } from "./demo-estornar.mjs";

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
    "findOrder",
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

test("notPaidMessage names the status and the Order and points to demo-pagar", () => {
  const id = "0b8f6c1e-8f4e-4c55-9d6a-1a2b3c4d5e6f";
  assert.equal(
    notPaidMessage("PENDING", id),
    `Só um Pedido pago pode ser estornado. Situação atual: PENDING. Rode antes: just demo-pagar ${id}`,
  );
});

test("notPaidMessage is null for a paid Order", () => {
  assert.equal(notPaidMessage("PAID", "x"), null);
});
