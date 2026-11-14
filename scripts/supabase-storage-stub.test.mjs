import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { startStub } from "../.github/supabase-storage-stub.mjs";

let clock = 1_000_000;
let server;
let base;

before(async () => {
  server = await startStub({ port: 0, now: () => clock });
  base = `http://127.0.0.1:${server.address().port}/storage/v1`;
});

after(
  () =>
    new Promise((resolve) => {
      server.close(resolve);
      server.closeAllConnections();
    }),
);

const KEY = "files/a/book.pdf";
const BYTES = Buffer.from("%PDF-stub-bytes");

async function upload(key, bytes = BYTES) {
  const res = await fetch(`${base}/object/product-files/${key}`, {
    method: "POST",
    headers: { apikey: "k", "content-type": "application/pdf" },
    body: bytes,
  });
  assert.equal(res.status, 200);
}

function sign(key, { apikey = "k", body = { expiresIn: 60 } } = {}) {
  return fetch(`${base}/object/sign/product-files/${key}`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      ...(apikey ? { apikey } : {}),
    },
    body: JSON.stringify(body),
  });
}

test("sign then download returns bytes, content-type and file name", async () => {
  await upload(KEY);
  const res = await sign(KEY);
  assert.equal(res.status, 200);
  const { signedURL } = await res.json();
  assert.ok(signedURL.startsWith(`/object/sign/product-files/${KEY}?token=`));
  const download = await fetch(
    `${base}${signedURL}&download=${encodeURIComponent("Meu livro.pdf")}`,
  );
  assert.equal(download.status, 200);
  assert.equal(download.headers.get("content-type"), "application/pdf");
  assert.equal(
    download.headers.get("content-disposition"),
    'attachment; filename="Meu livro.pdf"',
  );
  assert.ok(Buffer.from(await download.arrayBuffer()).equals(BYTES));
});

test("a non-ASCII file name uses the RFC 5987 form", async () => {
  await upload(KEY);
  const { signedURL } = await (await sign(KEY)).json();
  const res = await fetch(
    `${base}${signedURL}&download=${encodeURIComponent("Guia ç.pdf")}`,
  );
  assert.equal(res.status, 200);
  assert.equal(
    res.headers.get("content-disposition"),
    "attachment; filename=\"Guia _.pdf\"; filename*=UTF-8''Guia%20%C3%A7.pdf",
  );
});

test("sign without apikey is 401", async () => {
  await upload(KEY);
  assert.equal((await sign(KEY, { apikey: null })).status, 401);
});

test("sign with an invalid expiresIn is 400", async () => {
  await upload(KEY);
  assert.equal((await sign(KEY, { body: { expiresIn: 0 } })).status, 400);
  assert.equal((await sign(KEY, { body: { expiresIn: "x" } })).status, 400);
});

test("sign for a missing object is 400 with statusCode 404", async () => {
  const res = await sign("files/none.pdf");
  assert.equal(res.status, 400);
  assert.equal((await res.json()).statusCode, "404");
});

test("a token issued for another key is refused", async () => {
  await upload(KEY);
  await upload("files/b.pdf");
  const { signedURL } = await (await sign(KEY)).json();
  const token = signedURL.split("?token=")[1];
  const res = await fetch(
    `${base}/object/sign/product-files/files/b.pdf?token=${token}`,
  );
  assert.equal(res.status, 400);
  assert.equal((await res.json()).error, "InvalidJWT");
});

test("tampered or missing token is refused; download needs no apikey", async () => {
  await upload(KEY);
  const url = `${base}/object/sign/product-files/${KEY}`;
  assert.equal((await fetch(`${url}?token=bogus`)).status, 400);
  assert.equal((await fetch(url)).status, 400);
});

test("expired token is refused", async () => {
  await upload(KEY);
  const { signedURL } = await (await sign(KEY)).json();
  clock += 61_000;
  const res = await fetch(`${base}${signedURL}`);
  assert.equal(res.status, 400);
  assert.match((await res.json()).message, /exp/);
});

test("object deleted after signing is 400 with statusCode 404", async () => {
  await upload(KEY);
  const { signedURL } = await (await sign(KEY)).json();
  await fetch(`${base}/object/product-files/${KEY}`, {
    method: "DELETE",
    headers: { apikey: "k" },
  });
  const res = await fetch(`${base}${signedURL}`);
  assert.equal(res.status, 400);
  assert.equal((await res.json()).statusCode, "404");
});

test("unsafe download names are refused", async () => {
  await upload(KEY);
  const { signedURL } = await (await sign(KEY)).json();
  for (const name of ['a"b.pdf', "a\\b.pdf", "a/b.pdf", "a\r\nb.pdf"]) {
    const res = await fetch(
      `${base}${signedURL}&download=${encodeURIComponent(name)}`,
    );
    assert.equal(res.status, 400, name);
  }
});
