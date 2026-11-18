# #187 Capa obrigatória para publicar um Produto — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publishing (first time or again) a Product without a Capa is refused by the API and by the panel, with a clear message; a published Product cannot lose its Capa; Products already published without a Capa stay published, with a notice in the panel.

**Architecture:** The rule lives in `ProductPublicationService.publish` (under the Product row lock it already takes) and in `ProductCoverService.remove` (same lock), so publish-vs-remove races cannot end in a published Product without a Capa. The 409 body keeps the existing shape `{"errors":{...}}`, now with `file` and/or `cover`. The panel disables "Publicar" without a Capa and hides "Remover capa" while published.

**Tech Stack:** Spring Boot 4.1 / JdbcTemplate / Testcontainers; React + TanStack Query + Vitest; Playwright e2e.

**Spec:** issue #187; `docs/spec/sapiensia-spec.md` FR-011 ("Bloquear publicação até concluir aprovações e validações requeridas").

## Global Constraints

- Requirement ID in test names and PR: **FR-011** (publish refusals). No new endpoint, no migration, no new dependency, no SecurityConfig change.
- Error codes (exact): publish without Capa → `409 {"errors":{"cover":"COVER_REQUIRED"}}`; without file and Capa → `409 {"errors":{"file":"FILE_REQUIRED","cover":"COVER_REQUIRED"}}`; removing the Capa of a PUBLISHED Product → `409 {"errors":{"cover":"COVER_REQUIRED_WHILE_PUBLISHED"}}`. Same ProblemDetail as today (`title "Invalid request"`).
- Every refusal writes one FAILURE audit row in its own transaction (`audit.recordIndependent`), like the file refusal today.
- Products already PUBLISHED without a Capa are not touched (no migration, no automatic unpublish).
- Replacing the Capa (POST upload) stays allowed in every status, and never leaves the Product without a Capa, not for an instant and not when the upload fails: the new object is stored and the `product_covers` row is swapped (`upsert`) before the old object is deleted. `ProductCoverService.store` already works this way; Task 2 pins it with tests.
- The e2e that publishes and removes the Capa is not loosened: it proves the removal is refused on the published Product and still removes the Capa of a Rascunho.
- User-visible texts: only the ones listed in "Textos" below.

## Textos que o usuário vê (para aprovação)

| # | Onde | Texto |
|---|------|-------|
| T1 | Publicação, dica com botão desativado (sem Capa) | "Envie a Capa do produto para poder publicar." |
| T2 | Publicação, erro 409 `cover` | "Envie a Capa do produto antes de publicar." |
| T3 | Capa, no lugar de "Remover capa" quando Publicado com capa | "Para remover a capa, despublique o Produto. Para trocá-la, envie outra imagem." |
| T4 | Capa, erro 409 ao remover (o Produto foi publicado em outra aba) | "Este Produto está publicado e precisa de capa. Para remover a capa, despublique o Produto." |
| T5 | Capa, aviso quando Publicado **sem** capa (Produtos antigos) | "Este Produto está publicado sem capa. Ele continua no Catálogo, mas, se for despublicado, só poderá ser publicado de novo depois que você enviar uma capa." |
| T6 | Capa, pergunta de confirmação da remoção (troca a atual "…O Catálogo passa a mostrar um quadro sem imagem.") | "Remover a capa deste Produto? Sem capa, ele não pode ser publicado." |

Sem Arquivo **e** sem Capa, as dicas T1 e a atual `needFile` aparecem as duas (uma linha cada); o mesmo para os erros T2 e `fileRequired`. Nenhum outro texto muda.

## Review Focus

1. **Publish and remove-cover at the same time** — never ends PUBLISHED without a Capa (one of the two gets 409). Test in Task 2.
2. **UNPUBLISHED Product without Capa** (an old one that was unpublished) — "Publicar de novo" is refused like a draft. Test in Task 1.
3. **Repeated PUT on a Product PUBLISHED without Capa** — answers 409, stays PUBLISHED, Catálogo unchanged (no auto-unpublish). Test in Task 1.
4. **Stale screen** — the panel showed "Remover capa" (Rascunho) but the Product was published in another tab: the 409 shows T4 and the screen reloads the Product. Test in Task 4.
5. **No file and no Capa** — both messages at once, not only the first. Tests in Tasks 1 and 3.

## Files

