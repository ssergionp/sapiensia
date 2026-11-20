# Pedido de Reembolso pelo site (#213) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task (processo COMPLETO do projeto: revisão por tarefa, verificar-entrega e /code-review no fim). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** o Cliente pede o Reembolso de um Pedido pago em "Meus Pedidos", com motivo; o Acesso daquele Pedido é suspenso na hora; o Administrador vê a solicitação no painel (motivo, Downloads, outros Reembolsos) e devolve o dinheiro; o cancelamento definitivo continua só no `RefundConfirmation`.

**Architecture:** uma tabela nova `refund_requests` (uma linha por Pedido) e uma coluna `accesses.suspended_at`. Um serviço novo, `RefundRequestService`, é o único lugar que cria a solicitação e suspende o Acesso, numa transação só, com auditoria e Alerta. A solicitação não tem estado próprio: está "aguardando devolução" enquanto o Pedido está `PAID` e "devolvida" quando o Pedido vira `REFUNDED`, pelo caminho que já existe (webhook do Asaas → `RefundConfirmation`), que não muda. `Accesses.cancelByOrder` já cancela uma linha suspensa, então o e-mail de Reembolso sai como hoje.

**Tech Stack:** Java 25, Spring Boot 4.1, JdbcClient, Flyway, db-scheduler; React + TypeScript, TanStack Query, React Hook Form + Zod; JUnit 5 + Testcontainers, Vitest + Testing Library, Playwright.

**Spec:** issue #213 (regras aprovadas pelo Sérgio em 19/11/2026); `docs/spec/sapiensia-spec.md` (FR-020, FR-023, FR-025, SEC-010, SEC-011, SEC-012, SEC-014, SEC-029, SEC-036, SEC-065, NFR-009); ADR-0003, ADR-0007.

## Decisões do Sérgio

**Aprovado em 19/11/2026:** D1 a D9 como recomendado e os textos do Cliente e do Administrador, com dois ajustes já aplicados abaixo: o texto do erro de prazo vencido, e a Política de reembolso mantém o pedido por e-mail para defeito no arquivo sem o limite de 7 dias. A coluna Cliente da tela Reembolsos mostra o e-mail mascarado pelo `MaskedEmail`. Os textos legais (Política, Termos e Privacidade) ficam na última tarefa (Task 10) e o OK final deles é dado na PR.

| # | Decisão | Recomendação |
|---|---|---|
| D1 | Onde o Administrador devolve o dinheiro | **No painel do Asaas, como hoje** (ver "Devolução do dinheiro"). O botão no painel da SapiensIA fica para uma issue depois do lançamento. |
| D2 | Como contar os 7 dias | Até o **fim do 7º dia depois do dia do pagamento**, no horário de São Paulo (pagou em 19/11, pede até 26/11 às 23h59). É a contagem do Código Civil (não conta o dia do começo), favorece o Cliente e permite mostrar uma data. Alternativa: 168 horas exatas. |
| D3 | Prazo prometido para devolver | "em até 2 dias úteis" (é o que o runbook de suporte já propõe). |
| D4 | Aviso ao Administrador a cada solicitação | **Sim**: um Alerta por e-mail (`REFUND_REQUESTED`) a cada solicitação, além do Alerta do 3º Reembolso. Sem ele você só descobre a solicitação abrindo o painel. Fica fora das 5 regras da issue, por isso peço o OK. |
| D5 | Nome do conceito | No glossário, **Solicitação de reembolso** (o "pedido de Reembolso" se confunde com Pedido, que é a compra). Nas telas o verbo continua "Pedir Reembolso" e o estado é "Reembolso solicitado". |
| D6 | Cliente desistir da solicitação | **Fora deste ticket.** Dentro do prazo o Reembolso é sempre concedido; quem pediu por engano compra de novo depois da devolução. |
| D7 | Texto da Política sobre arquivo já baixado | Tirar o `[a definir: o advogado confirma...]` do arrependimento e dizer que o Reembolso vale "mesmo que você já tenha baixado o arquivo" (é a regra 1 da issue). |
| D8 | Onde fica o formulário | Página própria, `/pedidos/:id/reembolso`, aberta por um link em cada Pedido pago de "Meus Pedidos". O projeto não tem componente de diálogo; uma página é mais simples e acessível. |
| D9 | Como contar "3º Reembolso em 12 meses" | No momento da solicitação: outros Pedidos do mesmo Cliente reembolsados ou com Reembolso solicitado nos 12 meses anteriores; se forem 2 ou mais, esta é a 3ª e o Alerta sai. O painel mostra esse mesmo número ("Outros Reembolsos em 12 meses"). |

## Devolução do dinheiro: recomendação

**Opção B (recomendada): painel do Asaas, como hoje.** O painel da SapiensIA mostra a solicitação com o id da cobrança (`pay_...`) e o valor; você estorna o valor inteiro no Asaas; o webhook chega e o `RefundConfirmation` marca o Pedido como reembolsado, cancela o Acesso e manda o e-mail, sem mudança nenhuma nesse caminho.

- A favor: nenhum código novo que mexe em dinheiro. Hoje o sistema nunca manda dinheiro para fora, e isso continua. A saída do dinheiro fica protegida pelo login e pela autorização do próprio Asaas. Falta de saldo aparece na tela do Asaas, na hora. O ADR-0003 continua valendo.
- Contra: um passo manual (cerca de um minuto por Reembolso) e o risco de estornar a cobrança errada ou um valor parcial. O parcial já é coberto: fica registrado como `PARTIAL_REFUND` e gera Alerta para revisar.

**Opção A: botão "Devolver o dinheiro" no painel da SapiensIA, via `PixGateway`.** Conferi a documentação do Asaas: `POST /v3/payments/{id}/refund`, sem corpo, estorna o valor inteiro.

- A favor: um clique, sem copiar id, sem risco de cobrança ou valor errado.
- Contra: é o primeiro caminho do sistema que tira dinheiro da conta. Precisa de capacidade própria (`REFUNDS_EXECUTE`), autenticação recente (SEC-008), auditoria, tratamento de saldo insuficiente (o Asaas responde 400 quando o Pix foi sacado), de resposta sem resultado conhecido (timeout) e do estado `AWAITING_CRITICAL_ACTION_AUTHORIZATION` (se a conta exigir autorização de ação crítica, você aprova no Asaas de qualquer jeito). Só dá para testar de verdade no sandbox do Asaas. Mais ou menos 3 tarefas a mais neste ticket e uma revisão do ADR-0003.

**Por que B agora:** antes do lançamento o volume de Reembolsos é baixo, e a regra do projeto é a solução com menos código novo que cumpre o ticket. Tudo o que este ticket constrói (solicitação, suspensão, fila no painel) é o mesmo nas duas opções; o botão pode entrar depois, numa issue própria, sem retrabalho.

