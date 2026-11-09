# Administrador cadastra Produto (#17) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** Plano aprovado com ajustes (decisões 1–5 e ajustes A–C, incorporados abaixo). Só o BACKEND (Tasks 1 a 3) está liberado. As telas (Task 4) só começam depois de aprovados os textos da seção 4, que ainda precisam ser refeitos para a categoria por tema e para a troca de preço separada. Sem push, PR nem merge sem OK expresso.

**Goal:** O Administrador cria, lista e edita Produtos em rascunho (título, descrição, categoria por tema e preço em reais, guardado em centavos). Criar e editar texto exige `PRODUCTS_EDIT`; trocar o preço depois da criação exige `PRODUCTS_PRICE`. Tudo com auditoria e dados fictícios só no perfil local.

**Architecture:** Pacote novo `product` no backend, com `JdbcClient` e `UPDATE` condicional para a concorrência. Cinco rotas em `/api/admin/products`, quatro com `panel(Capability.PRODUCTS_EDIT)` e a troca de preço com `panel(Capability.PRODUCTS_PRICE)` na `SecurityConfig`. Auditoria pelo `AuditLog` já existente. No frontend, `src/features/products/` com lista e formulário.

**Tech Stack:** Spring Boot 4.1 / Spring Security 7.1, JdbcClient, Flyway, React + TanStack Query + React Hook Form + Zod, Vitest, Playwright. Sem dependência nova.

**Spec:** `docs/spec/sapiensia-spec.md`; issue #17 (pai #5); `docs/adr/0004-identidade-e-mfa.md`; `docs/adr/0006-meta-de-seguranca-asvs-nivel-2.md`; `docs/adr/0007-modelo-comercial-da-fase-1.md`; `GLOSSARY.md`.

## IDs cobertos

