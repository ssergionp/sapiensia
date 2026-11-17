# #101 — Atualizar as dependências do frontend em etapas (no lugar do PR #90)

Origem: PR #90 do Dependabot (14 pacotes de uma vez). Refs #90, #91.

Quatro etapas, **uma PR cada**, nesta ordem, cada uma a partir da `origin/main` mais nova. Nenhuma etapa mistura versão menor com versão maior. Nenhum teste existente é alterado para afrouxar; se uma versão nova mudar um comportamento visível, a etapa para e volta para decisão. Sem migration, nenhum texto novo.

## Task 1 (PR 1): versões menores e o Biome

Branch `chore/101-frontend-deps-minor`. Arquivos: `frontend/package.json`, `frontend/pnpm-lock.yaml`, `frontend/biome.json` e o que o `just fmt` reformatar (`frontend/README.md`) e `frontend/src/features/products/ProductFormPage.tsx` (renomeia a função local `describe` para `fieldDescribedBy`, falso positivo da regra nova `usePlaywrightValidDescribeCallback` do Biome 2.6).

Versões (as do PR #90, menos as duas maiores):

| Pacote | De | Para |
|---|---|---|
| `@tailwindcss/vite`, `tailwindcss` | ^4.3.3 | ^4.3.4 |
| `@tanstack/react-query` | ^5.104.5 | ^5.105.3 |
| `cn` | ^0.5.0 | ^0.5.2 |
| `lucide-react` | ^1.60.0 | ^1.68.0 |
| `react-hook-form` | ^7.90.0 | ^7.91.0 |
| `react-router` | ^8.5.0 | ^8.5.1 |
| `@biomejs/biome` | 2.5.15 | 2.6.5 |
| `@vitejs/plugin-react` | ^6.1.2 | ^6.2.1 |
| `shadcn` | 4.22.1 | 4.23.0 |
| `vite` | ^8.3.4 | ^8.3.5 |
| `vitest` | ^5.0.5 | ^5.1.0 |

Passos: editar o `package.json`, `pnpm install`, `pnpm exec biome migrate --write` (atualiza o `$schema` do `biome.json`), `just fmt`, e conferir `just lint`, `tsc -b`, os testes do frontend e o build.

## Task 2 (PR 2): TypeScript 7, sozinho

Branch `chore/101-typescript-7`. `typescript` ~6.0.3 → ~7.0.2. Conferir `tsc -b`, os testes e o build. Erros de tipo novos são corrigidos no mesmo PR, sem `any` e sem `@ts-ignore`.

## Task 3 (PR 3): `@hookform/resolvers` 7, sozinho

Branch `chore/101-hookform-resolvers-7`. ^6.0.0 → ^7.0.2. Conferir a mudança de API na documentação oficial (notas da versão 7) antes de mexer; conferir todos os formulários (cadastro, login, recuperação e troca de senha, CPF, painel) com os testes; o CI roda o e2e.

## Task 4 (PR 4): Dependabot não agrupa versões maiores

Branch `chore/101-dependabot-majors`. Em `.github/dependabot.yml`, cada grupo (`backend`, `frontend`, `github-actions`) passa a ter `update-types: ["minor", "patch"]`: as versões maiores saem do grupo e chegam em PR própria.

## Decisões

| ID | Decisão | Marca |
|----|---------|-------|
| D1 | A regra de não agrupar versões maiores vale para os três ecossistemas (Maven, npm e GitHub Actions), não só para o npm | DECIDIDO NO MODO CONTÍNUO — conferir |
| D2 | O PR #90 **não** é fechado por mim (combinado: não mexer nas PRs do Dependabot). A última PR do #101 avisa que o #90 pode ser fechado com link para esta issue | DECIDIDO NO MODO CONTÍNUO — conferir |
| D3 | As etapas 2 e 3 só começam depois do merge da anterior, para cada uma partir da main com a etapa anterior | DECIDIDO NO MODO CONTÍNUO — conferir |

## Textos que o usuário vê

Nenhum.

## Riscos

- Uma versão nova pode mudar a saída visual (Tailwind, lucide). Os testes de tela e o e2e (com o axe) do CI cobrem; um teste que quebre por comportamento visível faz a etapa parar e voltar para decisão.
- `pnpm install` precisa de rede; o `pnpm-lock.yaml` muda bastante (é esperado).

## Verificação (cada etapa)

`just lint`, `pnpm exec tsc -b`, `pnpm exec vitest run`, `pnpm build`, `just check-rapido` (se morrer por memória, vale o CI); o CI roda `just check` e o e2e.