## Global Constraints

- Prazo: a solicitação é aceita enquanto `agora < início do dia (dia do pagamento em America/Sao_Paulo + 8 dias)`. Dentro do prazo nunca é recusada pelo motivo.
- Motivos (lista fechada, nesta ordem): `NOT_AS_EXPECTED` "Não atendeu ao que eu esperava", `DEFECTIVE_FILE` "Arquivo com defeito", `BOUGHT_BY_MISTAKE` "Comprei por engano", `OTHER` "Outro". Comentário opcional, no máximo 500 caracteres.
- O Pedido continua `PAID` até o Asaas mostrar a cobrança `REFUNDED`. `RefundConfirmation`, `OrderRefundedEmail` e o webhook não mudam.
- O comentário do Cliente é texto livre: nunca vai para log, auditoria nem Alerta (SEC-036); no painel aparece como texto puro.
- O e-mail do Cliente no painel só por `MaskedEmail`.
- Novas compras nunca são bloqueadas por causa de Reembolsos (CDC art. 39, II).
- Todo texto visível está na tabela "Textos" e precisa do OK do Sérgio; texto novo fora dela volta para ele antes do push.
- Nenhuma dependência nova: motivo com `<input type="radio">` nativo em `<fieldset>`, comentário com o `Textarea` que já existe.
- Migration pela skill `nova-migration` (próximo número esperado: V30). Código, commits e comentários em inglês; textos e docs em português.
- Testes de subagente: só os focados da tarefa, um teste pesado por vez, vermelho por asserção (não por erro de compilação), `just check-rapido` uma vez no fim da tarefa. Teste focado de código que escreve em log ou cita um ID de requisito inclui as classes que já conferem esse log ou ID (`git grep -l FR-023 -- backend/src/test`).

## Review Focus

1. **Duas solicitações ao mesmo tempo para o mesmo Pedido** (duplo clique, duas abas): uma linha só, um Alerta só, a segunda resposta é 200 com a mesma data. Teste na Task 3.
2. **Solicitação bem na virada do prazo**: 23h59m59 do 7º dia aceita, 00h00 do 8º recusa, com o relógio em UTC. Teste na Task 3 (`RefundWindowTest`).
3. **Compra duplicada** (Pedido pago sem Acesso próprio): a solicitação é aceita e o Acesso do outro Pedido não é suspenso. Teste na Task 3.
4. **Comentário com HTML, quebras de linha, emoji ou só espaços**: guardado como texto (só espaços vira nulo), mostrado sem interpretar, nunca no log. Testes nas Tasks 3, 4 e 7.
5. **Conta anonimizada com solicitação aberta**: o comentário é apagado, a linha fica, o estorno ainda conclui pelo webhook. Teste na Task 5.

Limite conhecido, já no ADR-0003: um link de Download emitido antes da suspensão continua valendo pelos 60 segundos dele.

## Textos (precisam do OK do Sérgio)

### Cliente

| Onde | Texto |
|---|---|
| Meus Pedidos, Pedido pago dentro do prazo: link | `Pedir Reembolso` (nome acessível: `Pedir Reembolso de {título}`) |
| Meus Pedidos, mesma linha | `Você pode pedir o Reembolso até {dd/mm/aaaa}.` |
| Meus Pedidos, selo do Pedido com solicitação | `Reembolso solicitado` |
| Meus Pedidos, linha do Pedido com solicitação (no lugar de "Ir para a Biblioteca") | `Reembolso solicitado em {dd/mm/aaaa}. O Acesso a este Produto está suspenso.` |
| Página do formulário: título da aba | `Pedir Reembolso \| SapiensIA` |
| Título | `Pedir Reembolso` |
| Rótulos do resumo | `Produto`, `Valor` (já existem) |
| Aviso do prazo | `Você pode pedir o Reembolso deste Pedido até {dd/mm/aaaa}. O valor volta pelo Pix, para quem pagou.` |
| Aviso do Acesso | `Ao enviar, o seu Acesso a este Produto é suspenso na hora: ele sai da sua Biblioteca e você não consegue mais baixar o arquivo.` |
| Pergunta do motivo | `Qual é o motivo?` |
| Opções | `Não atendeu ao que eu esperava`, `Arquivo com defeito`, `Comprei por engano`, `Outro` |
| Rótulo do comentário | `Quer contar mais? (opcional)` |
| Dica do comentário | `Até 500 caracteres. Não escreva senhas nem números de documentos.` |
| Botão | `Enviar solicitação` (enviando: `Enviando…`) |
| Link | `Voltar a Meus Pedidos` |
| Erro: sem motivo | `Escolha um motivo.` |
| Erro: comentário longo | `Use até 500 caracteres.` |
| Erro: prazo terminou | `O prazo para pedir o Reembolso pelo site terminou. Se o arquivo tiver defeito, escreva para sapiensia.operacao@gmail.com.` |
| Erro: Pedido que não pode ser reembolsado pelo site | `Este Pedido não pode ser reembolsado pelo site. Se precisar de ajuda, escreva para sapiensia.operacao@gmail.com.` |
| Erro: falha inesperada | `Não foi possível enviar a solicitação agora. Tente novamente.` |
| Carregando, Pedido não encontrado, muitas tentativas, falha ao carregar | os textos que já existem (`Carregando…`, `Pedido não encontrado`, `Muitas tentativas. Aguarde alguns minutos e tente de novo.`, `Não foi possível carregar os seus Pedidos. Tente novamente.`) |
| Depois de enviar (e ao abrir a página de um Pedido já solicitado): título da aba | `Reembolso solicitado \| SapiensIA` |
| Título | `Reembolso solicitado` |
| Texto | `Recebemos a sua solicitação. O valor de {R$ 0,00} será devolvido pelo Pix, para quem pagou, em até 2 dias úteis. O seu Acesso a este Produto está suspenso e será cancelado quando o valor for devolvido. Você recebe um e-mail quando isso acontecer.` |
| Página do Pedido (`/pedidos/:id`) pago com solicitação: título da aba e título | `Reembolso solicitado \| SapiensIA`, `Reembolso solicitado` |
| Texto | `Você pediu o Reembolso deste Pedido. O Acesso ao Produto está suspenso e o valor será devolvido pelo Pix.` (link `Voltar a Meus Pedidos`) |
| Página do Produto, ao tentar comprar com solicitação aberta | `Você pediu o Reembolso deste Produto. Depois que o valor for devolvido, você pode comprar de novo.` |

O e-mail "Seu Pedido na SapiensIA foi reembolsado" não muda. Não há e-mail novo para o Cliente.

### Administrador