- Modify `backend/.../product/ProductPublicationService.java` — check the Capa next to the file.
- Replace `backend/.../product/ProductFileRequiredException.java` with `ProductNotPublishableException.java` (carries the error map).
- Create `backend/.../product/ProductCoverRequiredWhilePublishedException.java`.
- Modify `backend/.../product/ProductCoverService.java` (`remove`), `backend/.../web/ApiExceptionHandler.java` (handlers).
- Tests: `admin/AdminProductPublicationTest`, `admin/AdminProductPublicationAuthorizationTest`, `admin/AdminProductPublicationCapabilityTest`, `admin/AdminProductPublicationConcurrencyTest`, `admin/AdminProductCoverTest`, `admin/AdminProductFixtures`.
- Frontend: `features/products/api.ts`, `messages.ts`, `ProductPublicationSection.tsx` (+ test), `ProductCoverSection.tsx` (+ test), `ProductFormPage.tsx` (only if the cover section needs `status` passed in).
- E2E: `frontend/e2e/admin-panel.e2e.ts` (+ a cover-upload helper next to `e2e/image.ts`).
- Docs: `GLOSSARY.md` ("Publicar", "Capa"), `docs/adr/0007-modelo-comercial-da-fase-1.md` (bullet "Capa pública"), this plan.

---

### Task 1: API refuses to publish without a Capa

**Files:** `ProductPublicationService.java`, `ProductNotPublishableException.java` (new, replaces `ProductFileRequiredException`), `ApiExceptionHandler.java`, `AdminProductFixtures.java`, `AdminProductPublicationTest.java`, plus the publication Authorization/Capability/Concurrency tests that expect 200.

**Interfaces:**
- Produces: `public class ProductNotPublishableException extends RuntimeException` with `Map<String, String> errors()` (keys `file`, `cover`; insertion order file, cover); handler answers `409` with `problem(errors)`.
- Produces: `AdminProductFixtures.insertProductWithFileAndCover(JdbcTemplate, ...)` (same params as `insertProductWithFile`), and `AdminProductFixtures.addCover(JdbcTemplate, UUID productId)` that inserts a `product_covers` row (`covers/<id>/<uuid>.png`, `image/png`, valid size/width/height, `version` 16 chars). Task 2 uses both.

- [ ] **Step 1: Failing tests** in `AdminProductPublicationTest`:
  - `@DisplayName("FR-011: recusa publicar um Rascunho sem capa")` — product with file, no cover → `409`, `$.errors.cover == "COVER_REQUIRED"`, `$.errors.file` absent, status stays `DRAFT`, one `ADMIN_PRODUCT_PUBLISHED` FAILURE audit row.
  - `@DisplayName("FR-011: sem arquivo e sem capa, a recusa traz os dois motivos")` → `$.errors.file == "FILE_REQUIRED"` and `$.errors.cover == "COVER_REQUIRED"`.
  - `@DisplayName("FR-011: Produto despublicado sem capa não é publicado de novo")` — file, no cover, status set to `UNPUBLISHED` via SQL (with `first_published_at`) → `409 cover`, stays `UNPUBLISHED`.
  - `shouldKeepAPublishedProductWithoutCoverPublished` — PUBLISHED via `AdminProductFixtures.publish`, no cover, PUT → `409 cover`, still `PUBLISHED`, still listed in the Catálogo.
  - Change `shouldPublishAProductWithAFile` (and every other test in the four publication classes that expects 200) to `insertProductWithFileAndCover`.
- [ ] **Step 2: Run** `cd backend; .\mvnw.cmd test -Dtest='AdminProductPublication*Test'` — the new tests fail on the assertion (409 missing / `errors.cover` missing), not on compilation of unrelated code.
- [ ] **Step 3: Implement.** In `publish`, after `lock(id)`, build a `LinkedHashMap` with `file → FILE_REQUIRED` if `files.currentKey(id).isEmpty()` and `cover → COVER_REQUIRED` if `covers.currentKey(id).isEmpty()` (inject `ProductCovers`); if not empty, record the FAILURE audit independently and throw `ProductNotPublishableException(errors)`. Delete `ProductFileRequiredException` and its handler; the new handler returns `problem(ex.errors())` with status 409. Update the class Javadoc ("Needs a file and a Capa") and the controller Javadoc's 409 line.
- [ ] **Step 4: Run** the same command plus `-Dtest='AdminProductPublication*Test,ProductPublicationMigrationTest,OpenApiEndpointsTest'` — all pass.
- [ ] **Step 5: Commit** `feat: refuse publishing a product without a cover (#187)`.

