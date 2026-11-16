import assert from "node:assert/strict";
import { test } from "node:test";
import { checkBaseUrl } from "./local-url.mjs";

test("checkBaseUrl accepts local addresses, with or without port and trailing slash", () => {
  for (const ok of [
    "http://localhost:8081",
    "http://127.0.0.1:8081",
    "http://host.docker.internal:8081",
    "http://localhost",
    "http://localhost:8081/",
  ]) {
    assert.equal(checkBaseUrl(ok, undefined), ok.replace(/\/+$/, ""));
  }
});

test("checkBaseUrl falls back to the local API seen from Docker", () => {
  assert.equal(checkBaseUrl(undefined, undefined), "http://host.docker.internal:8081");
});

test("checkBaseUrl refuses everything that is not local", () => {
  for (const bad of [
    "http://localhost.evil.com:8081",
    "http://127.0.0.1.nip.io:8081",
    "http://localhost@evil.com",
    "https://sapiensia-homolog.fly.dev",
    "http://example.com",
    "",
  ]) {
    assert.throws(() => checkBaseUrl(bad, undefined), /não é local|CARGA_PERMITIR_REMOTO/, bad);
  }
});

test("checkBaseUrl accepts a remote address only with CARGA_PERMITIR_REMOTO=sim", () => {
  const remote = "https://sapiensia-homolog.fly.dev";
  assert.equal(checkBaseUrl(remote, "sim"), remote);
  for (const other of ["", "yes", "SIM", "true", undefined]) {
    assert.throws(() => checkBaseUrl(remote, other));
  }
});