| Onde | Texto |
|---|---|
| Menu do painel | `Reembolsos` |
| Título da tela | `Reembolsos solicitados` |
| Orientação | `Para devolver o dinheiro, estorne o valor inteiro da cobrança no painel do Asaas. Quando o Asaas confirmar, o Pedido passa a Reembolsado e o Acesso é cancelado sozinho.` |
| Filtro | `Filtrar por situação`: `Aguardando devolução` (padrão), `Devolvidos`, `Todos` |
| Colunas | `Pedido`, `Produto`, `Valor`, `Cliente`, `Motivo`, `Solicitado em`, `Pago em`, `Downloads do Produto`, `Outros Reembolsos em 12 meses`, `Cobrança no Asaas`, `Situação`. A coluna `Cliente` mostra o e-mail mascarado pelo `MaskedEmail` (primeira letra + `***` + `@domínio`), nunca o e-mail inteiro. |
| Situação | `Aguardando devolução`, `Devolvido em {dd/mm/aaaa HH:mm}` |
| Motivo | as 4 opções acima; com comentário: `Comentário do Cliente: {texto}` |
| Lista vazia | `Nenhum Reembolso aguardando devolução.`, `Nenhum Reembolso devolvido.`, `Nenhum Reembolso solicitado ainda.` |
| Sem permissão | `Você não tem permissão para ver os Reembolsos.` |
| Falha ao carregar | `Não foi possível carregar os Reembolsos agora. Tente novamente.` |
| Navegação | os textos que já existem (`Voltar ao painel`, `Anterior`, `Próxima`, `Página X de Y`) |
| Alerta por e-mail `REFUND_REQUESTED`: assunto | `Alerta SapiensIA: Reembolso solicitado (N)` |
| Corpo | `Um Cliente pediu o Reembolso de um Pedido pelo site. O Acesso daquele Pedido já está suspenso.` |
| O que fazer | `veja o motivo no painel, em Reembolsos, e devolva o valor inteiro no painel do Asaas, pelo runbook suporte-cliente.md, seção "Reembolso".` |
| Alerta por e-mail `REFUND_REQUESTS_REPEATED`: assunto | `Alerta SapiensIA: Cliente com 3 ou mais Reembolsos em 12 meses (N)` |
| Corpo | `Um Cliente pediu um Reembolso e, com ele, soma 3 ou mais em 12 meses.` |
| O que fazer | `o Reembolso dentro do prazo continua devido e novas compras não são bloqueadas. Veja o histórico no painel, em Reembolsos, e siga o runbook suporte-cliente.md, seção "Reembolsos repetidos".` |

### Textos legais (`legalTexts.ts`)

**Política de reembolso** (texto inteiro novo; "Casos especiais" não muda):

- **Quando você pode pedir**
  - `Em até 7 dias depois do pagamento, contados até o fim do 7º dia (direito de arrependimento, art. 49 do Código de Defesa do Consumidor). Dentro desse prazo o Reembolso é sempre concedido, qualquer que seja o motivo, mesmo que você já tenha baixado o arquivo.`
  - `Por defeito no arquivo, mesmo depois dos 7 dias: o PDF não abre, está corrompido ou não é o Produto comprado.`
- **Como pedir**
  - `Em até 7 dias depois do pagamento: entre na sua conta, abra Meus Pedidos e escolha “Pedir Reembolso” no Pedido pago. Você escolhe o motivo e, se quiser, escreve um comentário. O motivo nos ajuda a melhorar e não muda o seu direito ao Reembolso.`
  - `Por defeito no arquivo depois dos 7 dias, ou se não conseguir pedir pelo site: escreva para ${SUPPORT_EMAIL} com o e-mail da sua conta, o Produto e o motivo. [a definir: prazo de resposta ao pedido por e-mail; proposta provisória: primeira resposta em até 2 dias úteis].`
- **Como o dinheiro volta**
  - `O Reembolso é feito pelo Pix, de volta a quem pagou, por meio do Asaas, em até 2 dias úteis depois do pedido. O prazo para o valor aparecer na sua conta depende do banco.`
- **O que acontece com o Acesso**
  - `Quando você pede o Reembolso, o Acesso daquele Pedido é suspenso na hora: o Produto sai da sua Biblioteca e você não pode mais baixar o arquivo.`
  - `Quando o Asaas mostra que a cobrança foi estornada, o Acesso é cancelado em definitivo e você recebe um e-mail.`
  - `Depois do cancelamento você pode comprar o Produto de novo e recebe um Acesso novo.`
- **Reembolsos repetidos** (seção nova)
  - `Pedir Reembolso não impede novas compras. Reembolsos repetidos podem ser analisados como uso abusivo, conforme a seção “Suspensão por abuso” dos Termos de uso.`

**Termos de uso**

- "Acesso e Download", 3º parágrafo: `Se um Produto sair do Catálogo, o seu Acesso continua. Só um Reembolso cancela o Acesso; quando você pede o Reembolso, o Acesso daquele Pedido fica suspenso até a devolução do valor.`
- "Suspensão por abuso": `Podemos limitar ou suspender uma conta que use o site de forma abusiva, tente burlar os limites de Download, tente fraudar pagamentos, redistribua os Produtos ou peça Reembolsos repetidos para ficar com os arquivos sem pagar. [a definir: critérios e aviso prévio antes da suspensão].`

**Política de privacidade**

