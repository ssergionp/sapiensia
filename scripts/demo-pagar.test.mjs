import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import {
  apiBaseUrl,
  LOCAL_WEBHOOK_TOKEN,
  parseOrderId,
  paymentEvent,
} from "./demo-pagar.mjs";

const UUID = "123e4567-e89b-42d3-a456-426614174000";

test("parseOrderId accepts a UUID and nothing else", () => {
  assert.equal(parseOrderId(UUID), UUID);
  assert.equal(parseOrderId(UUID.toUpperCase()), UUID);
  for (const bad of [undefined, "", "abc", `${UUID}'; drop table x;--`, "1; ls"]) {
    assert.equal(parseOrderId(bad), null);
  }
});

test("apiBaseUrl defaults to 127.0.0.1:8081 and accepts only local addresses", () => {
  assert.equal(apiBaseUrl({}), "http://127.0.0.1:8081");
  assert.equal(apiBaseUrl({ DEMO_API_URL: "http://localhost:8090" }), "http://localhost:8090");
  assert.equal(apiBaseUrl({ DEMO_API_URL: "http://127.0.0.1:8090" }), "http://127.0.0.1:8090");
  for (const bad of [
    "https://sapiensia-homolog.fly.dev",
    "http://10.0.0.5:8081",
    "http://localhost.evil.com:8081",
  ]) {
    assert.throws(() => apiBaseUrl({ DEMO_API_URL: bad }));
  }
});

test("paymentEvent carries the charge id and the received type", () => {
  const event = paymentEvent("pay_abc123");
  assert.equal(event.event, "PAYMENT_RECEIVED");
  assert.equal(event.payment.id, "pay_abc123");
  assert.match(event.id, /^evt_demo_[0-9a-f]+$/);
  assert.notEqual(paymentEvent("pay_abc123").id, event.id);
});

test("the script token equals the one of application-local.yml", () => {
  const yml = readFileSync(
    new URL("../backend/src/main/resources/application-local.yml", import.meta.url),
    "utf8",
  );
  assert.ok(yml.includes(`:${LOCAL_WEBHOOK_TOKEN}}`));
});
