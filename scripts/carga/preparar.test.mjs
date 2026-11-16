import assert from "node:assert/strict";
import { test } from "node:test";
import { buildAccounts, buildContas, confirmationToken, localApiUrl, parseProductIds } from "./preparar.mjs";

test("localApiUrl defaults to 127.0.0.1:8081 and accepts only local addresses", () => {
  assert.equal(localApiUrl({}), "http://127.0.0.1:8081");
  assert.equal(localApiUrl({ CARGA_API_URL: "http://localhost:8090" }), "http://localhost:8090");
  for (const bad of [
    "https://sapiensia-homolog.fly.dev",
    "http://sapiensia-homolog.fly.dev:8081",
    "http://10.0.0.5:8081",
    "http://localhost.evil.com:8081",
    "http://127.0.0.1.evil.com:8081",
  ]) {
    assert.throws(() => localApiUrl({ CARGA_API_URL: bad }), /CARGA_API_URL/);
  }
});

test("confirmationToken reads the token of the confirmation link in the e-mail", () => {
  const body = [
    "To: carga-1@example.com",
    "Subject: Confirme seu e-mail na SapiensIA",
    "",
    "Abra: http://localhost:5173/confirmar-email#aB3_-xyz9 e pronto.",
  ].join("\n");
  assert.equal(confirmationToken(body), "aB3_-xyz9");
  assert.equal(confirmationToken("sem link aqui"), null);
});

test("parseProductIds keeps the ids of the catalog page, in order", () => {
  const page = { items: [{ id: "a-1" }, { id: "b-2" }], page: 0, totalPages: 1 };
  assert.deepEqual(parseProductIds(page), ["a-1", "b-2"]);
  assert.deepEqual(parseProductIds({}), []);
});

test("buildAccounts makes unique accounts and spreads the purchased Products", () => {
  const accounts = buildAccounts({ count: 4, products: ["p1", "p2", "p3"], runId: "r1" });
  assert.equal(accounts.length, 4);
  assert.equal(new Set(accounts.map((a) => a.email)).size, 4);
  for (const a of accounts) {
    assert.match(a.email, /^carga-r1-\d+@example\.com$/);
    assert.ok(a.password.length >= 12);
  }
  assert.deepEqual(
    accounts.map((a) => a.productId),
    ["p1", "p2", "p3", "p1"],
  );
  assert.throws(() => buildAccounts({ count: 2, products: [], runId: "r1" }));
});

test("buildContas keeps the products and the accounts that cenarios.js reads", () => {
  const accounts = buildAccounts({ count: 2, products: ["p1"], runId: "r2" });
  const contas = buildContas({ accounts, products: ["p1"] });
  assert.deepEqual(contas.products, ["p1"]);
  assert.deepEqual(Object.keys(contas.accounts[0]).sort(), ["email", "password", "productId"]);
  assert.ok(!Number.isNaN(Date.parse(contas.createdAt)));
});