| ID | Como | Onde |
|---|---|---|
| FR-016 | Título, descrição, categoria (tema, lista fechada), preço e estado. Formato, tags, idioma, público e nível ficam fora do #17: comentário no #20 | Tasks 1 a 4 |
| FR-007 | Só o vocabulário: a categoria do Produto é tema (Saúde, Direito, Finanças, Segurança...). A política de revisão por categoria não é deste ticket | D6 |
| FR-018 | Preço em centavos, em BRL. **Parcial de propósito:** moeda, mercado, vigência e arredondamento não entram, porque a ADR-0007 fixa preço único em reais e o #5 manda a ADR valer sobre a spec | D2 |
| NFR-012 | Seed só com dados fictícios, só no perfil `local` | D8, Task 1 |
| SEC-010 | Rotas negadas por padrão; capacidade e os dois fatores verificados no servidor | D3, Task 2 |
| SEC-012 | `id`, `status` e preço calculado nunca vêm do navegador; campos extras são ignorados | Task 2 |
| SEC-013, SEC-015, SEC-020 | Tamanho, tipo e semântica; página de tamanho fixo; métodos e `Content-Type` restritos | D6, Task 2 |
| SEC-017 | Token CSRF exigido em `POST` e `PUT` | Task 2 |
| SEC-018 | Erros sem detalhe interno | Task 2 |
| SEC-065 | Ator, ação, objeto, resultado, hora, origem e correlation ID | D5, Tasks 2 e 3 |
| SEC-029 | Alteração de preço separada da edição: capacidade `PRODUCTS_PRICE`, rota e ação de auditoria próprias | D3, D5 |
| SEC-008 | **Não se aplica aqui:** a ADR-0004 exige reautenticação só para trocar o Arquivo do produto (#22). Ver Riscos | D3 |
| SEC-011 | Produto não tem dono: a resposta da rota negada é igual exista o Produto ou não. Ver D3 | Task 2 |
| ADR-0004 | Capacidade própria no enum | D3 |
| ADR-0007 | "Mudar o preço afeta só Pedidos novos": a regra já está na ADR e não é duplicada; o teste fica cobrado no #24 | D2 |

## Global Constraints

- Todo endpoint começa com `/api`; rota nova nasce negada.
- Código, commits e comentários em inglês; textos da tela em pt-BR, só os da seção 4.
- Nenhuma dependência nova.
- Controller e repositório package-private; DTOs como `record`.
- Toda classe de teste termina em `Test`; teste que prova requisito leva o ID no `@DisplayName`.
- Sem `@MockitoBean` em teste de integração. Falha da auditoria se simula com gatilho no banco, como o `AdminAuditTest` faz.
- Valores em centavos nunca passam por `float` ou `double`, nem no backend nem no frontend.
- Nenhum título, descrição ou preço em log. `AuditEntry` não tem texto livre.

## Review Focus

1. **Preço digitado com ponto (`49.90`)**: tem de ser recusado com mensagem, nunca lido como R$ 4.990,00. Teste na Task 4.
2. **Caractere NUL ou surrogate solto no título ou na descrição**: o PostgreSQL não aceita; a resposta tem de ser `400`, nunca `500`. Teste na Task 2.
3. **`?page=2147483647`**: o offset não pode estourar `int`; responde `200` com lista vazia. Teste na Task 2.
4. **Salvar a partir de uma aba velha**: `409`, nada sobrescrito, e o que foi digitado continua no formulário. Testes nas Tasks 3 e 4.
5. **Clique duplo em Salvar no cadastro**: um Produto só; o botão fica desabilitado durante o envio. Teste na Task 4.

---

## 1. Decisões de projeto

### D1. Modelo do Produto

**Recomendação:** tabela única `products` (SQL na seção 2), com `id` UUID, `title`, `description`, `category`, `price_cents`, `status`, `version`, `created_at` e `updated_at`.

- **Todos os campos são obrigatórios já no rascunho.** O formulário tem só 4 campos, e isso evita ter duas regras de validação (rascunho e publicação). O #19 só precisa conferir o Arquivo do produto.
- **Estados:** a coluna `status` nasce aceitando só `DRAFT`, com enum `ProductStatus { DRAFT }`. O #19 adiciona `PUBLISHED` com uma migration de uma linha. Por quê: nada neste ticket pode publicar, e o banco passa a garantir isso; o valor `PUBLISHED` sem código que o use seria estrutura para o futuro.
- **Editar não mexe em `status`**, então a edição continua funcionando quando o #19 chegar.
- **Sem slug e sem `created_by`:** o #20 não pede slug e o autor já fica na auditoria.
- **ID não sequencial** (UUID), como a spec pede na seção 8.

### D2. Preço em centavos **[OK seu nos limites]**

**Recomendação:**

| Item | Valor | Motivo |
|---|---|---|
| Tipo | `integer` no banco, `int` no Java, `priceCents` inteiro no JSON | Cabe até R$ 21 milhões; `accept-float-as-int: false` já recusa `49.9` |
| Mínimo | **500** (R$ 5,00) | Não achei valor mínimo de Pix na documentação do Asaas, mas a página de preços diz que, em cobrança menor que a tarifa, a tarifa consome o valor inteiro. O #24 confere no sandbox |
| Máximo | **999.999** (R$ 9.999,99) | Pega erro de digitação de um zero a mais |
| Onde fica cada limite | Faixa comercial nos constraints do record; no banco só `price_cents > 0` | O banco guarda o invariante; mudar a faixa não exige migration |

**Preço e Pedidos existentes:** a regra "mudar o preço só afeta Pedidos novos" já está na ADR-0007; não é duplicada aqui nem em outra ADR. No #17 não existe Pedido, então não há teste possível agora. Em vez disso, deixo um comentário na issue #24 pedindo o teste "mudar o preço do Produto não altera Pedido já criado".

### D3. Capacidades e `SecurityConfig`

**Duas capacidades:** `Capability.PRODUCTS_EDIT` (criar com preço inicial, listar, ver e editar título, descrição e categoria) e `Capability.PRODUCTS_PRICE` (trocar o preço depois da criação). Os nomes são estreitos de propósito: publicar (#19) e enviar arquivo (#18) ganham as suas.

**Ajuste A, decisão: adotar `PRODUCTS_PRICE` já neste ticket.**

- A SEC-029 manda separar a permissão de alterar preço das demais. Hoje só existe um Administrador e ele recebe todas as capacidades, então a separação não muda quem pode o quê; muda o desenho: quando existir um Editor (a spec cita esse papel), ele recebe `PRODUCTS_EDIT` sem poder mexer em dinheiro, sem refazer rota nem migration.
- Fazer agora é barato (uma constante, uma regra, uma rota, uma ação de auditoria, um método de serviço); fazer depois obrigaria a tirar o preço do `PUT` já em uso pela tela.
- O preço **inicial** é parte da criação e fica em `PRODUCTS_EDIT`: um Produto sem preço não existe (todos os campos são obrigatórios no rascunho). O que a SEC-029 protege é a *alteração* de um valor existente.
- **Consequência:** o `PUT /api/admin/products/{id}` **não aceita** `priceCents` (campo extra é ignorado, como `id` e `status`). Se aceitasse, `PRODUCTS_EDIT` contornaria `PRODUCTS_PRICE`. O teste prova que um `priceCents` mandado no `PUT` não altera o preço.
- **Mesma trava de versão:** a troca de preço usa a mesma coluna `version` e o mesmo `UPDATE ... where version = :version`. Uma edição de texto e uma troca de preço feitas com a mesma versão disputam: uma vence (`200`), a outra recebe `409`.
- **Custo:** uma rota a mais e, na tela (Task 4, ainda não aprovada), o preço passa a ser salvo por uma chamada própria. Isso vira decisão de tela e textos novos, que voltam para o Sérgio na seção 4.

```java
.requestMatchers(HttpMethod.GET, "/api/admin/products", "/api/admin/products/*").access(panel(Capability.PRODUCTS_EDIT))
.requestMatchers(HttpMethod.POST, "/api/admin/products").access(panel(Capability.PRODUCTS_EDIT))
.requestMatchers(HttpMethod.PUT, "/api/admin/products/*").access(panel(Capability.PRODUCTS_EDIT))
.requestMatchers(HttpMethod.PUT, "/api/admin/products/*/price").access(panel(Capability.PRODUCTS_PRICE))
```

- `panel(...)` já exige a capacidade **e** os fatores de senha e TOTP, duas travas independentes. Nenhum `permitAll`, então não precisa de ADR.
- O padrão `/*` pega um único segmento: `/api/admin/products/{id}/file` (#18) continua negado. `DELETE` e `PATCH` também.
- Quem completa o login recebe `EnumSet.allOf(Capability.class)`, então o Administrador ganha as duas capacidades sem mais código.
- **Sem reautenticação** para criar ou editar: a ADR-0004 só a exige na troca do PDF, e aqui tudo é rascunho. A SEC-008 para mudar o preço de Produto *publicado* volta no #19 (ver Riscos).
- **Respostas (ajuste C):** anônimo `401`; Cliente `403`; senha certa sem TOTP `401` (o `accessDeniedHandler` manda o estado pendente para o login); sem a capacidade ou sem um dos fatores `403`. Para cada rota, a resposta negada é **idêntica** (mesmo status, mesmo corpo) para ID existente e inexistente: Produto não tem dono, a negação acontece na rota antes do banco. Para o Administrador completo, ID inexistente dá `404`.

### D4. Rotas

| Rota | Sucesso | Erros |
|---|---|---|
| `GET /api/admin/products?page=0` | `200 {items, page, totalPages}` | `400` em página negativa ou não numérica |
| `POST /api/admin/products` | `201` + `Location` + Produto | `400` |
| `GET /api/admin/products/{id}` | `200` | `404` |
| `PUT /api/admin/products/{id}` | `200` + Produto (título, descrição, categoria; **sem preço**) | `400`, `404`, `409` |
| `PUT /api/admin/products/{id}/price` | `200` + Produto (só o preço) | `400`, `404`, `409` |

Falha de banco ou da auditoria responde `503` sem corpo, como nas rotas do painel. A página tem tamanho fixo de 20 e ordem `created_at desc, id desc`. Por quê: sem parâmetro `size` não há o que limitar (SEC-015), e o resultado é um record próprio em vez do JSON instável do `Page`.

### D5. Auditoria

**Recomendação:** novas constantes `ADMIN_PRODUCT_CREATED`, `ADMIN_PRODUCT_UPDATED` e `ADMIN_PRODUCT_PRICE_CHANGED` em `AuditAction`, e `PRODUCT` em `AuditObject`. O ator é o id do `AdministratorPrincipal`, o objeto é o id do Produto e a origem vem do `ClientIpResolver`. `AuditEntry` não tem texto livre, então título e preço não têm como entrar.

| Situação | Resultado | Transação |
|---|---|---|
| Criado, editado ou preço trocado | `SUCCESS` | `recordInTransaction`: sem trilha, sem ação (`503`) |
| Edição ou troca de preço recusada por versão velha (`409`) | `FAILURE` | `recordIndependent`, sobrevive ao rollback |
| `400`, `404`, `503` e leituras | não audita | Nada aconteceu com um objeto; segue o critério do #16 |

Com a capacidade `PRODUCTS_PRICE` separada, a troca de preço ganha ação própria (`ADMIN_PRODUCT_PRICE_CHANGED`): quem mexeu em dinheiro tem de ser achado sem olhar valores. Os valores antes e depois **não** entram (`AuditEntry` não tem texto livre).

### D6. Validação de entrada

| Campo | Regra | Códigos de erro |
|---|---|---|
| `title` | `@NotBlank`, `@Size(max=120)`, sem caractere de controle; normalizado em NFC e `strip()` antes de validar e gravar | `TITLE_REQUIRED`, `TITLE_TOO_LONG`, `TITLE_INVALID` |
| `description` | `@NotBlank`, `@Size(max=5000)`, permite só `\t \n \r` entre os de controle; NFC e `strip()` | `DESCRIPTION_REQUIRED`, `DESCRIPTION_TOO_LONG`, `DESCRIPTION_INVALID` |
| `category` | `@NotNull`, enum `ProductCategory` | `CATEGORY_REQUIRED` |
| `priceCents` | `@NotNull`, `@Min(500)`, `@Max(999999)` | `PRICE_REQUIRED`, `PRICE_TOO_LOW`, `PRICE_TOO_HIGH` |
| `version` (nos dois `PUT`) | `@NotNull` | `VERSION_REQUIRED` |

- Regex como classe de caracteres única (`[^\p{Cc}\p{Cs}]*` no título; `[[^\p{Cc}\p{Cs}][\t\n\r]]*` na descrição). Por quê: o `Pattern` do Java empilha recursão em `(a|b)*` e pode estourar a pilha num texto longo; a classe única não.
- Categoria desconhecida, número gigante e tipo errado param no Jackson: `400` genérico, sem o mapa `errors`. A tela só envia valores válidos, então isso só acontece em chamada forjada.
- Descrição é **texto puro**: o React escapa e nenhuma tela usa HTML. Sem sanitizador.
- O schema Zod espelha estas regras e os códigos do backend viram as mensagens de campo.

**Categoria (decidido):** é TEMA (FR-007 e FR-016), não tipo de conteúdo. Lista fechada no enum `ProductCategory`, guardada como código com `check` no banco:

| Código | Rótulo (só na tela) |
|---|---|
| `HEALTH` | Saúde |
| `LAW` | Direito |
| `FINANCE` | Finanças |
| `SECURITY` | Segurança |
| `EDUCATION` | Educação |
| `TECHNOLOGY` | Tecnologia |
| `BUSINESS` | Negócios |
| `OTHER` | Outros |

Formato (E-book, Cartilha, Tutorial, Checklist), tags, idioma, público e nível ficam fora do #17 (comentário no #20). Os limites de tamanho contam unidades UTF-16 do Java, que nunca ficam abaixo dos caracteres que o PostgreSQL conta em `varchar(n)`; então o banco nunca recusa o que a validação aceitou.

### D7. Concorrência

**Recomendação:** trava otimista com a coluna `version`. O `GET` devolve a versão e o `PUT` a envia no corpo. A edição de texto e a troca de preço são, cada uma, um único `UPDATE ... set version = version + 1 where id = :id and version = :version`. Se afetar 0 linhas, um `SELECT` distingue `404` de `409`.

Por quê `JdbcClient` e não JPA com `@Version`: a comparação com a versão **do cliente** fica atômica no SQL, é o padrão do `Administrators` e dos `UPDATE` condicionais do MFA, e não exige entidade. O `PUT` substitui os campos editáveis (título, descrição, categoria) inteiros. Em `409` a tela avisa e não sobrescreve nada; quem decide é o Administrador.

### D8. Seed local

**Recomendação:** `backend/src/main/resources/db/seed/R__seed_products.sql`, com 5 Produtos fictícios, de temas diferentes (títulos começando em `[Fictício]`), IDs fixos e `insert ... on conflict (id) do nothing`, para não apagar o que você editou localmente. Só o `application-local.yml` inclui `classpath:db/seed`; o perfil `prod` e os testes usam só `db/migration`.

## 2. Migrations e o que fica de fora

**Próximo número livre: V13** (o último é V12), criado com a skill `nova-migration`.

```sql
-- V13__create_products.sql
create table products (
  id uuid primary key default gen_random_uuid(),
  title varchar(120) not null,
  description varchar(5000) not null,
  category varchar(16) not null check (category in ('HEALTH', 'LAW', 'FINANCE', 'SECURITY', 'EDUCATION', 'TECHNOLOGY', 'BUSINESS', 'OTHER')),
  price_cents integer not null check (price_cents > 0),
  status varchar(16) not null default 'DRAFT' check (status in ('DRAFT')),
  version integer not null default 0,
  created_at timestamp with time zone not null default now(),
  updated_at timestamp with time zone not null default now()
);
```

Sem índice extra: com poucas dezenas de Produtos ele não se paga.

**Fora do ticket:** Capa (#21); Arquivo do produto (#18) e sua troca (#22); publicar e despublicar (#19); Catálogo público (#20); Pedido (#24); excluir Produto (nenhum ticket pede, e o Pedido vai referenciá-lo); busca e filtros (Fase 3); limite de requisições por rota (#42).

## 3. Arquivos e testes

### Criar

| Arquivo | Responsabilidade |
|---|---|
| `backend/src/main/resources/db/migration/V13__create_products.sql` | Tabela |
| `backend/src/main/resources/db/seed/R__seed_products.sql` | Dados fictícios |
| `backend/.../product/ProductCategory.java`, `ProductStatus.java` | Enums |
| `backend/.../product/ProductRequest.java`, `ProductUpdateRequest.java`, `ProductPriceRequest.java` | Entrada com as validações (criar; editar texto; trocar preço) |
| `backend/.../product/ProductResponse.java`, `ProductPage.java` | Saída |
| `backend/.../product/Products.java` | `JdbcClient` |
| `backend/.../product/ProductAdminService.java` | Regras, transação e auditoria |
| `backend/.../product/ProductAdminController.java` | Rotas e o `503` |
| `backend/.../product/ProductNotFoundException.java`, `ProductVersionConflictException.java` | Viram `404` e `409` |
| `frontend/src/features/products/schemas.ts`, `api.ts`, `hooks.ts`, `messages.ts` | Contrato e textos |
| `frontend/src/features/products/ProductListPage.tsx`, `ProductFormPage.tsx` | Telas |
| `frontend/src/features/admin/AdminGuard.tsx` | Carregando, erro, login e "só para o Administrador", extraídos do `AdminHomePage` |
| `frontend/src/components/ui/textarea.tsx` | Mesmas classes do `Input`, sem dependência |

### Alterar

| Arquivo | Mudança |
|---|---|
| `backend/.../admin/Capability.java` | `PRODUCTS_EDIT` e `PRODUCTS_PRICE` |
| `backend/.../security/SecurityConfig.java` | 4 regras (D3) |
| `backend/.../audit/AuditAction.java`, `AuditObject.java` | D5 |
| `backend/.../web/ApiExceptionHandler.java` | `404` e `409` |
| `backend/src/test/.../admin/AdminBrowser.java` | Método `put` |
| `backend/src/test/.../admin/AdminRouteDenyByDefaultTest.java` | `DELETE` e rota mais funda de Produto continuam negados |
| `frontend/src/App.tsx` | 3 rotas |
| `frontend/src/features/admin/AdminHomePage.tsx` | Usa o `AdminGuard`; link "Produtos" se tiver `PRODUCTS_EDIT` |
| `frontend/e2e/admin-panel.e2e.ts` | Novo teste no mesmo arquivo (ver abaixo) |
| `docs/adr/0007-modelo-comercial-da-fase-1.md` | Só os limites do preço e a lista de categorias; a regra do Pedido já está lá |
| `GLOSSARY.md` | Termo **Rascunho** |

### Testes

Os testes HTTP ficam no pacote de teste `admin`, para usar `AdminBrowser` e `AdminMfaTestSupport` sem abrir a visibilidade deles.

**Unitários**

- `ProductRequestTest` (Bean Validation): limites 120/121 e 5000/5001, em branco, NUL, surrogate solto, `\n` só na descrição, NFC (`e` + acento combinante vira o caractere composto), preço 0/negativo/499/500/999999/1000000, nulos; `ProductPriceRequest` com os mesmos limites de preço.

**Integração (HTTP e PostgreSQL de verdade)**

`AdminProductAuthorizationTest`, tabela estado × rota com `AdminBrowser` (login real):

| Estado | 4 rotas | Observação |
|---|---|---|
| Anônimo | `401` | SEC-010 |
| Cliente | `403` | Igual para ID existente e inexistente, sem dado no corpo |
| Só senha (pendente) | `401` | SEC-001, sem TOTP |
| Pendente vencido | `401` | |
| Administrador completo | `200`/`201` | Controle positivo |

As 5 rotas entram na tabela (as quatro de `PRODUCTS_EDIT` e `PUT .../price`).

Mais, nesse mesmo teste (ajuste C): a resposta negada é igual (status e corpo) para ID existente e inexistente em todas as rotas com `{id}`; sem token CSRF `403` em `POST` e `PUT` e nada gravado; `DELETE` e `/api/admin/products/{id}/file` `403` até para o Administrador; depois de todas as recusas, `products` continua sem linha nova.

`AdminProductCapabilityTest`, com MockMvc e sessão montada (`SecurityMockMvcRequestPostProcessors.authentication`; o `spring-boot-starter-security-test` já está no `pom.xml`). Por quê sessão montada: no login real o Administrador sempre recebe todas as capacidades, então esse estado não dá para alcançar pelo fluxo.

| Sessão | Resposta |
|---|---|
| Dois fatores, só `PANEL_ACCESS` | `403` |
| `PRODUCTS_EDIT`, só fator senha | `403` |
| `PRODUCTS_EDIT`, só fator TOTP | `403` |
| `PRODUCTS_EDIT` e dois fatores | `201` (controle) |
| `PRODUCTS_EDIT` e dois fatores, em `PUT .../price` | `403` (SEC-029) |
| `PRODUCTS_PRICE` e dois fatores, em `POST` e `PUT` do texto | `403` |
| `PRODUCTS_PRICE` e dois fatores, em `PUT .../price` | `200` (controle) |
| `PRODUCTS_PRICE`, só senha ou só TOTP, em `PUT .../price` | `403` |

`AdminProductTest`:

- `FR-016`: cria em `DRAFT`, com `201`, `Location` e corpo.
- `FR-018`: preço em centavos volta exato; `49.9` e `3000000000` dão `400` sem detalhe interno.
- `SEC-012`: `id`, `status` e `version` extras no `POST` são ignorados.
- `SEC-013`: cada código de erro por campo; NUL dá `400`, nunca `500`.
- `SEC-015`: 20 por página, ordem certa, `page=2147483647` dá `200` vazio, página negativa dá `400`.
- `SEC-020`: `text/plain` dá `415`.
- ID inexistente dá `404`; ID malformado dá `400`.
- Editar troca título, descrição e categoria, sobe a versão e mantém `status`, preço e `created_at`; `priceCents` mandado no `PUT` é ignorado.
- `SEC-029`: `PUT .../price` troca só o preço e sobe a versão; rejeita 499, 0, negativo e 1000000 com `400`.
- `SEC-018`: nenhum `400` traz nome de classe ou SQL.

`AdminProductConcurrencyTest`:

- Versão velha dá `409` e não altera a linha.
- Dois `PUT` em paralelo com a mesma versão dão um `200` e um `409`.
- Edição de texto e troca de preço com a mesma versão: um `200` e um `409`.

`AdminProductAuditTest` (SEC-065):

- Criar, editar e trocar o preço gravam `SUCCESS` (`ADMIN_PRODUCT_CREATED`, `_UPDATED`, `_PRICE_CHANGED`) com ator, objeto, origem e correlation ID.
- `409` grava `FAILURE` (nos dois `PUT`); `400` e `404` não gravam.
- Com a trilha fora (gatilho de teste), criar e editar dão `503` e nada muda (também a troca de preço).

`ProductSeedTest` (NFR-012):

- No contexto de teste, os IDs do seed não existem.
- Aplicado num schema próprio (migrations + seed, com `Flyway` manual), gera 5 linhas válidas, e rodar de novo não duplica.
- `application-prod.yml` e `application.yml` não citam `db/seed`.
- **NFR-012 em perfil prod (ajuste B):** teste de integração com `spring.profiles.active=prod` (mesma configuração do contexto de teste para banco e propriedades `app.*` obrigatórias): depois da subida, `flyway_schema_history` não tem `R__seed_products` e nenhum ID de Produto do seed existe em `products`. Complemento: o `locations` do Flyway no perfil prod resolvido não contém `db/seed`, e `R__seed_products.sql` existe só em `db/seed`, nunca em `db/migration`. Se subir o contexto `prod` inteiro no teste for inviável (segredos, e-mail), o teste monta o Flyway com a lista de `locations` lida do `application-prod.yml` e prova o mesmo; o relato diz qual forma foi usada.

**Frontend (Vitest + Testing Library)**

- `schemas.test.ts`: `49,90` vira 4990; `1.234,56` vira 123456; `5` vira 500; `49.90`, `1,234`, `abc`, `-1`, `4,99` e `10000` recusados; centavos voltam a `49,90` sem `float`.
- `ProductListPage.test.tsx`: carregando, erro, vazio, lista, paginação, aviso depois de salvar, `401` leva ao login, `403` mostra sem permissão.
- `ProductFormPage.test.tsx`: mensagens por campo, botão desabilitado durante o envio, `409` mantém o digitado, `404`, código do servidor vira mensagem no campo.
- `AdminHomePage.test.tsx`: link "Produtos" aparece só com a capacidade.

**E2E, sem mocks nem `page.route`**

Novo `test(...)` dentro de `frontend/e2e/admin-panel.e2e.ts`: "FR-016 / FR-018: o Administrador cria e edita um Produto em rascunho". Por quê no mesmo arquivo: há um só Administrador e o TOTP não aceita o mesmo passo duas vezes. Testes de um mesmo arquivo rodam em sequência, e arquivos diferentes em paralelo iriam disputar o login. Esse arquivo também já fica fora do projeto celular.

Fluxo: login com senha e TOTP (reusa `tryPassword`, `waitForStepAfter` e `enterCode`) → link "Produtos" → "Novo Produto" → preenche com título único → a lista mostra título, "R$ 49,90" e "Rascunho" → edita o preço para 59,90 → recarrega a página e confere → contexto anônimo em `/painel/produtos` vai para `/painel/entrar`. Não depende do seed: localmente ele existe e no CI não.

## Tarefas de execução

### Task 1: Tabela e seed

**Files:** V13, `R__seed_products.sql`, `ProductSeedTest` (inclui o teste do perfil prod).

**Produces:** tabela `products` da seção 2.

- [ ] Escrever o `ProductSeedTest` e ver falhar (Docker aberto).
- [ ] Criar migration e seed.
- [ ] Rodar `cd backend; .\mvnw.cmd test -Dtest=ProductSeedTest` e ver passar.
- [ ] Commit `feat: products table and local seed (#17)`.

### Task 2: Criar, listar e ver, com autorização e auditoria

**Files:** `ProductCategory`, `ProductStatus`, requests, responses, `Products`, `ProductAdminService`, `ProductAdminController`, `ProductNotFoundException`, `Capability`, `SecurityConfig`, `AuditAction`, `AuditObject`, `ApiExceptionHandler`, `AdminBrowser`, `AdminRouteDenyByDefaultTest`.

**Consumes:** Task 1. **Produces:** `Products.insert(ProductRequest) -> ProductResponse`; `Products.find(UUID) -> Optional<ProductResponse>`; contrato da seção D4, com JSON `{id, title, description, category, priceCents, status, version, createdAt, updatedAt}`. O parâmetro é `@RequestParam(defaultValue = "0") @Min(0) int page`, e o offset é calculado como `page * 20L`.

- [ ] Escrever `ProductRequestTest`, `AdminProductAuthorizationTest`, `AdminProductCapabilityTest`, a parte de criação e listagem de `AdminProductTest` e a de criação de `AdminProductAuditTest`; ver falhar.
- [ ] Implementar.
- [ ] Rodar `.\mvnw.cmd test -Dtest="ProductRequestTest,AdminProduct*Test,AdminRouteDenyByDefaultTest"` e ver passar.
- [ ] Commit `feat: create and list products in the admin panel (#17)`.

### Task 3: Editar com trava otimista

**Files:** `ProductUpdateRequest`, `ProductPriceRequest`, `ProductVersionConflictException`, `Products.update`, `Products.updatePrice`, `ProductAdminService.update` e `.changePrice`, `ProductAdminController` (`PUT` e `PUT .../price`), `ApiExceptionHandler`, `AdminProductConcurrencyTest`.

**Produces:** `Products.update(UUID, ProductUpdateRequest) -> int` e `Products.updatePrice(UUID, ProductPriceRequest) -> int` (linhas afetadas).

- [ ] Escrever os testes de edição, concorrência e auditoria da edição; ver falhar.
- [ ] Implementar (D5 e D7).
- [ ] Rodar `.\mvnw.cmd test -Dtest="AdminProduct*Test"` e ver passar.
- [ ] Commit `feat: edit products with optimistic locking (#17)`.

### Task 4: Telas (só depois de aprovada a seção 4)

**Files:** `frontend/src/features/products/*`, `AdminGuard`, `textarea.tsx`, `App.tsx`, `AdminHomePage`.

- [ ] Escrever os testes de frontend e ver falhar.
- [ ] Implementar; `AdminHomePage.test.tsx` continua passando sem afrouxar.
- [ ] Rodar `pnpm exec vitest run` em `frontend/` e ver passar.
- [ ] Rodar `/verificar-tela` nas 3 telas, em desktop, celular e teclado.
- [ ] Commit `feat: product screens in the admin panel (#17)`.

### Task 5: E2E, documentação e verificação final

**Files:** `admin-panel.e2e.ts`, ADR-0007, `GLOSSARY.md`.

- [ ] Escrever o e2e.
- [ ] Subir o sistema real (`just db` na pasta principal, `just dev-back` e `just dev-front` na worktree) e rodar `just e2e`.
- [ ] Atualizar a ADR-0007 (limites do preço e categorias) e o glossário.
- [ ] Rodar `just check-resumo` e mostrar o resultado.
- [ ] Commit `test: e2e of the product draft flow (#17)` e `docs: product pricing rules (#17)`.
- [ ] Rodar a skill `verificar-entrega` e mostrar a tabela.
- [ ] Abrir a issue única de sobras (`needs-triage`, citando o #17). Os comentários no #24 (teste do preço) e no #20 (formato, tags, idioma, público, nível) são feitos no início do backend.
- [ ] Parar e pedir o OK do push.

## 4. Textos novos que o usuário vê (para aprovar)

**Decisões que valem para todas as telas**

- A tela de um Produto tem **dois blocos independentes**: "Dados do produto" (título, descrição, categoria; botão "Salvar dados") e "Preço" (valor em reais; botão "Salvar preço"). Cada botão grava só o seu bloco.
- Categorias aprovadas: Saúde · Direito · Finanças · Segurança · Educação · Tecnologia · Negócios · Outros.
- O preço aparece em reais com vírgula (ex.: R$ 19,90) e é gravado em centavos (19,90 vira 1990).
- O Produto tem **um número de versão só**. Salvar um bloco aumenta esse número, e a tela passa a usar o número novo nos dois blocos (sem apagar o que a pessoa já digitou no outro). Por isso salvar os dois blocos em sequência não dá conflito; o conflito só aparece se outra janela mexer no Produto.
- Quem tem a permissão de editar mas não a de preço vê o bloco "Preço" só para leitura, com um aviso.

**Painel e lista (`/painel/produtos`)**

| Onde | Texto |
|---|---|
| Link no painel | Produtos |
| Título da página | Produtos |
| Link | Novo Produto |
| Link | Voltar ao painel |
| Colunas | Título · Categoria · Preço · Estado · Ações |
| Preço na linha | R$ 19,90 |
| Estado | Rascunho |
| Link na linha | Editar (leitor de tela: "Editar {título}") |
| Carregando | Carregando… |
| Lista vazia | Nenhum Produto cadastrado ainda. Use "Novo Produto" para criar o primeiro. |
| Erro ao carregar | Não foi possível carregar os Produtos agora. Tente novamente. |
| Sem permissão | Você não tem permissão para gerenciar Produtos. |
| Indisponível (503) | O sistema está indisponível no momento. Tente novamente em alguns instantes. |
| Paginação | Anterior · Próxima · Página {n} de {total} |
| Nome da paginação (leitor de tela) | Paginação |
| Confirmação | Produto criado. |

**Novo Produto (`/painel/produtos/novo`)**

| Onde | Texto |
|---|---|
| Título da página | Novo Produto |
| Mensagem do rascunho | Este Produto começa como rascunho: os clientes ainda não conseguem vê-lo nem comprá-lo. |
| Bloco | Dados do produto |
| Bloco | Preço |
| Botões | Criar Produto · Cancelar |
| Enviando | Criando… |

(Na criação os dois blocos aparecem juntos e há um botão só; os rótulos, ajudas e validações são os mesmos da tela de edição, abaixo.)

**Editar Produto (`/painel/produtos/:id`)**

| Onde | Texto |
|---|---|
| Título da página | Editar Produto |
| Link | Voltar para Produtos |
| Mensagem do rascunho | Este Produto está em rascunho: os clientes ainda não conseguem vê-lo nem comprá-lo. |
| Carregando | Carregando… |
| Erro ao carregar | Não foi possível carregar o Produto agora. Tente novamente. |
| Não encontrado | Produto não encontrado. |

*Bloco "Dados do produto"*

| Onde | Texto |
|---|---|
| Título do bloco | Dados do produto |
| Rótulo | Título |
| Rótulo | Descrição |
| Rótulo | Categoria |
| Primeira opção | Selecione uma categoria |
| Opções | Saúde · Direito · Finanças · Segurança · Educação · Tecnologia · Negócios · Outros |
| Ajuda do título | Até 120 caracteres. |
| Ajuda da descrição | Até 5.000 caracteres. |
| Botão | Salvar dados |
| Enviando | Salvando… |
| Salvo | Dados salvos. |
| Título vazio | Digite o título. |
| Título longo | O título pode ter até 120 caracteres. |
| Título inválido | O título tem caracteres que não podem ser usados. |
| Descrição vazia | Digite a descrição. |
| Descrição longa | A descrição pode ter até 5.000 caracteres. |
| Descrição inválida | A descrição tem caracteres que não podem ser usados. |
| Sem categoria | Selecione uma categoria. |

*Bloco "Preço"*

| Onde | Texto |
|---|---|
| Título do bloco | Preço |
| Rótulo | Valor (R$) |
| Ajuda | Use vírgula nos centavos. Exemplo: 19,90. O mínimo é R$ 5,00 e o máximo é R$ 9.999,99. |
| Botão | Salvar preço |
| Enviando | Salvando… |
| Salvo | Preço salvo. |
| Preço vazio | Digite o preço. |
| Preço mal escrito | Digite um preço válido, como 19,90. |
| Preço baixo | O preço mínimo é R$ 5,00. |
| Preço alto | O preço máximo é R$ 9.999,99. |
| Sem permissão para o preço | Você não tem permissão para alterar o preço. |

*Erros ao salvar (valem para os dois blocos; aparecem dentro do bloco que falhou, sem apagar o que foi digitado)*

| Onde | Texto |
|---|---|
| Conflito de versão (409) | Este Produto foi alterado em outra janela. O que você digitou não foi salvo. Use Recarregar para ver a versão atual. |
| Botão do conflito | Recarregar |
| Sem permissão (403) | Você não tem permissão para fazer esta alteração. |
| Indisponível (503) | O sistema está indisponível no momento e nada foi salvo. Tente novamente em alguns instantes. |
| Erro inesperado | Não foi possível salvar agora. Tente novamente. |

Reaproveitados, sem mudança: "Carregando…", "Não foi possível carregar o painel agora. Tente novamente." e "Esta área é só para o Administrador." Sessão encerrada (`401`) não tem texto: o app vai para `/painel/entrar`.

## 5. Dependências novas

Nenhuma. O `<select>` é nativo, com as classes do `Input`, e o `textarea.tsx` é só marcação e classes.

## 6. Riscos e o que fica de fora

**O que depende da sua decisão**

Decididas pelo Sérgio: categoria por tema, preço de R$ 5,00 a R$ 9.999,99, tamanhos 120/5.000, critério dos Pedidos cobrado no #24. Falta só: o seu OK nos **textos** da seção 4 (refeitos, com os blocos "Dados do produto" e "Preço").

**Riscos**

- **Mínimo do Asaas não confirmado:** a documentação consultada não traz o valor. Se o #24 achar um piso maior que R$ 5,00, muda só o `@Min` e o texto.
- **Mudar preço de Produto publicado com sessão roubada:** hoje é só rascunho, sem risco. A `PRODUCTS_PRICE` separa a permissão, mas não pede reautenticação. O #19 deve reavaliar a SEC-008 quando existir Produto à venda; vai para a issue de sobras.
- **Corpo JSON muito grande:** é lido antes de validar o tamanho. Só o Administrador completo chega lá, e o limite geral é do #42.
- **`/painel/produtos` e o `AdminGuard`:** extrair a proteção mexe no `AdminHomePage`, que já tem testes; eles precisam passar sem alteração.
- **E2E mais lento:** o novo teste espera o próximo passo do TOTP (até 30 s).
- **`audit_events.origin`** só é confiável depois do #56, como já consta na ADR-0004.
- **Baseline não rodado nesta worktree:** você pediu para não rodar o `just check`. A primeira execução de testes acontece na Task 1.
- **`authentication(...)` com o `SessionVersionFilter`:** a sessão montada precisa de um Administrador real no banco, com a mesma `session_version`, senão o filtro a encerra. O teste cria essa linha.

**Fora do ticket:** ver seção 2.
