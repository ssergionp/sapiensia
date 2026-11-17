# #156 Flaky AdminAlertOriginsTest.shouldRecordTheNameOfADroppedEmailTask

Plano curto, auto-aprovado pelo responsável no pedido da sessão.

Requisitos: ADR-0005 (o teste afetado), NFR-009 (alertas ao Administrador).

## Causa

O teste espera "5 tentativas de envio e nenhuma linha em `scheduled_tasks`" e logo confere a linha `TASK_GAVE_UP` em `admin_alerts`.
No db-scheduler 16.12.0, `FailureHandler.MaxRetriesFailureHandler.onFailure` chama `executionOperations.stop()` (apaga a linha de `scheduled_tasks`) **antes** do callback do `thenRemove`, que é quem grava o alerta (`CustomerEmailTasks.retryThenDrop` → `AdminAlerts.recordSafely`; conferido no bytecode).
Entre os dois passos há uma janela em que a tarefa já sumiu e o alerta ainda não existe; se a espera (que olha a cada 100 ms) cai nela, a asserção vê 0 linhas.
É o que aconteceu no CI (run 43975858986: `Expected size: 1 but was: 0`). O código de produção está certo: o alerta é gravado; só o sinal que o teste espera é o errado.

## O que muda

Só `backend/src/test/java/br/com/sergio/sapiensia/payment/AdminAlertOriginsTest.java`:

1. Reproduzir (RED): no teste, um trigger `before insert` em `admin_alerts` com `pg_sleep(0.5)` alarga a janela; com a espera atual o teste falha sempre com `Expected size: 1 but was: 0`. O trigger fica no teste (removido no `finally`), para a espera nunca mais depender de tempo.
2. Corrigir (GREEN): esperar o resultado que o teste confere (a linha `TASK_GAVE_UP` existe), e depois conferir as mesmas coisas de antes: tarefa removida (`droppedTasks() == 0`), pelo menos 5 tentativas, exatamente 1 linha com o nome da tarefa e sem Pedido nem evento.

Nenhuma asserção afrouxada, nenhum timeout aumentado. Outros testes com espera em `scheduled_tasks` (Administrator*Test, *CleanupTest) esperam tarefas concluídas, não o callback de desistência: sem a corrida.

## Testes

- O próprio teste, RED com o trigger e a espera antiga; GREEN com a espera nova; a classe inteira verde.
- `just check-rapido` uma vez.

## Fora do escopo

- Erro de rede ao baixar o Maven no job e2e: só registrado na issue, sem mudança no CI.

## Textos que o usuário vê

Nenhum.
