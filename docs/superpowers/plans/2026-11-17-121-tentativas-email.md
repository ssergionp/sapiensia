# #121 — Tarefas de e-mail do #9 executam 6 vezes em vez de 5

Origem: #29. Refs #9, #29, ADR-0005.

Só backend; sem migration; nenhum texto novo.

## Task 1: `maxRetries(maxAttempts - 1)` nas duas classes, com teste que conta as tentativas

Arquivos: `backend/src/main/java/br/com/sergio/sapiensia/customer/CustomerEmailTasks.java`, `backend/src/main/java/br/com/sergio/sapiensia/admin/AdministratorEmailTasks.java` e os testes `backend/src/test/java/br/com/sergio/sapiensia/customer/CustomerEmailTest.java` e `backend/src/test/java/br/com/sergio/sapiensia/admin/AdministratorEmailTest.java` (ou a classe que já testa o envio de cada uma; usar a que existir).

- Nas duas classes, `FailureHandler.maxRetries(maxAttempts)` vira `maxRetries(maxAttempts - 1)`, como em `OrderPaidEmailTasks` e `PaymentEventTasks`. O total de execuções passa a ser `app.email.max-attempts` (5), como dizem o log e o ADR-0005.
- Testes (cada um falha pela asserção antes do código, vendo 6), no modelo de `OrderPaidEmailTest` ("quando o envio falha de vez", que conta `sender.attemptsTo(...)`):
  1. `quando o envio de um e-mail ao Cliente falha de vez, ele é tentado exatamente max-attempts vezes` (uma tarefa da `CustomerEmailTasks`, por exemplo a confirmação de cadastro).
  2. `quando o envio de um e-mail ao Administrador falha de vez, ele é tentado exatamente max-attempts vezes` (uma tarefa da `AdministratorEmailTasks`, por exemplo a definição de senha).

## Decisões

| ID | Decisão | Marca |
|----|---------|-------|
| D1 | Corrigir o código para 5 tentativas (o que o ADR-0005 e o log dizem), em vez de mudar o texto para 6 | DECIDIDO NO MODO CONTÍNUO — conferir |

## Textos que o usuário vê

Nenhum.

## Riscos

- O #73 (PR #166, ainda aberta) reorganizou o `AdministratorEmailTasks` num método genérico. Quem for mergeado depois resolve o conflito mantendo `maxAttempts - 1` no método genérico.

## Verificação

Os dois testes e as classes de e-mail tocadas, um Maven por vez; `just lint-back`; `just check-rapido` no fim (se morrer por memória, vale o CI).
