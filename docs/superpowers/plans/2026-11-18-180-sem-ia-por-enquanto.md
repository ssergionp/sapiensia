# #180 Sem IA na plataforma por enquanto — Plan (LEVE)

**Goal:** Documentation only. Record that AI content creation (Fase 2) and Escala (Fase 4) are postponed; the platform only publishes and sells ready-made material. No code changes.

## Changes

1. New `docs/adr/0008-sem-ia-na-plataforma-por-enquanto.md` (decision, reason, Fases 2 and 4 as backlog, Fase 2 may return as a new decision).
2. `docs/spec/sapiensia-spec.md`: mark as "(adiado, ADR-0008)" without deleting — section 15 (Fases 2 and 4), FR-001..006, FR-012..015 (AI ones), SEC-051..064 (6.6), flow 9.1, 9.4 (atendimento por IA), AI items in the release gate (10.2), risks (16) and the AI-only entities (8); short note at the top of 5.1 and 6.6.
3. `CLAUDE.md` and `README.md`: description becomes "publicar e vender e-books, cartilhas, tutoriais e checklists prontos".
4. `GLOSSARY.md`: no AI-only terms; no change.

## Tests and risks

- No behavior change, so no test. Check: `just check-rapido` (hook), `git diff --stat` shows only `docs/`, `README.md`, `CLAUDE.md`.
- Risk: a test or doc citing a requirement ID that I mark as postponed. Check with `git grep -n "FR-00[1-7]\|FR-01[245]\|SEC-05[1-9]\|SEC-06[0-4]"` outside the spec.

## Texts the user sees

None (no screen or message changes). The only product text is the README/CLAUDE.md description, which is not shown to Clientes.

## Needs the owner's decision

None: the wording follows the issue (#180) and its comment.
