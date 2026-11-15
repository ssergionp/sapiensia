# #35 E2E da jornada completa (+ #128 item 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One browser test that a new Customer goes from sign-up to the downloaded file, against the real API and database, plus the fix of the flaky `PaymentLogTest` (#128, item 1).

**Architecture:** The new test is appended to `frontend/e2e/admin-panel.e2e.ts` and reuses that file's helpers. Only the external edge is fake: Asaas (the in-process `FakePixGateway` locally, `.github/asaas-stub.mjs` in CI) and Storage (local storage locally, `.github/supabase-storage-stub.mjs` in CI). The payment arrives through the real webhook endpoint. No `page.route`, no mocks.

**Tech Stack:** Playwright (`@playwright/test`), JUnit 5 + AssertJ.

**Spec:** `docs/spec/sapiensia-spec.md` §10.1 ("End-to-end para compra, pagamento, entrega"); issues #35 and #128.

## Global Constraints

- Requirement IDs in the test title: FR-016 (Catálogo), FR-019 (Checkout), FR-021 (Webhooks), FR-024 (Entrega), FR-029 (Biblioteca), FR-034 (Download seguro).
- No new user-visible text: the change touches only tests. No new dependency, no migration, no ADR.
- Secrets (Administrator password, TOTP, webhook token) never go into a step title, a log or an assertion message (same rules as the rest of `admin-panel.e2e.ts`).
- Locally: only `just lint`, focused tests and the e2e, one heavy command at a time. The full check is the CI's.

## Decisions

**(a) The journey, all in one fresh browser context, one Customer:**
1. Setup (not the journey): the Administrator publishes a Product `E2E Jornada <stamp>`, R$ 29,90, with `buildPdf("jornada-<stamp>")`. Same UI steps as the existing download test.
2. Sign-up: `registerAndConfirm` (UI form; the confirmation link is read from the e-mail outbox files).
3. Login: from `/` click "Entrar", fill in, see "Você entrou como <email>.".
4. Catálogo: from `/` click "Ver o Catálogo", click the Product link, see the title and "R$ 29,90". (The Catálogo is ordered newest first, so the new Product is on page 1 even with old local data.)
5. Order: "Comprar com Pix", CPF block, `fictitiousCpf()`, "Gerar Pix", Order page with "Pague com Pix". The charge id is read from the Pix code (as `buyProductWithPix` does).
6. Payment: `postPaymentReceived` sends `PAYMENT_RECEIVED` to the real `/api/webhooks/asaas` with the accepted token (local token or `E2E_ASAAS_WEBHOOK_TOKEN` in CI). The same page turns to "Pagamento confirmado" without a reload.
7. My Orders: from `/` click "Meus Pedidos", the item shows "Pago", click "Ir para a Biblioteca".
8. Library and Download: the title is listed; "Baixar <title>" starts a download named `e2e-jornada-<stamp>.pdf` whose bytes equal `buildPdf("jornada-<stamp>")`.
9. E-mail: the "Seu pagamento na SapiensIA foi confirmado" message names the Product and links to `/biblioteca`; at the end there is exactly one.

**(b) Reuse vs new.** Reused, unchanged: `loginWithStoredCredentials`, `fictitiousCpf`, `postPaymentReceived`, `PRODUCT_EDIT_PATH`, `ORDER_PATH`, `PAID_HEADING`, `PAID_TEXT`, `PAID_EMAIL_SUBJECT`, `LOCAL_WEBHOOK_TOKEN` (all in `admin-panel.e2e.ts`), `registerAndConfirm`/`uniqueEmail`/`strongPassword`/`waitForMessage`/`messagesFor` (`outbox.ts`), `buildPdf` (`pdf.ts`), and the CI stubs and env already in `ci.yml`. `scripts/demo-pagar.mjs` is **not** used: it reads the charge id from the local database with `docker exec` and only talks to a local API, so it would not run in CI; `postPaymentReceived` sends the same event. New: only the test itself (~110 lines) and the #128 fix.

**(c) Where it runs, time, isolation.**
- It goes at the end of `admin-panel.e2e.ts`, not in a new file. Playwright runs different files in parallel workers; a separate file could log in as the single Administrator at the same time as the panel tests (two logins on the same TOTP step: the second is refused) or before the first test of the panel file creates the stored credentials. Inside the file, tests run in order, after that first test. It is ignored by the `celular` project, like the rest of the file (other tests already cover phone layouts).
- `just e2e` runs it locally (against `just dev-back`, local profile) and the CI `e2e` job runs it against the prod Docker image with the stubs. Nothing to change in `ci.yml` or `playwright.config.ts`.
- Expected time: about 40–70 s (up to 30 s of it waiting for a fresh TOTP step); `test.setTimeout(180_000)` like its neighbours. Measured during implementation and reported.
- No dependency on other tests' data: the Product, Customer, Order, charge, event id and PDF marker are new on every run (`Date.now()` plus random), and every locator filters by the unique title or e-mail. The one shared state is the Administrator's stored credentials (`.tmp/e2e/`), which every panel test already depends on.

**(d) #128 item 1, exact change** in `backend/src/test/java/br/com/sergio/sapiensia/payment/PaymentLogTest.java:54-55`:

```java
// before
Pattern.compile("(?i)(value|amount|price|cents)\\W{0,4}(2990|29\\.9)(?![0-9])|29[.,]90");
// after
Pattern.compile("(?i)(value|amount|price|cents)\\W{0,4}(2990|29\\.9)(?![0-9])|(?<![0-9]{2}:)29[.,]90");
```

