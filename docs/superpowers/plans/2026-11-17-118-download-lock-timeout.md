# #118 — Pendências do #34: a espera da trava do Download segura conexão do banco

Origem: revisão final do #34 (PR #117). Refs #34.

Só backend; sem migration (a V34 reservada não é usada); nenhum texto novo.

## Task 1: limite de espera pela trava do Acesso

Arquivos: `backend/src/main/java/br/com/sergio/sapiensia/product/ProductDownloadService.java`, `backend/src/main/java/br/com/sergio/sapiensia/product/ProductDownloads.java`, um teste de integração novo `backend/src/test/java/br/com/sergio/sapiensia/payment/DownloadLockTimeoutTest.java` (no pacote `payment`, onde ficam os utilitários `OrderHttp`/`OrderTestSupport`) e `backend/src/test/java/br/com/sergio/sapiensia/product/SupabaseProductFileStorageWireTest.java` (só a limpeza do `java.util.Optional` com nome completo, ~linha 214, trocado por import).

- Primeira instrução da transação de `ProductDownloadService.issue`: `set local lock_timeout = '3s'` (um método em `ProductDownloads`, por exemplo `limitLockWait()`). Assim, o pedido que espera a trava do Acesso (`for update of a`) desiste em 3 s com erro do banco. Esse erro vira o `503` sem corpo que o `LibraryDownloadController` já devolve para `DataAccessException`. O `set local` vale só para essa transação.
- Javadoc da classe: uma frase dizendo que a espera pela trava é limitada a 3 s.
- Teste (falha pela asserção antes do código; citar os IDs de requisito que os testes de Download já citam):
  - `um pedido que espera a trava do Acesso por mais de 3 s recebe 503 e não grava Registro de download`: uma transação em outra thread trava a linha do Acesso (`select ... for update`) e a segura por ~6 s; o `POST /api/library/{id}/download` do dono responde `503` em menos de ~5 s, e `download_records` não ganha linha. Depois que a trava é solta, um novo pedido responde `200`.
  - Sem o `lock_timeout`, o pedido esperaria os ~6 s e responderia 200: é isso que torna o teste vermelho antes do código (conferir o tempo e o status).

## Fora deste ticket (decisão)

- O teste de tempo limite do `SupabaseProductFileStorageWireTest` ("< 7 s") **não** é afrouxado.
- `DownloadButton.tsx` continua tratando todo 429 como limite diário: hoje só o limite diário responde 429 nessa rota, e ler `errors.download` seria código para um caso que ainda não existe.

## Decisões

| ID | Decisão | Marca |
|----|---------|-------|
| D1 | `lock_timeout` de 3 s só na transação do Download, por `set local` (não no pool inteiro) | DECIDIDO NO MODO CONTÍNUO — conferir |
| D2 | Do item 2 da issue, só a limpeza do `Optional`; o resto fica de fora, pelos motivos acima, sem issue nova (a #118 é a própria lista de pendências e será fechada) | DECIDIDO NO MODO CONTÍNUO — conferir |

## Textos que o usuário vê

Nenhum novo: o 503 já mostra a mensagem atual do botão Baixar ("Não foi possível preparar o download. Tente novamente.").

## Verificação

`-Dtest=DownloadLockTimeoutTest,ProductDownloadServiceTest,DownloadDailyLimitTest,SupabaseProductFileStorageWireTest` e as classes que citam os mesmos IDs; `just lint-back`; `just check-rapido` no fim.