### Task 2: A published Product cannot lose its Capa

**Files:** `ProductCoverService.java`, `ProductCoverRequiredWhilePublishedException.java` (new), `ApiExceptionHandler.java` (or `ProductCoverController`'s local handler — put it where the other cover errors are mapped), `AdminProductCoverTest.java`, `AdminProductPublicationConcurrencyTest.java`.

**Interfaces:**
- Consumes: `AdminProductFixtures.addCover`, `insertProductWithFileAndCover` (Task 1).
- Produces: `DELETE /api/admin/products/{id}/cover` → `409 {"errors":{"cover":"COVER_REQUIRED_WHILE_PUBLISHED"}}` when the Product is PUBLISHED and has a Capa. Task 4 relies on this code.

- [ ] **Step 1: Failing tests:**
  - `AdminProductCoverTest.shouldRefuseRemovingTheCoverOfAPublishedProduct` — 409 with the code above, cover row still there, one `ADMIN_PRODUCT_COVER_REMOVED` FAILURE audit row, public `GET /api/products/{id}/cover` still 200.
  - `AdminProductCoverTest.shouldRemoveTheCoverOfAnUnpublishedProduct` — UNPUBLISHED → 204, row gone (DRAFT is already covered by existing tests).
  - `AdminProductCoverTest.shouldAnswerNoContentWhenAPublishedProductHasNoCover` — PUBLISHED, no cover → 204 (nothing to remove, as today).
  - Swap on a PUBLISHED Product (in `ProductCoverServiceTest`, with the failure-injection style that class already uses for storage/database errors): `shouldKeepTheOldCoverWhenStoringTheNewOneFails` (storage `put` fails → exception, row still has the old key/version, old object still stored, status PUBLISHED); `shouldKeepTheOldCoverWhenTheSwapTransactionFails` (database fails in the swap → same assertions, new object dropped); `shouldServeTheNewCoverWhenDeletingTheOldObjectFails` (delete of the old object fails → row has the new key, public cover 200, old key left in `pending_object_deletions`). These may pass at once, since `store` already orders the steps; then they are pinning tests, and a reviewer checks they would fail if the old object were deleted before the swap.
  - `AdminProductPublicationConcurrencyTest.shouldNeverEndPublishedWithoutACover` — DRAFT with file and cover; publish and remove-cover start together (same latch pattern already in the class), repeated as the class does; at the end, `status = PUBLISHED` implies a `product_covers` row exists, and exactly one of the two requests got 409.
- [ ] **Step 2: Run** `.\mvnw.cmd test -Dtest='AdminProductCoverTest,AdminProductPublicationConcurrencyTest'` — new tests fail on the assertion.
- [ ] **Step 3: Implement.** In `remove`, inside the transaction after `lockProduct`, when a cover exists and the Product status is `PUBLISHED`, record the FAILURE audit independently and throw `ProductCoverRequiredWhilePublishedException` (the transaction rolls back; nothing is queued for deletion).
- [ ] **Step 4: Run** the step-2 command plus `-Dtest='*Cover*Test'` — all pass.
- [ ] **Step 5: Commit** `feat: keep the cover of a published product (#187)`.

### Task 3: Panel — publication needs a Capa

**Files:** `features/products/api.ts`, `messages.ts`, `ProductPublicationSection.tsx`, `ProductPublicationSection.test.tsx`.

**Interfaces:**
- Consumes: 409 bodies from Task 1.
- Produces: `publication()` throws `ProductValidationError({ file?, cover? })` when the 409 has `errors.file` and/or `errors.cover`; any other 409 stays `ProductConflictError`. `PUBLICATION_MESSAGES.needCover` = T1, `PUBLICATION_ERRORS.coverRequired` = T2.

- [ ] **Step 1: Failing tests** in `ProductPublicationSection.test.tsx`:
  - Rascunho with file and no cover → "Publicar" disabled and T1 visible; with neither → T1 and "Envie o Arquivo do produto para poder publicar." both visible.
  - 409 `{errors:{cover:"COVER_REQUIRED"}}` → alert shows T2.
  - 409 with both codes → alert shows T2 and "Envie o Arquivo do produto antes de publicar.".
  - Despublicado without cover → "Publicar de novo" disabled with T1.
  - Existing tests that publish successfully get a product with a cover.
- [ ] **Step 2: Run** `cd frontend; pnpm vitest run src/features/products/ProductPublicationSection.test.tsx` — new tests fail.
- [ ] **Step 3: Implement.** `hasCover = product.cover !== null`; button disabled when `!hasFile || !hasCover`; hints and errors render one line per missing item (file first). `publication()` reads `errors` once and keeps only the known string fields.
- [ ] **Step 4: Run** the same command — pass.
- [ ] **Step 5: Commit** `feat: panel blocks publishing without a cover (#187)`.

### Task 4: Panel — Capa section while published

**Files:** `features/products/api.ts`, `messages.ts`, `ProductCoverSection.tsx`, `ProductCoverSection.test.tsx`, `ProductFormPage.tsx` (only to pass `status` if the section does not get the whole Product).

**Interfaces:**
- Consumes: 409 code from Task 2.
- Produces: `removeProductCover` throws `ProductCoverRequiredWhilePublishedError` on that 409. `COVER_TEXTS.removeBlocked` = T3, `COVER_TEXTS.publishedWithoutCover` = T5, `COVER_TEXTS.confirmQuestion` = T6, `COVER_ERRORS.requiredWhilePublished` = T4.

- [ ] **Step 1: Failing tests** in `ProductCoverSection.test.tsx`:
  - Publicado with cover → no "Remover capa" button, T3 visible, "Enviar capa" still there.
  - Publicado without cover → T5 visible.
  - Rascunho with cover → "Remover capa" asks T6.
  - Rascunho with cover, DELETE answers 409 `COVER_REQUIRED_WHILE_PUBLISHED` → alert shows T4 and the Product query is invalidated (re-fetched).
- [ ] **Step 2: Run** `pnpm vitest run src/features/products/ProductCoverSection.test.tsx` — new tests fail.
- [ ] **Step 3: Implement** with the existing alert/notice components of the section; no new component.
- [ ] **Step 4: Run** `pnpm vitest run src/features/products` — pass.
- [ ] **Step 5: Commit** `feat: panel keeps the cover of a published product (#187)`.

### Task 5: E2E, glossary and ADR

**Files:** `frontend/e2e/admin-panel.e2e.ts`, e2e cover helper (extract the upload already done at line ~961 into a function next to `e2e/image.ts`), `GLOSSARY.md`, `docs/adr/0007-modelo-comercial-da-fase-1.md`.

- [ ] **Step 1:** Tests at lines ~743, 1076, 1316, 1668, 1886, 2065 upload a Capa with the helper before "Confirmar publicação". In the FR-011 test (~743), before the Capa: "Publicar" disabled with T1 (`FR-011` already in its title). In the test at ~920 (no assertion is removed, only moved or strengthened): while Rascunho, it still removes the Capa through "Remover capa" + T6 confirmation and checks it is gone, then sends it again; after publishing, "Remover capa" is absent and T3 visible, and a direct `DELETE /api/admin/products/{id}/cover` from the page's session (through the page's request context with the CSRF token, as the suite already does for API calls) answers 409 `COVER_REQUIRED_WHILE_PUBLISHED` while the public Capa still answers 200; sending another image changes the public Capa (new `version`, 200); after unpublishing, removing the Capa works and the public Capa is 404 as before.
- [ ] **Step 2:** Glossary "Publicar": "Só é possível com Arquivo do produto e Capa enviados." "Capa": add "Obrigatória para publicar; um Produto publicado não pode ficar sem ela (pode trocá-la)." ADR 0007, bullet "Capa pública (#21)": add one sentence — Capa obrigatória para publicar desde #187; Produtos já publicados sem Capa continuam publicados (o 404 da capa pública continua valendo para eles).
- [ ] **Step 3: Run** `just db`, `just dev-back`, `just dev-front` (main folder) then `just e2e` — the admin-panel suite passes.
- [ ] **Step 4: Commit** `test: e2e publishes with a cover; docs: cover required to publish (#187)`.

### Fim
- `/verificar-tela` on `/painel/produtos/:id` (Rascunho sem capa, Publicado com capa, Publicado sem capa).
- `just check-rapido`, verificar-entrega, `/code-review`; push and PR (`Closes #187`, cite FR-011) approved by Sérgio on 18/11; merge stays with Sérgio.

Production is not live yet, so there are no real Products published without a Capa to list; the panel notice (T5) covers any that appear before go-live.