- "Quais dados tratamos", item novo depois de "Pedidos e Acessos": `Solicitações de Reembolso: o motivo escolhido, o comentário que você escrever (opcional) e a data.`
- "Com quem compartilhamos": `Resend: envio dos e-mails (confirmação, nova senha, aparelho novo, pagamento confirmado, reembolso).`
- "Por quanto tempo guardamos": `Pedidos, Acessos, eventos de pagamento, solicitações de Reembolso e Registros de download: 5 anos depois do Pedido.`
- "Seus direitos", parágrafo da exclusão da conta (ajuste aprovado pelo Sérgio na PR #219): a lista vira `Os registros financeiros (Pedidos, Acessos, eventos de pagamento, solicitações de Reembolso e Registros de download) ficam pelo prazo de guarda, sem o seu e-mail nem o seu nome.` e o parágrafo termina com `O comentário que você escreveu numa solicitação de Reembolso é apagado quando a conta é anonimizada.`

**OK final dos textos legais:** dado pelo Sérgio em 19/11/2026, na PR #219, com o ajuste acima. Na mesma data ele aceitou a regra "devolva os dois Pedidos" do runbook de suporte para a compra duplicada.

`DRAFT_VERSION` continua `Versão de rascunho de 19/11/2026.`

## File Structure

Backend (`backend/src/main/java/br/com/sergio/sapiensia/`):

- Create `payment/RefundReason.java`: enum dos 4 motivos.
- Create `payment/RefundWindow.java`: a regra dos 7 dias, sem dependências.
- Create `payment/RefundRequests.java`: repositório de `refund_requests` e a lista do painel.
- Create `payment/RefundRequestService.java`: o único lugar que cria a solicitação e suspende o Acesso.
- Create `payment/RefundRequestController.java`, `RefundRequestBody.java`, `RefundRequestResponse.java`: rota do Cliente.
- Create `payment/AdminRefundRequestController.java`, `AdminRefundRequestItem.java`, `AdminRefundRequestPage.java`: rota do painel.
- Create `payment/RefundWindowClosedException.java`, `RefundNotAvailableException.java`, `RefundInProgressException.java`.
- Modify `payment/Accesses.java`, `payment/Orders.java`, `payment/OrderService.java`, `payment/OrderSummary.java`, `payment/OrderResponse.java`, `library/LibraryItems.java`, `product/ProductDownloads.java`, `product/ProductCovers.java`, `alert/AdminAlertType.java`, `alert/AdminAlertEmail.java`, `audit/AuditAction.java`, `admin/Capability.java`, `security/SecurityConfig.java`, `web/ApiExceptionHandler.java`, `web/RateLimitedOperation.java`, `web/RateLimitConfig.java`, `customer/CustomerAnonymization.java`, `application.yml` e os arquivos de propriedades de teste.
- Create `db/migration/V30__refund_requests.sql`.

Frontend (`frontend/src/`):

- Create `features/orders/RefundRequestPage.tsx` e o teste; modify `features/orders/{api,hooks,schemas,messages,statusTone}.ts`, `OrdersPage.tsx`, `OrderPage.tsx`, `BuyWithPix.tsx`, `App.tsx`.
- Create `features/admin-refunds/{AdminRefundsPage.tsx,api.ts,hooks.ts,schemas.ts,messages.ts}` e o teste; modify `features/admin/AdminNav.tsx`.
- Modify `features/legal/legalTexts.ts` e `LegalPage.test.tsx`; `e2e/admin-panel.e2e.ts`.

Docs: `GLOSSARY.md`, `docs/adr/0003-*.md`, `docs/adr/0007-*.md`, `docs/runbooks/suporte-cliente.md`, `docs/runbooks/webhook-asaas.md`, `docs/legal/README.md`, `docs/privacidade/inventario-dados-pessoais.md`, `docs/api/endpoints.md`, `backend/src/test/resources/openapi/endpoints.txt`, `CLAUDE.md`.

---

### Task 1: migration e Acesso suspenso

**Files:**
- Create: `backend/src/main/resources/db/migration/V30__refund_requests.sql` (skill `nova-migration`)
- Modify: `payment/Accesses.java`, `library/LibraryItems.java:24`, `product/ProductDownloads.java:43`, `product/ProductCovers.java:62`
- Test: `payment/RefundRequestSchemaTest.java` (novo), `payment/LibraryTest.java`, `payment/LibraryDownloadTest.java`, `payment/RefundProcessingTest.java`

**Interfaces:**
- Produces: tabela `refund_requests (id uuid pk, order_id uuid not null unique references orders, customer_id uuid not null references customers, reason text not null, details text, requested_at timestamptz not null)` com `check (reason in ('NOT_AS_EXPECTED','DEFECTIVE_FILE','BOUGHT_BY_MISTAKE','OTHER'))` e `check (char_length(details) between 1 and 500)`; índice em `(customer_id, requested_at)`; coluna `accesses.suspended_at timestamptz` (nula = não suspenso).
- Produces: `boolean Accesses.suspendByOrder(UUID orderId, Instant now)` (verdadeiro só para a chamada que suspendeu o Acesso ativo daquele Pedido) e `boolean Accesses.suspended(UUID customerId, UUID productId)` (há Acesso não cancelado e suspenso).

- [ ] **Step 1: testes vermelhos**
  - `RefundRequestSchemaTest`: `shouldRejectUnknownReason`, `shouldRejectDetailsLongerThan500`, `shouldRejectEmptyDetails`, `shouldRejectSecondRequestForSameOrder` (insert direto por `JdbcTemplate`, espera `DataIntegrityViolationException`).
  - `LibraryTest`: `@DisplayName("FR-025: Acesso suspenso não aparece na Biblioteca")`: concede o Acesso, chama `accesses.suspendByOrder`, `GET /api/library` devolve lista vazia e `GET /api/library/{productId}/cover` devolve 404.
  - `LibraryDownloadTest`: `@DisplayName("FR-025: Acesso suspenso não baixa")`: `POST /api/library/{productId}/download` devolve 404 e nenhuma linha nova em `download_records`.
  - `RefundProcessingTest`: `@DisplayName("FR-023, FR-025: o estorno cancela um Acesso suspenso e manda o e-mail")`: Pedido pago, `suspendByOrder`, evento `PAYMENT_REFUNDED` com a cobrança `REFUNDED`; espera `status = REFUNDED`, `cancelled_at` preenchido, uma auditoria `ACCESS_CANCELLED` e um e-mail de Reembolso.
- [ ] **Step 2: rodar e ver falhar** — `cd backend; .\mvnw.cmd test -Dtest=RefundRequestSchemaTest+LibraryTest+LibraryDownloadTest+RefundProcessingTest` (falha: relação/coluna/método inexistente; depois da migration, falha por asserção).
- [ ] **Step 3: migration e código** — a migration acima; `suspendByOrder` com `update accesses set suspended_at = :now where order_id = :orderId and cancelled_at is null and suspended_at is null`; os três leitores ganham `and a.suspended_at is null`. `Accesses.exists` e `cancelByOrder` **não** mudam (o Acesso suspenso continua contando como "já tem" e continua cancelável).
- [ ] **Step 4: rodar e ver passar** — mesmo comando, mais `AccessCancellationSchemaTest+RefundRepurchaseTest+RefundConcurrencyTest`.
- [ ] **Step 5: commit** — `feat: suspend an Access and store refund requests (#213)`

### Task 2: Alertas novos

**Files:**
- Modify: `alert/AdminAlertType.java`, `alert/AdminAlertEmail.java`
- Test: o teste de texto dos Alertas que já existe no pacote `alert` (o implementador localiza com `git grep -l "ORDER_REFUNDED_EMAIL_GAVE_UP" -- backend/src/test`), `payment/AdminAlertPrivacyTest.java`

**Interfaces:**
- Produces: `AdminAlertType.REFUND_REQUESTED` e `AdminAlertType.REFUND_REQUESTS_REPEATED`, nessa ordem, logo depois de `RECONCILIATION_DIVERGENCE` (a ordem do enum é a prioridade de envio).

- [ ] **Step 1: testes vermelhos** — assunto e corpo dos dois tipos com os textos exatos da tabela "Textos"; a linha do Alerta traz `Pedido {uuid}` e nenhum e-mail.
- [ ] **Step 2: ver falhar**, **Step 3: implementar** (dois valores no enum, dois `case` em `AdminAlertEmail.text`), **Step 4: ver passar** com as classes de teste de Alerta já existentes.
- [ ] **Step 5: commit** — `feat: admin alerts for refund requests (#213)`

### Task 3: solicitação do Cliente (serviço e rota)

**Files:**
- Create: `payment/RefundReason.java`, `RefundWindow.java`, `RefundRequests.java`, `RefundRequestService.java`, `RefundRequestController.java`, `RefundRequestBody.java`, `RefundRequestResponse.java`, `RefundWindowClosedException.java`, `RefundNotAvailableException.java`, `RefundInProgressException.java`
- Modify: `payment/Orders.java` (o `Row` ganha `Instant paidAt` no fim; `SELECT` lê `o.paid_at`), `payment/OrderService.java:134`, `audit/AuditAction.java`, `security/SecurityConfig.java`, `web/ApiExceptionHandler.java`, `web/RateLimitedOperation.java`, `web/RateLimitConfig.java`, `application.yml`, propriedades de teste, `docs/api/endpoints.md`, `backend/src/test/resources/openapi/endpoints.txt`, `payment/OrderPaidEmailTextTest.java` e `OrderRefundedEmailTextTest.java` (construtor do `Row`)
- Test: `payment/RefundWindowTest.java`, `payment/RefundRequestTest.java`, `payment/RefundRequestLogTest.java` (novos), `payment/OrderRouteAccessTest.java`

**Interfaces:**
- Consumes: `Accesses.suspendByOrder`, `Accesses.suspended`, `AdminAlertType.REFUND_REQUESTED`, `REFUND_REQUESTS_REPEATED`, `Orders.lockCustomer`, `Orders.findForCustomer`.
- Produces:
  - `static Instant RefundWindow.closesAt(Instant paidAt)` e `static LocalDate RefundWindow.lastDay(Instant paidAt)`.
  - `record RefundRequestService.Requested(Instant requestedAt, boolean isNew)`; `Requested RefundRequestService.request(UUID customerId, UUID orderId, RefundReason reason, String details)`.
  - `RefundRequests.insert(UUID id, UUID orderId, UUID customerId, RefundReason reason, String details, Instant now): boolean` (`on conflict (order_id) do nothing`), `Optional<Instant> RefundRequests.requestedAt(UUID orderId)`, `int RefundRequests.otherRefunds(UUID customerId, UUID orderId, Instant since)` (outros Pedidos do Cliente com `refunded_at >= since` ou solicitação com `requested_at >= since`).
  - `POST /api/orders/{id}/refund-request`, corpo `{ "reason": "DEFECTIVE_FILE", "details": "..." }`, resposta `{ "requestedAt": "..." }`: 201 na criação, 200 quando já existia.
  - Recusas: 404 (Pedido de outro Cliente ou inexistente), 409 `{"errors":{"refund":"WINDOW_CLOSED"}}`, 409 `{"errors":{"refund":"NOT_AVAILABLE"}}` (Pedido pendente, expirado ou já reembolsado), 400 (motivo fora da lista, comentário com mais de 500), 409 `{"errors":{"order":"REFUND_IN_PROGRESS"}}` em `POST /api/orders` quando o Acesso daquele Produto está suspenso.
  - `AuditAction.REFUND_REQUESTED`; `RateLimitedOperation.REFUND_REQUEST` (`app.rate-limit.refund-request`: 10 / 10 min por conta, 30 / 10 min por IP).

- [ ] **Step 1: `RefundWindowTest` vermelho** (unitário)

```java
@Test
@DisplayName("FR-023: o prazo vai até o fim do 7º dia depois do pagamento, em São Paulo")
void shouldCloseAtTheStartOfTheEighthDayInSaoPaulo() {
    // 19/11 23:30 in São Paulo
    assertThat(RefundWindow.closesAt(Instant.parse("2026-11-20T02:30:00Z"))).isEqualTo(Instant.parse("2026-11-27T03:00:00Z"));
    assertThat(RefundWindow.lastDay(Instant.parse("2026-11-20T02:30:00Z"))).isEqualTo(LocalDate.of(2026, 11, 26));
    // 20/11 00:00 in São Paulo
    assertThat(RefundWindow.closesAt(Instant.parse("2026-11-20T03:00:00Z"))).isEqualTo(Instant.parse("2026-11-28T03:00:00Z"));
}
```

- [ ] **Step 2: `RefundRequestTest` vermelho** (integração; mesmas anotações e propriedades do `RefundProcessingTest`, para reaproveitar o contexto; pedidos pelo `OrderHttp`, `paid_at` ajustado por `JdbcTemplate` para os casos de prazo)
  - `FR-023: Pedido pago dentro do prazo — 201, solicitação gravada, Acesso suspenso, Pedido continua PAID`: confere a linha (`reason`, `details`), `suspended_at`, Biblioteca vazia, Download 404, uma auditoria `REFUND_REQUESTED` com `object_type = 'ORDER'`, um `admin_alerts` do tipo `REFUND_REQUESTED` com o `order_id`.
  - `FR-023: repetir a solicitação — 200, mesma data, uma linha, um Alerta`.
  - `FR-023: duas solicitações simultâneas — uma linha só` (duas threads, uma resposta 201 e outra 200).
  - `SEC-011: Pedido de outro Cliente — 404 e nada muda`.
  - `FR-023: pagamento há 8 dias — 409 WINDOW_CLOSED e o Acesso continua ativo`; `pagamento no 7º dia — 201`.
  - `FR-023: Pedido pendente, expirado ou reembolsado — 409 NOT_AVAILABLE`.
  - `SEC-012: motivo fora da lista — 400; comentário com 501 caracteres — 400; comentário só com espaços — gravado como nulo`.
  - `FR-023: compra duplicada — a solicitação do Pedido sem Acesso não suspende o Acesso do outro Pedido`.
  - `FR-023: 3ª solicitação em 12 meses — Alerta REFUND_REQUESTS_REPEATED; a 2ª não gera; um Reembolso de 13 meses atrás não conta`.
  - `FR-023, FR-025: depois da solicitação, o estorno no Asaas conclui — REFUNDED, Acesso cancelado, e-mail enviado`.
  - `FR-019: comprar o mesmo Produto com a solicitação aberta — 409 REFUND_IN_PROGRESS`.
  - `OrderRouteAccessTest`: anônimo 401 e sessão de Administrador 403 em `POST /api/orders/{id}/refund-request`.
  - `RefundRequestLogTest` (`SEC-036`): o log da solicitação tem `orderId` e `reason` e não tem o comentário nem o e-mail.
- [ ] **Step 3: ver falhar** — `cd backend; .\mvnw.cmd test -Dtest=RefundWindowTest+RefundRequestTest+RefundRequestLogTest+OrderRouteAccessTest`
- [ ] **Step 4: implementar**
  - `RefundWindow`: zona `America/Sao_Paulo`; `lastDay = dia do pagamento + 7`; `closesAt = início de (lastDay + 1)`.
  - `RefundRequestService.request`, numa transação (`TransactionTemplate`): `orders.lockCustomer(customerId)`; `findForCustomer` ou `OrderNotFoundException`; se já há solicitação, devolve `Requested(existente, false)`; se `status != PAID`, `RefundNotAvailableException`; se `!clock.instant().isBefore(RefundWindow.closesAt(paidAt))`, `RefundWindowClosedException`; `insert`; `accesses.suspendByOrder`; `audit.recordInTransaction(new AuditEntry(AuditAction.REFUND_REQUESTED, AuditResult.SUCCESS, null, AuditObject.ORDER, orderId, null))`; `alerts.record(REFUND_REQUESTED, null, orderId, null)`; se `otherRefunds(customerId, orderId, now menos 12 meses) >= 2`, `alerts.record(REFUND_REQUESTS_REPEATED, null, orderId, null)`. Depois do commit: `log.info("Refund requested: orderId={} reason={}", ...)`.
  - Comentário: `strip()`; vazio vira `null`.
  - `OrderService.createInTransaction`: antes do `accesses.exists`, `if (accesses.suspended(customerId, product.id())) throw new RefundInProgressException();`.
  - `SecurityConfig`: `POST /api/orders/*/refund-request` com `hasRole("CUSTOMER")`. `RateLimitConfig`: `add(registry, RateLimitedOperation.REFUND_REQUEST, "POST", true, "/api/orders/{id}/refund-request")`.
  - `ApiExceptionHandler`: as três recusas 409, no padrão de `OrderInProgressException`.
- [ ] **Step 5: ver passar** — o comando do Step 3 mais `OpenApiEndpointsTest+OrderCreationTest+OrderLogTest+PaymentLogTest+MyOrdersTest`.
- [ ] **Step 6: commit** — `feat: customer refund request suspends the Access (#213)`

### Task 4: leitura (Meus Pedidos e lista do painel)

**Files:**
- Create: `payment/AdminRefundRequestController.java`, `AdminRefundRequestItem.java`, `AdminRefundRequestPage.java`
- Modify: `payment/Orders.java`, `payment/OrderService.java`, `payment/OrderSummary.java`, `payment/OrderResponse.java`, `payment/RefundRequests.java`, `admin/Capability.java`, `security/SecurityConfig.java`, `docs/api/endpoints.md`, `openapi/endpoints.txt`
- Test: `payment/MyOrdersTest.java`, `payment/PaidOrderReadTest.java`, `admin/AdminRefundRequestListTest.java`, `admin/AdminRefundRequestAccessTest.java` (novos, no molde de `AdminOrderListTest` e `AdminOrderAccessTest`)

**Interfaces:**
- Produces:
  - `OrderSummary` ganha `LocalDate refundableUntil` (só quando o Pedido está `PAID`, sem solicitação e dentro do prazo; senão `null`) e `Instant refundRequestedAt` (`null` sem solicitação ou depois de reembolsado). `OrderResponse` ganha `Instant refundRequestedAt`.
  - `Capability.REFUNDS_VIEW` (SEC-029: permissão de Reembolso separada da de Pedidos).
  - `GET /api/admin/refund-requests?page=0&status=OPEN|REFUNDED` (sem `status` = todos), 20 por página, mais novas primeiro: `{ "items": [AdminRefundRequestItem], "page": 0, "totalPages": 1 }`.
  - `record AdminRefundRequestItem(UUID orderId, String productTitle, int priceCents, String customerEmail, RefundReason reason, String details, Instant requestedAt, Instant paidAt, long downloads, int otherRefunds, String chargeId, Instant refundedAt)`: `customerEmail` por `MaskedEmail.of`; `downloads` = linhas de `download_records` daquele Cliente e Produto; `otherRefunds` = `RefundRequests.otherRefunds(customerId, orderId, requestedAt menos 12 meses)` calculado no SQL da página; `refundedAt` nulo enquanto aguarda.

- [ ] **Step 1: testes vermelhos**
  - `MyOrdersTest`: `FR-020: Pedido pago dentro do prazo traz refundableUntil` (data = dia do pagamento + 7); `fora do prazo traz null`; `com solicitação traz refundRequestedAt e refundableUntil null`; `reembolsado traz os dois null`.
  - `PaidOrderReadTest`: `GET /api/orders/{id}` de Pedido com solicitação traz `refundRequestedAt`.
  - `AdminRefundRequestListTest`: `FR-023: a lista traz motivo, comentário, Downloads do Produto, outros Reembolsos e a cobrança` (2 Downloads, 1 outro Pedido reembolsado, valores exatos); `SEC-036: o e-mail vem mascarado e o inteiro não aparece no corpo`; `filtro OPEN e REFUNDED`; `paginação: 21 solicitações dão 2 páginas`; `comentário com <script> volta como texto, sem alteração`.
  - `AdminRefundRequestAccessTest`: `SEC-010: anônimo 401`; `SEC-029: sem REFUNDS_VIEW (mesmo com ORDERS_VIEW) 403`; `só senha ou só TOTP 403`; `com REFUNDS_VIEW e os dois fatores 200`; sessão de Cliente 403.
- [ ] **Step 2: ver falhar** — `cd backend; .\mvnw.cmd test -Dtest=MyOrdersTest+PaidOrderReadTest+AdminRefundRequestListTest+AdminRefundRequestAccessTest`
- [ ] **Step 3: implementar** — `Orders.SELECT` com `left join refund_requests r on r.order_id = o.id` e `Row` com `Instant refundRequestedAt`; a página do painel numa transação de leitura `REPEATABLE READ` como `listForAdministrator`; o filtro por constantes (`o.status = 'PAID'` / `o.status = 'REFUNDED'`), nada do pedido HTTP entra no texto do SQL; `SecurityConfig`: `GET /api/admin/refund-requests` com `panel(Capability.REFUNDS_VIEW)`.
- [ ] **Step 4: ver passar** — o mesmo comando mais `OpenApiEndpointsTest+AdminOrderListTest+AdminOrderAccessTest`.
- [ ] **Step 5: commit** — `feat: refund request fields in My Orders and the admin list (#213)`

### Task 5: anonimização e inventário de dados pessoais

**Files:**
- Modify: `customer/CustomerAnonymization.java:117`, `docs/privacidade/inventario-dados-pessoais.md`, `docs/runbooks/anonimizar-conta-cliente.md`
- Test: `customer/CustomerAnonymizationTest.java`

- [ ] **Step 1: teste vermelho** — `@DisplayName("FR-036: a anonimização apaga o comentário da solicitação de Reembolso e mantém a linha")`: depois de anonimizar, `details is null`, `reason` e `requested_at` intactos; um estorno posterior ainda leva o Pedido a `REFUNDED`.
- [ ] **Step 2: ver falhar**, **Step 3: implementar** (`update refund_requests set details = null where customer_id = :id`, na mesma transação), **Step 4: ver passar** com `CustomerAnonymizationTest+CustomerAnonymizationEmailTest`.
- [ ] **Step 5: docs** — o inventário ganha a linha de `refund_requests` (motivo, comentário livre, data; guarda de 5 anos; comentário apagado na anonimização); o runbook cita o comentário.
- [ ] **Step 6: commit** — `feat: anonymization clears the refund request comment (#213)`

### Task 6: telas do Cliente

**Files:**
- Create: `frontend/src/features/orders/RefundRequestPage.tsx`, `RefundRequestPage.test.tsx`
- Modify: `features/orders/schemas.ts`, `api.ts`, `hooks.ts`, `messages.ts`, `statusTone.ts`, `OrdersPage.tsx`, `OrderPage.tsx`, `BuyWithPix.tsx`, os testes dessas telas, `App.tsx`

**Interfaces:**
- Consumes: `refundableUntil` (`"aaaa-mm-dd"` ou `null`) e `refundRequestedAt` de `GET /api/orders`; `refundRequestedAt` de `GET /api/orders/{id}`; `POST /api/orders/{id}/refund-request`.
- Produces: `requestRefund(orderId, { reason, details? })` em `api.ts` com os erros `RefundWindowClosedError`, `RefundNotAvailableError`, `OrderNotFoundError`, `TooManyRequestsError`, `NotLoggedInError`; `useRequestRefund()` em `hooks.ts`, que invalida `["orders"]` e `["library"]`; `RefundInProgressError` em `createOrder`; rota `/pedidos/:id/reembolso`.

- [ ] **Step 1: testes vermelhos** (Vitest + Testing Library, por papel e texto visível)
  - `RefundRequestPage.test.tsx`: mostra Produto, Valor, o prazo e o aviso do Acesso; enviar sem motivo mostra `Escolha um motivo.` e não chama a API; comentário com 501 caracteres mostra `Use até 500 caracteres.`; envio com motivo chama `POST` com `{reason:"DEFECTIVE_FILE"}` e mostra `Reembolso solicitado` com o valor; `WINDOW_CLOSED`, `NOT_AVAILABLE`, 429 e erro inesperado mostram os textos da tabela; Pedido já solicitado abre direto na confirmação; Pedido sem `refundableUntil` e sem solicitação mostra o texto de "não pode ser reembolsado pelo site"; 401 leva a `/entrar` guardando o retorno; depois do envio o foco vai para o título.
  - `OrdersPage`: link `Pedir Reembolso de {título}` e a data só com `refundableUntil`; com `refundRequestedAt`, selo `Reembolso solicitado`, a linha da data e nenhum link para a Biblioteca.
  - `OrderPage`: Pedido pago com `refundRequestedAt` mostra `Reembolso solicitado` e não mostra `Ir para a Biblioteca`.
  - `BuyWithPix`: 409 `REFUND_IN_PROGRESS` mostra o texto da tabela.
- [ ] **Step 2: ver falhar** — `cd frontend; pnpm vitest run src/features/orders`
- [ ] **Step 3: implementar** — formulário com React Hook Form + `zodResolver` (`reason: z.enum([...])`, `details: z.string().max(500).optional()`); motivo em `<fieldset>` com `<legend>` e `<input type="radio">`; a página lê o Pedido de `useOrders()` (sem chamada nova); a data de `refundableUntil` é formatada da própria string (`aaaa-mm-dd` → `dd/mm/aaaa`), sem fuso; `refundRequestedAt` com o `dateFormat` de São Paulo que a tela já usa; selo com tom `pending`.
- [ ] **Step 4: ver passar** — mesmo comando.
- [ ] **Step 5: `/verificar-tela`** em `/pedidos`, `/pedidos/:id/reembolso` (formulário, erro de validação e confirmação) e `/pedidos/:id` com solicitação, em 1280 px e 390 px, com teclado.
- [ ] **Step 6: commit** — `feat: refund request page and states in My Orders (#213)`

### Task 7: tela do painel

**Files:**
- Create: `frontend/src/features/admin-refunds/AdminRefundsPage.tsx`, `AdminRefundsPage.test.tsx`, `api.ts`, `hooks.ts`, `schemas.ts`, `messages.ts`
- Modify: `features/admin/AdminNav.tsx` (link `Reembolsos`, só com `REFUNDS_VIEW`), `App.tsx` (rota `/painel/reembolsos`)

**Interfaces:**
- Consumes: `GET /api/admin/refund-requests` (Task 4); `AdminGuard`, `tableStyles.ts`, `saoPauloDateAndTime`, `formatCents`, o tratamento de 401/403 de `admin-orders/api.ts` e `hooks.ts`.

- [ ] **Step 1: testes vermelhos** — a tabela mostra as 11 colunas com os valores do item (e-mail mascarado como veio, motivo traduzido, `Comentário do Cliente: ...`, `Aguardando devolução` ou `Devolvido em ...`); o comentário `<b>x</b>` aparece como texto; o filtro começa em `Aguardando devolução` e troca a consulta (`status=OPEN`, `status=REFUNDED`, sem `status`); as três listas vazias; 403 mostra o texto de sem permissão; 401 encerra a sessão do painel; paginação; o menu só mostra `Reembolsos` com a capacidade.
- [ ] **Step 2: ver falhar** — `cd frontend; pnpm vitest run src/features/admin-refunds src/features/admin`
- [ ] **Step 3: implementar** no molde de `AdminOrdersPage.tsx` (mesma tabela rolável e mesmo `<select>` nativo).
- [ ] **Step 4: ver passar**; **Step 5: `/verificar-tela`** em `/painel/reembolsos` (com itens, vazia, sem permissão), 1280 px e 390 px.
- [ ] **Step 6: commit** — `feat: admin screen for refund requests (#213)`

### Task 8: glossário, ADRs e runbooks

**Files:**
- Modify: `GLOSSARY.md`, `docs/adr/0003-*.md`, `docs/adr/0007-*.md`, `docs/runbooks/suporte-cliente.md`, `docs/runbooks/webhook-asaas.md`, `CLAUDE.md`

Os textos legais (`legalTexts.ts`) e `docs/legal/README.md` **não** entram aqui: ficam na Task 10.

- [ ] **Step 1: docs**
  - `GLOSSARY.md`: termo novo **Solicitação de reembolso** (o pedido do Cliente pelo site, com motivo; suspende o Acesso; _Avoid_: pedido de reembolso, ticket); **Reembolso** (até 7 dias depois do pagamento sempre concedido; depois, só por defeito; o Administrador devolve no painel do Asaas); **Acesso** (suspenso ao solicitar, cancelado só pelo estorno); **Administrador** e **Alerta** (dois tipos novos).
  - ADR-0007: a regra comercial (7 dias sem condição, solicitação pelo site, suspensão, sem bloqueio de compras, Alerta do 3º). ADR-0003: a solicitação não muda o caminho do dinheiro; `RefundRequestService` suspende, `RefundConfirmation` continua o único que cancela; a decisão D1 e o motivo.
  - `suporte-cliente.md`: seção "Reembolso" reescrita (ver a solicitação no painel, achar a cobrança pelo id `pay_...`, estornar o valor inteiro no Asaas, conferir que o Pedido virou Reembolsado; solicitação por engano; falta de saldo no Asaas; pedido por e-mail depois dos 7 dias), seção nova "Reembolsos repetidos" (o que o Alerta quer dizer, que o Reembolso continua devido e a compra não é bloqueada, quando avaliar os Termos), e os prazos da seção "Canal e prazos" iguais aos da Política. `webhook-asaas.md`: uma linha na seção "Reembolso e MED" apontando para a solicitação.
  - `CLAUDE.md`, "Reaproveitar antes de criar": `RefundRequestService` (o único lugar que cria a Solicitação de reembolso e suspende o Acesso; o cancelamento continua no `RefundConfirmation`) e `RefundWindow` (a única regra do prazo).
- [ ] **Step 2: commit** — `docs: glossary, ADRs and runbooks for the refund request (#213)`

### Task 9: e2e e sistema de verdade

**Files:**
- Modify: `frontend/e2e/admin-panel.e2e.ts`

- [ ] **Step 1: teste novo** `FR-023: Reembolso pedido pelo site — Acesso suspenso na hora, painel com o motivo, estorno conclui`, sem `page.route` nem mocks: o Cliente compra e paga (ajudantes que já existem), baixa uma vez, abre Meus Pedidos, `Pedir Reembolso`, escolhe `Arquivo com defeito`, escreve um comentário, envia; vê `Reembolso solicitado`; a Biblioteca fica vazia; Meus Pedidos mostra o selo; o Administrador abre `Reembolsos` e vê o motivo, o comentário, `1` Download, o e-mail mascarado e `Aguardando devolução`; `markChargeRefunded` + evento `PAYMENT_REFUNDED`; Meus Pedidos mostra `Reembolsado` e o painel `Devolvido em`. `expectNoA11yViolations` nas telas novas (`pedir-reembolso`, `reembolso-solicitado`, `painel-reembolsos`), nos dois temas.
- [ ] **Step 2: rodar** — na pasta principal `just db`; na worktree `just dev-back` e `just dev-front`; `just e2e` com o arquivo `admin-panel.e2e.ts` inteiro (as credenciais do Administrador ficam em `.tmp/e2e` da pasta principal).
- [ ] **Step 3: conferir à mão no navegador** o fluxo principal com `just demo` (comprar, `just demo-pagar`, pedir Reembolso, ver o painel, `just demo-estornar`).
- [ ] **Step 4: commit** — `test: e2e for the refund request journey (#213)`

### Task 10: textos legais (última tarefa; o OK final do Sérgio é dado na PR)

**Files:**
- Modify: `frontend/src/features/legal/legalTexts.ts`, `frontend/src/features/legal/LegalPage.test.tsx`, `docs/legal/README.md`

- [ ] **Step 1: testes vermelhos** em `LegalPage.test.tsx`: a Política de reembolso contém `7 dias depois do pagamento`, `Meus Pedidos`, `Por defeito no arquivo, mesmo depois dos 7 dias`, `suspenso na hora`, `2 dias úteis`, `Acesso é cancelado em definitivo`, `Reembolsos repetidos` e não contém `Por ora não há botão de reembolso` nem `[a definir: o advogado confirma se e como o arrependimento`; os Termos contêm `fica suspenso até a devolução do valor` e `Reembolsos repetidos`; a Privacidade contém `Solicitações de Reembolso` e `pagamento confirmado, reembolso`.
- [ ] **Step 2: ver falhar** — `cd frontend; pnpm vitest run src/features/legal`
- [ ] **Step 3: aplicar os textos da seção "Textos legais"**, exatamente como estão escritos lá.
- [ ] **Step 4: ver passar** — mesmo comando.
- [ ] **Step 5: `docs/legal/README.md`** — itens 7 e 13 marcados como decididos em 19/11/2026 (#213), com o que ficou decidido (7 dias depois do pagamento sem condição; devolução em até 2 dias úteis), e a nota de que o texto final aguarda o OK do Sérgio na PR.
- [ ] **Step 6: `/verificar-tela`** em `/reembolso`, `/termos-de-uso` e `/privacidade`.
- [ ] **Step 7: commit** — `docs: refund policy, terms and privacy texts for the refund request (#213)`

### Fechamento

- [ ] `just check-rapido`; skill `verificar-entrega` (mostrar a tabela); `/code-review`; corrigir o que aparecer.
- [ ] Push e PR com `Closes #213` (OK do Sérgio dado em 19/11/2026, junto com o do plano), citando FR-023, FR-025, SEC-011, SEC-029 e SEC-036; a PR pede o OK final dos textos legais. O merge fica com o Sérgio; o ticket só está pronto com o CI verde.

## Riscos

- **Solicitação parada**: se o estorno não for feito no Asaas, o Acesso fica suspenso e o Cliente sem o dinheiro. Mitigação neste ticket: o Alerta `REFUND_REQUESTED` e o filtro `Aguardando devolução` como padrão. Um aviso de "solicitação aberta há mais de 2 dias úteis" fica como sugestão para depois.
- **Estorno feito na cobrança errada ou parcial** (opção B): o parcial fica registrado e alerta; a cobrança errada reembolsa outro Pedido. O painel mostra o id da cobrança e o valor para reduzir o erro.
- **`Orders.Row` muda** (dois campos novos): dois testes constroem o record à mão e precisam acompanhar.
- **Textos legais**: a Política passa a prometer prazos (7 dias, 2 dias úteis); continua marcada como rascunho para o advogado.
- **Não testado por este plano**: o estorno de verdade no Asaas (sandbox) não muda e não é reexecutado; o e2e usa o provedor fake local e o stub no CI.
