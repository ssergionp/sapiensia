# #192 Sign-out destination, header on session error, panel buttons (+ #187 leftovers) Implementation Plan

> **For agentic workers:** LEVE flow: implement directly, no subagents. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Fix the three follow-ups of #192 and the two points left out of #187, in one PR (`Closes #192`).

**Architecture:** Frontend only. No API, no migration, no new user-visible text.

**Tech Stack:** React, TanStack Query, React Router, Tailwind, Vitest + Testing Library.

**Spec:** issue #192 and `docs/superpowers/plans/2026-11-18-187-capa-obrigatoria.md` (texts T1, T3, T4, T5).

## Textos que o usuário vê

Nenhum texto novo. Todos os textos já existem ("Entrar", "Sair", T1, `needFile`, T3, T4, T5).

## Global Constraints

- Nothing in the backend, e2e API calls or `messages.ts` changes.
- Customer buttons (orders, library) keep their current size; only the admin panel gets 44 px.

## Review Focus

1. **Sign-out from `/pedidos`, `/biblioteca`, `/pedidos/:id`**: lands on `/`, and the next sign-in does not return to the old page (the page must not reach `rememberReturnTo`). Test in Task 1.
2. **Session request fails (5xx / network)**: header shows "Entrar" and not "Sair"; on `/entrar` it still hides "Entrar". Test in Task 1.
3. **Product without file and without cover**: the disabled "Publicar" points at both hints. Test in Task 3.

## Tasks

### Task 1: Header (`SiteHeader.tsx`, `features/session/hooks.ts`)

- [ ] Tests in the existing SiteHeader test file (create `SiteHeader.test.tsx` if none): (a) signed in on `/pedidos`, click "Sair" → location becomes `/` and `/entrar` was never visited; (b) session query answers 500 → "Entrar" link visible, no "Sair"; (c) same on `/entrar` → no "Entrar".
- [ ] Run them, confirm RED by assertion.
- [ ] `useLogout(onSignedOut?: () => void)`: `onSuccess` calls `onSignedOut?.()` **before** `invalidateQueries`, so the page unmounts before the session refetch makes it redirect to `/entrar`. `SiteHeader` passes `() => navigate("/")`.
- [ ] `showSignIn = (session.data === null || session.isError) && pathname !== "/entrar"`.
- [ ] Run tests, GREEN. Commit `fix: send customers home after sign-out and show Entrar on session error (#192)`.

### Task 2: Panel buttons at 44 px (`features/admin/AdminGuard.tsx`)

- [ ] Wrap the guard's final output (`AdminNav` + children) in `<div className="[&_[data-slot=button]]:h-11">`. One rule covers the ~30 buttons (cards, pagination, confirmations); the pages that already use `h-11` are unchanged. Chosen over adding a size to ~30 call sites (less code, no site forgotten later).
- [ ] Test in `AdminNav`/guard tests: the wrapper carries the class (jsdom has no Tailwind, so the real height is measured in the browser, Task 4).
- [ ] Commit `fix: make admin panel buttons 44 px (#192)`.

### Task 3: #187 leftovers

- [ ] `ProductPublicationSection.tsx`: give the `needFile`/`needCover` hints ids (`publish-need-file`, `publish-need-cover`); "Publicar" gets `aria-describedby` with the ids of the hints shown (none when no hint). Test: no cover → `getByRole("button", {name: "Publicar"})` has description T1; no file and no cover → both.
- [ ] `ProductCoverSection.tsx`: the T3/T5 paragraph gets `id="cover-publication-note"`; the file input's `aria-describedby` includes it while published. Test: published with cover → input description contains T3; published without cover → T5.
- [ ] `ProductCoverSection.tsx`: the 409 on removal (T4) becomes a section-level alert (`role="alert"`, after the form, no id linked to the input); the input gets no `aria-invalid` for it. Other cover errors stay as field errors. Test: DELETE answers 409 → T4 alert is visible, input is not `aria-invalid`, input description has no T4.
- [ ] Commit `fix: link publication hints and show cover removal 409 as section notice (#187)`.

### Task 4: Verification

- [ ] `just check-rapido`.
- [ ] `/verificar-tela` on the product screen (published with and without cover) and admin products list; measure admin button heights = 44 px in desktop and phone width.
- [ ] Real system (`just db`, `just dev-back`, `just dev-front`): sign out from `/pedidos` → `/`; sign in again → no return to `/pedidos`.
- [ ] `verificar-entrega`, one `/code-review`, push, PR with `Closes #192`.
