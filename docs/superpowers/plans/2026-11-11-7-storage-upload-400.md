# Storage upload HTTP 400 (#7) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Find why `SupabaseProductFileStorage.put` gets HTTP 400 from Supabase Storage on staging while the same request made with `curl.exe` gets 200, fix it with the least code, and log the safe reason of any future Storage error.

**Architecture:** A test starts a local fake HTTP server (JDK `com.sun.net.httpserver.HttpServer`, loopback, no network, no secret) and points the real adapter at it, with the real `JdkClientHttpRequestFactory`. The server records what really arrives: method, path, protocol, headers and body size. The current tests use `MockRestServiceServer`, which replaces the HTTP client, so none of them sees the real request.

**Tech Stack:** Java 25, Spring Boot 4.1 `RestClient`, JDK `HttpClient`, Jackson 3 (`tools.jackson`), JUnit 5, AssertJ.

**Spec:** issue #7 (staging) and the diagnosis of 2026-11-11 in this session: with `curl.exe`, the same `sb_secret_` key in the `apikey` header only, `x-upsert: false`, `Content-Type: application/pdf` and the same PDF, `POST /storage/v1/object/product-files/<path>` answered 200; the app got 400 (log 2026-11-11 23:06:16). Bucket, key, URL and type are right. SEC-047 (no secret, URL or object path in messages and logs).

## Global Constraints

- No secret, project URL or object path in any log line, exception message, test output or commit.
- The exception message stays `Storage could not <action> the file: HTTP <status>` (or the class name).
- No network in tests; the fake server listens on `127.0.0.1` with an ephemeral port.
- No new dependency.
- Focused tests only (`.\mvnw.cmd test -Dtest=...`); `just check-resumo` only when the owner says the machine is free.
- No change outside `product/` storage code and its tests.

## Review Focus

- A Supabase error body with the secret, the project URL or the object path inside `message` must not reach the log.
- A non-JSON or empty error body logs only the status.
- A very long `message` is cut at 200 characters.
- A 2xx response must not log anything.
- The DELETE path keeps treating 404 as "already gone".

## Known limit

The fake server is plain HTTP. Over `http://` the JDK client asks for HTTP/2 with an `Upgrade: h2c` header, which never happens over `https://` (there HTTP/2 is negotiated by TLS ALPN, with no header). So the test proves the body framing and headers, but it cannot reproduce HTTP/2 over TLS. If hypotheses (a), (c) and (d) are ruled out, (b) stays unproven locally and only the logged reason (Task 2) on staging can settle it.

---

### Task 1: Capture the real request and compare with curl

**Files:**
- Create: `backend/src/test/java/br/com/sergio/sapiensia/product/SupabaseProductFileStorageWireTest.java`

- [ ] **Step 1:** Test `shouldSendTheSameRequestAsCurl` (`@DisplayName("SEC-047: o adaptador envia ao Storage a mesma requisição que o curl")`): start `HttpServer` on `127.0.0.1:0`, answer 200 `{"Key":"x"}`, build `new SupabaseProductFileStorage(RestClient.builder(), "http://127.0.0.1:<port>", "test-secret-not-real", "product-files")`, call `put(key, file, size)` with a 1 KB fake `%PDF-` file, and record method, path, protocol, every header and body bytes.
- [ ] **Step 2:** Print the recorded request (header names and values except `apikey`, shown as `<present>`) and assert what curl sends: `POST`, path `/storage/v1/object/product-files/<key>`, `Content-Length` equal to the file size, no `Transfer-Encoding: chunked`, `Content-Type: application/pdf` exactly, body size equal to the file size and body SHA-256 equal to the file's, `x-upsert: false`. The server uses port 0, is stopped in `@AfterEach`, and the test depends on no order and no network.
- [ ] **Step 3:** Run `.\mvnw.cmd test -Dtest=SupabaseProductFileStorageWireTest` and record which assertions fail. Show the owner the difference before any fix.
- [ ] **Step 4:** Check the hypotheses one by one against the recording: (a) chunked without `Content-Length`; (b) HTTP/2 (`Upgrade`/`HTTP2-Settings` headers show only the attempt; see Known limit); (c) `Content-Type` with extra parameters (`charset`); (d) any extra header from Spring or the JDK.
- [ ] **Step 5:** If one is proven: minimal fix in `SupabaseProductFileStorage` (for example a body with a known length, or `HttpClient.Version.HTTP_1_1` only if (b) is the only one left and the owner agrees), the test goes from red to green, commit `fix: send the product file to Supabase Storage the way it accepts (#7)`.

### Task 2: Log the safe reason of a Storage error

**Files:**
- Modify: `backend/src/main/java/br/com/sergio/sapiensia/product/SupabaseProductFileStorage.java`
- Test: `backend/src/test/java/br/com/sergio/sapiensia/product/SupabaseProductFileStorageTest.java`

- [ ] **Step 1:** Tests (with a log capture appender on the adapter logger):
  - `shouldLogStatusCodeErrorAndMessageOfAStorageError`: response 400 with `{"statusCode":"403","error":"Unauthorized","message":"invalid signature"}` logs one WARN line with `statusCode=403`, `error=Unauthorized`, `message=invalid signature`, and the exception message is still exactly `Storage could not store the file: HTTP 400`.
  - `shouldNeverLogTheSecretTheUrlOrTheObjectPath` (SEC-047): body whose three fields contain the secret, the project URL, the bucket path and the object key: none of the four appears in the log.
  - `shouldLogOnlyTheStatusWhenTheBodyIsNotJson`: body `<html>boom</html>` logs the status only, no `boom`.
  - `shouldCutTheFieldsAt200Characters`: a 500-character `message` is logged with at most 200 characters.
  - `shouldLogTheSafeReasonOfADeleteError`: DELETE answered 400 with the JSON body logs the same fields; `shouldNotLogAMissingObjectOnDelete`: DELETE answered 404 logs nothing and does not throw.
- [ ] **Step 2:** Run them red.
- [ ] **Step 3:** In `failure(...)`: when the exception is a `RestClientResponseException`, parse `getResponseBodyAsString()` with a plain `JsonMapper` (local, no bean), read only `statusCode`, `error`, `message` as text, replace the secret, the base URL, `"/storage/v1/object/" + bucket` and the key with `<redacted>`, cut each at 200 characters, and `log.warn("Storage rejected the {} with HTTP {} (statusCode={}, error={}, message={})", ...)`. Not JSON or no fields: `log.warn("Storage rejected the {} with HTTP {}", ...)`. The exception message is unchanged.
- [ ] **Step 4:** Run `.\mvnw.cmd test -Dtest=SupabaseProductFileStorage*` green.
- [ ] **Step 5:** Commit `fix: log the Storage error fields without secrets when an upload fails (#7)`.

## What does not change

- The controller (its log line from #93 stays), the 503 answer and the user text.
- The object key format, the bucket, the secret handling, the timeouts.
- The DELETE behaviour, except that its errors are logged the same way.

## Owner decisions

- If only (b) is left, forcing HTTP/1.1 is a guess: it goes in only with the owner's OK, otherwise only Task 2 ships and staging tells the real reason.
