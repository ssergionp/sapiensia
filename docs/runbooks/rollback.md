# Rollback: voltar a versão anterior

Como voltar o app no Fly.io para a imagem anterior e o que fazer com o banco. Complementa [homologacao-fly.md](homologacao-fly.md) (leia as regras de lá: todo comando `fly` só com a autorização do responsável, sempre `-c fly.homolog.toml`, todo deploy com `--ha=false`) e [incidente.md](incidente.md).

**O teste deste procedimento na homologação ainda NÃO foi feito.** Ele mexe no ambiente real e precisa da autorização do responsável. Os comandos abaixo vêm da documentação do Fly, não de uma execução nossa. Veja "Registro do teste na homologação" no fim.

## Voltar a imagem ou corrigir para a frente?

- **Volte a imagem** quando o problema começou logo depois de um deploy e a versão anterior funcionava. É rápido e não mexe no banco.
- **Corrija para a frente** (novo commit, PR, deploy) quando a causa não é só o código novo, quando o banco já mudou de um jeito que a imagem anterior não entende (seção "Migrations") ou quando o conserto é pequeno e óbvio.
- Na dúvida, volte a imagem para estancar e corrija depois com calma.

## Voltar a imagem no Fly

Cada passo precisa da autorização do responsável.

1. Liste os releases com a imagem de cada um ([`fly releases`](https://docs.fly.io/flyctl/releases)).

   ```powershell
   fly releases --image -c fly.homolog.toml
   ```

   Mostra os releases do app, com a referência da imagem Docker de cada um. Anote a imagem do último release que funcionava (a de antes do deploy ruim).

2. Faça o deploy dessa imagem ([`fly deploy --image`](https://docs.fly.io/flyctl/deploy)).

   ```powershell
   fly deploy --image <referência da imagem anterior> -c fly.homolog.toml --ha=false
   ```

   Coloca no ar a imagem que você anotou, sem construir nada de novo. O `--ha=false` é obrigatório: uma só máquina por ambiente, porque sessão e contadores de login ficam em memória (ADR-0004). Se o Fly recusar alguma flag, **a conferir** com `fly deploy --help`.

3. Confira como em [homologacao-fly.md](homologacao-fly.md), seção "Conferências" (health em `/api/system/health`, log sem erro).

Depois: abra uma issue para o defeito da imagem ruim e não faça novo deploy dela.

## Migrations: o banco não volta sozinho

- O Flyway Community **não tem "undo"**: o comando `undo` e os scripts `U` são de edição paga ([documentação do Flyway sobre migrations de undo](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/undo-migrations); a edição exata, Teams ou Enterprise, varia entre as páginas: **a conferir**). Não use `flyway undo` nem apague linha de `flyway_schema_history`.
- **Regra expand/contract:** toda migration nova precisa funcionar também com a imagem anterior. Primeiro adicione (coluna nova opcional, tabela nova) e publique o código que a usa; só numa migration posterior remova o que ficou sem uso. Assim, voltar a imagem não quebra contra o banco já migrado. Ao revisar uma migration, pergunte: "a imagem de ontem ainda roda com este banco?".
- **Corrigir o banco = migration nova** que desfaz o efeito da anterior (skill `nova-migration`, com a numeração do projeto). Nunca edite uma migration já aplicada.
- Se a imagem anterior não roda no banco já migrado, não dá para voltar: corrija para a frente.
- **Restaurar backup é o último caso.** Perde tudo o que aconteceu depois do último backup (RPO de até 24 horas, ADR-0002), inclusive Pedidos pagos; a conciliação noturna não recria Pedido (veja [conciliacao-asaas.md](conciliacao-asaas.md)). O backup noturno no Cloudflare R2 vem com o #40: só considere esta opção quando o #40 estiver pronto, e só com o responsável. Um backup restaurado precisa da mesma `APP_MFA_ENCRYPTION_KEY` (ADR-0004).

## Registro do teste na homologação

**Ainda não foi feito.** Precisa da autorização do responsável. Quando for feito, preencha uma linha por teste:

| Data | Imagem de | Imagem para | Resultado | Quem |
|---|---|---|---|---|
| | | | | |

O critério do #46 "rollback testado na homologação" fica aberto até esta tabela ter uma linha com resultado bom.
