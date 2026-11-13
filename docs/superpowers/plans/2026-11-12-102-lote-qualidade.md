# #102 Lote de qualidade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the six items of #102 (flaky test, Vite reloads during local e2e, false alarm in the `verificar-entrega` script, three workflow lessons) without changing any system behavior.

**Architecture:** One task per item that has code (1, 2, 3), one task for the three text lessons (4, 5, 6), one task for the template. Each code item starts with a reproduction that fails before the change.

**Tech Stack:** JUnit 5 (backend test helper), Vite 8.3.4 `server.watch` (chokidar), Node 24 `node:test` (built-in, no new dependency), Markdown.

**Spec:** issue #102 (Refs #67, #76, #20, #7, #100). No FR-/SEC-/NFR- IDs: #102 does not touch requirements.

## Global Constraints

- Branch `fix/102-lote-qualidade`, worktree `.claude/worktrees/fix-102-lote-qualidade`, from origin/main d7b7d35.
- No test loosened: no test case or assertion removed, nothing disabled. `AdminMfaBypassTest` is not edited.
- No new dependency. No text the user of the system sees changes (no screen, no API message).
- One heavy command at a time (`just check-resumo`, `just e2e`, whole Maven suite). Focused tests during the tasks.
- Before `just check-resumo` and before `just e2e`: free memory and free disk ≥ 2 GB (`Get-CimInstance Win32_OperatingSystem` and `Get-PSDrive C`). Below that, stop and tell Sérgio.
- e2e from the worktree uses the database of the main folder (`just db` in `C:\Projetos_IA\code_Superpowers\sapiensia`).
- Push only `git push -u origin fix/102-lote-qualidade`, after Sérgio's OK to the plan and to the implementation.

## Review Focus

1. Helper given three equal codes (not only two): must still return a code outside them. Covered in Task 1.
2. `.tmp/` ignore must not stop Vite from reloading real source edits (`src/`). Covered in Task 2, step 5.
3. The script must keep flagging an ID that no test cites, also when an e2e file exists in the branch. Covered in Task 3.
4. An e2e file changed in the branch now also goes through section 3 (loosened tests): a `test.skip(` added there must be flagged. Covered in Task 3.
5. The clipboard check in the runbooks must not print the secret on screen. Covered in Task 4 (decision D2).

---

### Task 1: `AdminMfaTestSupport.wrongCodeAt` survives equal codes (#76)

**Cause (read in the code):** `wrongCodeAt` builds `Set.of(code(step-1), code(step), code(step+1))`. `Set.of` throws `IllegalArgumentException: duplicate element` when two of the three TOTP codes coincide (about 3 in 10⁶ runs), so `AdminMfaBypassTest` fails at random.

**Files:**
- Modify: `backend/src/test/java/br/com/sergio/sapiensia/admin/AdminMfaTestSupport.java` (method `wrongCodeAt`)
- Create: `backend/src/test/java/br/com/sergio/sapiensia/admin/AdminMfaTestSupportTest.java`

**Interfaces:**
- Produces: `static String firstCodeNotIn(String... accepted)` (package-private, in `AdminMfaTestSupport`): the first 6-digit code, counting from `000000`, that is not in `accepted`. `wrongCodeAt` keeps its signature and calls it with the three codes.

Why a new small method instead of a real colliding secret: a real collision needs a search over secrets and a magic secret in the test; passing the codes directly makes the test exact and readable.

- [ ] **Step 1: Write the failing test** (plain JUnit, no Spring)
  - `firstCodeNotInAcceptsTwoEqualCodes`: `firstCodeNotIn("000000", "000000", "000001")` returns `"000002"`.
  - `firstCodeNotInAcceptsThreeEqualCodes`: `firstCodeNotIn("000000", "000000", "000000")` returns `"000001"`.
  - `wrongCodeAtIsNoneOfTheThreeAcceptedCodes`: with `Totp.newSecret()` and `Instant.now()`, the result is not equal to `codeAt` of `at - 30s`, `at`, `at + 30s`, and matches `\d{6}`.
