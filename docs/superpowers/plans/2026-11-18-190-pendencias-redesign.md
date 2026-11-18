# #190 Pendências do redesign (#186) — Plan (LEVE)

**Goal:** Close the leftover items of #190 in one PR, except "Página inicial sem produto à vista" (stays in the issue until there are real Produtos). Frontend only; no API, no migration, no new dependency.

**Requirements:** FR-033, NFR-005 (keyboard, contrast, WCAG 2.2 AA). Cite them in the test titles of items 1 and 5.

## Items (each one starts with a failing test)

1. **Skip link** — `App.tsx`: first element of the page is `<a href="#conteudo">Pular para o conteúdo</a>`, `sr-only` until focused. The `div.flex-1` that wraps `<Routes>` gets `id="conteudo"` and `tabIndex={-1}`, so one target serves every screen (no id in the ~25 `<main>`s).
   Test (`App.test.tsx`): first Tab stop is the link; its `href` is `#conteudo` and the element with that id exists; the link is not read as part of the nav.
2. **Entrar hierarchy** — `LoginPage.tsx`: "Esqueci minha senha" becomes a quiet link right under the password field (before the submit button); "Ainda não tem conta? Criar conta" moves below the form, after a separator, as an outline button. Same texts, only position and weight change.
   Test (`LoginPage.test.tsx`): in tab order "Esqueci minha senha" comes before "Entrar" (button), "Criar conta" after it.
3. **Reserve cover cuts long titles** — `CoverImage.tsx`: drop `before:line-clamp-5` so the title shows whole, like on the card; `overflow-wrap:anywhere` stays.
   Test (`CoverImage.test.tsx`): the fallback has no `line-clamp` class. jsdom has no layout, so this is weak: the real proof is a long title in the browser (`/verificar-tela`, desktop and phone), checking nothing is cut.
4. **Header with an expired session** — `lib/api.ts`: `apiFetch` calls a registered listener on a 401 from any path except `/api/sessions*` and `/api/admin*` (login failure and the session query itself are 401 by design). `SiteHeader` (customer navigation) registers it and invalidates the `session` query, so the header refetches and drops "Sair" and the account links.
   Tests: `api.test.ts` (401 on `/api/library` calls the listener; 401 on `/api/sessions` does not); `SiteHeader.test.tsx` (signed in, then a 401 from `/api/library` → "Entrar" shows, "Sair" is gone).
5. **Fixed colors** — only `CoverImage.tsx` still has hex (8 reserve colors + `#FAF6F0` text). The state badges (`StatusBadge`) and the payment icon (`OrderPage`) already use tokens, so nothing to do there. New tokens `--cover-finance` … `--cover-other` and `--cover-foreground` in `index.css` (`:root` and `.dark`, same values: covers are dark in both themes), used by `CoverImage`.
   Tests: `theme-tokens.test.ts` (every cover token exists in both blocks and has contrast ≥ 4.5 with `--cover-foreground`); `CoverImage.test.tsx` (no hex literal in the fallback style, uses `var(--cover-…)`).
6. **Hero depends on "para usar."** — move `splitTagline` to `brandTexts.ts`, exported; the highlight becomes the last two words of the tagline, so any new tagline works and today's render is unchanged ("para usar.").
   Test (`brandTexts.test.ts`, new): `splitTagline("Aprenda no seu ritmo agora")` → `["Aprenda no seu ", "ritmo agora"]`; today's tagline → highlight `"para usar."`; one-word tagline → no highlight, no crash.

## Order and commits

One commit per item (`fix:`/`feat:`/`refactor:`, in English, `(#190)`), test first and seen failing by assertion. Then `just check-rapido`, `/verificar-tela` (home, Entrar, Catálogo with a long title, Biblioteca), one `/code-review`, `verificar-entrega`.

## Texts the user sees

- New: **"Pular para o conteúdo"** (approved), visible only on keyboard focus.
- No other text changes (item 2 moves existing texts; item 6 keeps the hero as is).

## Risks

- Item 4: any other customer endpoint answering 401 for a reason other than an ended session would refresh the session; the refetch is the authority, so the worst case is one extra `GET /api/sessions/current`.
- Item 1: focus on a `div` with `tabIndex=-1` must not show an outline; check in the browser.
- Item 3: a very long title makes the cover taller than 2:3 instead of cutting. Say so if it looks bad in the card grid.

## Needs the owner's decision

- **Item 2 is a visual choice** (the issue says "avaliar"). Proposal above; if you prefer another hierarchy, say so before I write code.
- **PR says `Refs #190`, not `Closes #190`**, because the issue stays open for the home-page item. After the merge I edit the issue body so only that item is left.