The only text no longer flagged is `29.90`/`29,90` right after two digits and a colon, i.e. the seconds of a clock time (`15:52:29.904…`, `15:52:29,904`). Every other `29.90`/`29,90` still fails the test, including `"value":29.90` (preceded by `":`, not by digits). The javadoc above the pattern gets one sentence saying so.

**(e) What this does not prove.**
- The real Asaas (HTTP contract, real Pix, its retries and IPs) and the real Supabase Storage (signed URL): both are stubs; their contracts are covered by the backend tests.
- Real e-mail delivery: the e-mail is a file in the outbox.
- Refund, AI generation and publication workflows from §10.1: not built yet.
- Phone layout, accessibility, other browsers than Chromium: other tests or tickets.
- The Administrator's part beyond publishing a Product (it is setup here).

## Review Focus

- The webhook is processed by the scheduler, not inline: the paid heading waits up to 30 s, as in the existing tests.
- CI retries a failed test once (`retries: 1`): on retry everything is created again, so the "exactly one paid e-mail" check still holds per Customer.
- The download in CI goes through the Storage stub's signed URL, locally through `/api/local-downloads/<token>`: the byte comparison must pass in both, so the test runs in both before the PR.
- Locally, the worktree uses the main folder's database: the stored Administrator credentials are in the main folder's `.tmp/e2e/`; the run points `E2E_ADMIN_PASSWORD_FILE` and `E2E_ADMIN_TOTP_FILE` there.
- A bare `29.90` that is not a timestamp (`total 29.90`) must still be caught after the fix: pinned in Task 1.

---

### Task 1: PaymentLogTest no longer matches log timestamps (#128 item 1)

**Files:**
- Modify: `backend/src/test/java/br/com/sergio/sapiensia/payment/PaymentLogTest.java:50-55` (pattern and javadoc) and a new test method in the same class.

- [ ] **Step 1: Write the test that reproduces #128** — `shouldCatchTheValueButNotTheSecondsOfATimestamp`, `@DisplayName("ADR-0006, SEC-033: the value pattern ignores the seconds of a timestamp")`:

```java
assertThat(VALUE_FORMS.matcher("\"@timestamp\":\"2026-11-15T15:52:29.904482300Z\"").find()).isFalse();
assertThat(VALUE_FORMS.matcher("2026-11-15 15:52:29,904 INFO").find()).isFalse();
for (String leak : List.of("\"value\":29.90", "\"value\":29.9}", "amount=2990", "price: 29.9 ", "R$ 29,90", "total 29.90")) {
    assertThat(VALUE_FORMS.matcher(leak).find()).as(leak).isTrue();
}
```

- [ ] **Step 2: Run it and see it fail** — `cd backend; .\mvnw.cmd test -Dtest=PaymentLogTest`. Expected: the new test FAILS on the `@timestamp` line; the existing test passes.
- [ ] **Step 3: Change the pattern** as in Decision (d), and the javadoc.
- [ ] **Step 4: Run it and see it pass** — same command. Expected: both tests PASS.
- [ ] **Step 5: Commit** — `test: ignore log timestamps in the PaymentLogTest value check (#128)`.

### Task 2: E2E of the full journey (#35)

**Files:**
- Modify: `frontend/e2e/admin-panel.e2e.ts` (append one test at the end; nothing else changes).

**Interfaces:** consumes the helpers listed in Decision (b), unchanged.

- [ ] **Step 1: Write the test** `"FR-016 FR-019 FR-021 FR-024 FR-029 FR-034: jornada completa — o Cliente se cadastra, compra com Pix, tem o pagamento confirmado e baixa o Arquivo"`, steps 1–9 of Decision (a), with `test.setTimeout(180_000)`, the localhost guard of its neighbours, and `try/finally` closing the context. Key assertions:
  - `await expect(buyer.getByText(\`Você entrou como ${email}.\`)).toBeVisible()`
  - Catálogo: `getByRole("heading", { level: 1, name: title })` and `getByText(/R\$\s29,90/)`
  - `await expect(buyer).toHaveURL(ORDER_PATH)` and the paid heading `{ timeout: 30_000 }` without reload, then `PAID_TEXT` visible
  - My Orders item filtered by title: `getByText("Pago", { exact: true })`
  - `download.suggestedFilename()` is `e2e-jornada-${stamp}.pdf`; `(await readFile(await download.path())).equals(buildPdf(marker))` is `true`
  - paid e-mail body contains `title` and `"/biblioteca"`; `messagesFor(email, PAID_EMAIL_SUBJECT)` has length 1 at the end
- [ ] **Step 2: Run it locally** (`just db` in the main folder, `just dev-back` and `just dev-front` in the worktree), then in `frontend/`: `pnpm exec playwright test e2e/admin-panel.e2e.ts -g "jornada completa" --project=desktop` with `E2E_ADMIN_PASSWORD_FILE`/`E2E_ADMIN_TOTP_FILE` pointing to the main folder's `.tmp/e2e/`. Expected: 1 passed; note the duration.
- [ ] **Step 3: Prove it can fail** — temporarily compare against `buildPdf("outro")`, run again, expect FAIL on the byte comparison, revert.
- [ ] **Step 4: `just lint`** — expected: no errors (run `just fmt` if it flags formatting).
- [ ] **Step 5: Commit** — `test: e2e of the full purchase journey (#35)`.

### Task 3: Delivery

- [ ] `just check-rapido` (hook requirement), skill `verificar-entrega` with its table.
- [ ] After your OK: push, PR with `Closes #35` and `Refs #128`, citing FR-016, FR-019, FR-021, FR-024, FR-029, FR-034; the ticket is done only with the CI `e2e` job green.