- [ ] **Step 2: Run it red.** First add `firstCodeNotIn` still using `Set.of(accepted)` and make `wrongCodeAt` call it (pure move, same behavior). Run `cd backend; .\mvnw.cmd test -Dtest=AdminMfaTestSupportTest`. Expected: the two equal-code tests FAIL with `IllegalArgumentException: duplicate element`.
- [ ] **Step 3: Fix:** `new HashSet<>(Arrays.asList(accepted))` instead of `Set.of`.
- [ ] **Step 4: Run green:** `.\mvnw.cmd test -Dtest=AdminMfaTestSupportTest+AdminMfaBypassTest` (and every class that calls `wrongCodeAt`, found with `git grep -l wrongCodeAt -- backend/src/test`). Expected: all PASS.
- [ ] **Step 5: Commit** `test: tolerate equal TOTP codes in wrongCodeAt (#76)`.

### Task 2: Vite stops watching `.tmp/` (#67)

**Cause (from #67, confirmed there):** Playwright writes trace resources (`.html`) to `frontend/.tmp/e2e-results/...`; Vite watches them and reloads every open page (58 reloads in one run).

**Files:**
- Modify: `frontend/vite.config.ts` (`server`)

Checked in Vite 8.3.4 (`node_modules/vite/dist/node/chunks/node.js`): the `server.watch.ignored` patterns are appended to Vite's defaults (`.git`, `node_modules`, `test-results`, cache dir), so nothing is lost.

- [ ] **Step 1: Reproduce red (light, no e2e):** run `just dev-front` in the background with output in `.tmp/vite-102.log`, open `http://localhost:5173` in Playwright's Chromium, write `frontend/.tmp/repro-102/x.html`. Expected: the log shows `page reload .tmp/repro-102/x.html`.
- [ ] **Step 2: Change:** `server.watch.ignored: ["**/.tmp/**"]` with a one-line comment pointing to #67.
- [ ] **Step 3: Same reproduction green:** restart dev-front, write the file again. Expected: no `page reload` line for it.
- [ ] **Step 4: Proof in `just e2e`:** yes, it can be proved. With db (main folder), `just dev-back` and `just dev-front` (output to `.tmp/vite-102.log`), run `just e2e` with the default config (trace `retain-on-failure`, the configuration that gave 58 reloads in #67). Expected: e2e green **and** `Select-String "page reload" .tmp/vite-102.log` returns nothing. Limit: cause 2 of #67 (cold system, first signups over 15 s) is not fixed here; if a test fails with no reload in the log, it is reported as is, not retried until green.
- [ ] **Step 5: Real edits still reload:** with dev-front running, touch a file in `frontend/src/` and see `hmr update` or `page reload src/...` in the log, then revert it.
- [ ] **Step 6: Commit** `fix: stop Vite from watching .tmp during local e2e (#67)`.

### Task 3: `verificar-entrega` sees FR-/SEC- in e2e test titles (#20)

**Cause (read in the code):** `conferir.mjs` line 71, `ehTeste`, matches only `src/test/` and `*.test|spec.[jt]sx?`. Files `frontend/e2e/*.e2e.ts` are not tests for the script, so an ID cited only in an e2e title shows `REQUISITO SEM TESTE`.

**Files:**
- Modify: `.claude/skills/verificar-entrega/conferir.mjs:71`
- Create: `.claude/skills/verificar-entrega/conferir.test.mjs`

**Interfaces:**
- Produces: `ehTeste(f)` also true for `/\.e2e\.[jt]sx?$/`. Consequence wanted: e2e files changed in the branch also go through section 3 (loosened tests).

- [ ] **Step 1: Write the failing test** with `node:test` (built-in). Each case creates a throwaway git repo in `os.tmpdir()`: commit on `main`, branch `fix/1-x`, then a plan `docs/superpowers/plans/p.md` citing the IDs, then runs `node <abs path>/conferir.mjs` with that repo as cwd and checks stdout.
  - `recognizes an ID cited only in an e2e test title`: plan cites `FR-001`; `frontend/e2e/a.e2e.ts` has `test("FR-001: abre o catálogo", ...)`. Expected stdout contains `OK: FR-001 citado num teste desta branch`.
  - `still flags an ID no test cites`: plan cites `FR-001` and `SEC-002`; e2e cites only `FR-001`. Expected `REQUISITO SEM TESTE: SEC-002`.
  - `flags a skip added to an e2e file`: e2e file committed on main, then `test.skip(` added in the branch. Expected `TESTE DESLIGADO OU ISOLADO`.
- [ ] **Step 2: Run red:** `node --test .claude/skills/verificar-entrega/conferir.test.mjs`. Expected: cases 1 and 3 FAIL, case 2 PASS.
- [ ] **Step 3: Change `ehTeste`.**
- [ ] **Step 4: Run green:** same command, 3/3 PASS.
- [ ] **Step 5: Commit** `fix: count e2e test titles in verificar-entrega requirement check (#20)`.

Not wired into `just check` (decision D3).

### Task 4: Lessons in `CLAUDE.md` and runbooks (items 4, 5, 6)

**Files:**
- Modify: `CLAUDE.md` (section "Armadilhas conhecidas", two new lines at the end)
- Modify: `docs/runbooks/homologacao-fly.md` (steps 8 to 13b and the intro line above step 7)
- Modify: `docs/runbooks/webhook-asaas.md` (step 2)

Exact text (Portuguese, the language of these files; generic so it also fits the template):

- Item 4, `CLAUDE.md`:
  `- Valor falso que imita segredo (\`sb_secret_...\`, token \`eyJ...\`) é acusado pelo gitleaks do CI: nos testes, monte-o em tempo de execução (\`"sb_" + "secret_" + ...\`), nunca como literal.`
- Item 5, `CLAUDE.md`:
  `- Teste focado (\`-Dtest=...\`) de código que escreve em log ou cumpre um requisito com ID: inclua também as classes que já conferem esse log ou citam esse ID (\`git grep -l SEC-047 -- backend/src/test\`). Uma checagem de log em outra classe já escapou de um filtro só com o nome da classe mudada.`
- Item 6, runbooks: before each `fly secrets set ...="$(Get-Clipboard)"`, a check line and its one-sentence explanation. Form depends on D2. Recommended form (prints only True/False or a length, never part of the value):

  | Secret | Check | Expected |
  |---|---|---|
  | `APP_STORAGE_SUPABASE_SECRET_KEY` | `(Get-Clipboard).StartsWith("sb_secret_")` | `True` |
  | `APP_STORAGE_SUPABASE_URL` | `(Get-Clipboard).StartsWith("https://")` | `True` |
  | `DB_URL` | `(Get-Clipboard).StartsWith("jdbc:postgresql://")` | `True` |
  | `DB_USER` | `(Get-Clipboard).StartsWith("postgres.")` | `True` |
  | `RESEND_API_KEY` | `(Get-Clipboard).StartsWith("re_")` | `True` |
  | `APP_ADMIN_EMAIL` | `(Get-Clipboard).Contains("@")` | `True` |
  | `APP_MFA_ENCRYPTION_KEY` | `(Get-Clipboard).Length` | `44` |
  | `DB_PASSWORD` | `(Get-Clipboard).Length` | the length saved in Bitwarden, not 0 |
  | `APP_ASAAS_WEBHOOK_TOKEN` | `(Get-Clipboard).Length` | `64` |

  Plus one line in the intro: "Se der `False` ou outro tamanho, a área de transferência tem outra coisa: copie de novo antes de gravar."

- [ ] **Step 1:** Write the texts above.
- [ ] **Step 2:** `just lint` (Biome does not read `.md`; this only confirms nothing else broke) and read the diff.
- [ ] **Step 3: Commit** `docs: record gitleaks, focused-test and clipboard lessons (#102)`.

### Task 5: Full verification on the branch

- [ ] Memory and disk ≥ 2 GB, then `just check-resumo`. Expected green.
- [ ] Memory and disk ≥ 2 GB, then Task 2 step 4 (`just e2e` + reload count).
- [ ] Skill `verificar-entrega`, table shown to Sérgio.
- [ ] Stop for Sérgio's OK to the implementation; then `git push -u origin fix/102-lote-qualidade` and PR with `Closes #102`.

### Task 6: Template `java-react-template`

Checked today: the template is at `C:\Projetos_IA\code_Superpowers\java-react-template`, clean, on `main` in sync with origin (771a975).

**How:** `/levar-ao-modelo` is a skill only Sérgio can start (`disable-model-invocation: true`). After the merge, Sérgio types `/levar-ao-modelo #102` in the main folder (not in the worktree, see below). Its commit goes straight to the template's `main` with Sérgio's OK, as the skill says.

What it covers and what it does not:

| Change | File in template | Covered by /levar-ao-modelo? |
|---|---|---|
| `server.watch.ignored` | `frontend/vite.config.ts` (today differs only in port 8080/8081) | yes |
| Items 4 and 5 | `CLAUDE.md`, "Armadilhas conhecidas" (today identical to SapiensIA) | yes |
| `ehTeste` + `conferir.test.mjs` | `.claude/skills/verificar-entrega/` | **no**: `.claude/skills/` is not in the skill's table |
| Runbook clipboard check | none: the template has no `docs/runbooks/`, and `docs/` is excluded by the skill | **no** |

Findings that block "do it exactly as the issue says":
- The template's `conferir.mjs` does **not** have the requirement-ID section at all (section 4 exists only in SapiensIA). Fixing `ehTeste` there helps only section 3.
- The template CI has no gitleaks step, so item 4 there would say "gitleaks do CI" about a tool it does not run.
- `/levar-ao-modelo` looks for the template at `../java-react-template`, relative to the current folder. From the worktree that resolves to `.claude/worktrees/java-react-template`, which does not exist. It has to run from the main folder, after the merge (when the main folder has the changes).
- #102 says "do it first in the template and bring it with /levar-ao-modelo", but that skill goes the other way (project → template). The plan does it in SapiensIA first and then takes it to the template.

## Decisions for Sérgio

- **D1 Template timing and scope.** Recommended: after the merge of this PR, Sérgio runs `/levar-ao-modelo` in the main folder for `vite.config.ts` and the two `CLAUDE.md` lines; in the same template commit, with OK, I also carry the whole `conferir.mjs` (section 4 included) and `conferir.test.mjs` by hand, since the skill does not cover `.claude/skills/`. Runbook item stays out of the template (no runbooks there). Item 4 in the template drops the word "gitleaks" and says "o scanner de segredos". Alternatives: only `ehTeste` in the template; or skip the script there.
- **D2 Clipboard check.** You asked for `(Get-Clipboard).Substring(0,10)`. For `sb_secret_` it shows exactly the public prefix, fine. For the other secrets it would print 10 real characters of the secret on the screen (and in the terminal scrollback). Recommended: the table in Task 4 (`StartsWith`/`Length`, nothing of the value shown). Alternative: `Substring(0,10)` only on the Supabase key, and no check on the others.
- **D3 Script test in `just check`.** Recommended: no; `conferir.test.mjs` runs by hand (`node --test ...`) when the script changes. Wiring it into `just check` changes the `justfile` and CI of both repos for one small script.

## Not covered / risks

- Cause 2 of #67 (slow first signups on a cold system) stays open; a timeout there can still fail `just e2e` once.
- The 3-in-a-million collision itself is not reproduced with a real secret; it is reproduced by passing equal codes to the helper (Task 1).
- No user-facing text changes, so there is no list of screen texts.
