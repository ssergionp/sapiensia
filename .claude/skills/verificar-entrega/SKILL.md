---
name: verificar-entrega
description: Confere a entrega de um ticket antes de declarar que terminou, antes do push e antes da PR - check verde e recente, arquivos fora do plano, teste afrouxado, tela e sistema de verdade, com prova. Use sempre que for dizer que um ticket está pronto.
---

# Verificar a entrega

Pronto não é "o código parece certo": é cada conferência abaixo rodada e o resultado mostrado.

1. Se ainda não rodou depois da última edição de código, rode `just check-resumo`.
2. Rode `node .claude/skills/verificar-entrega/conferir.mjs` na pasta do ticket (a worktree) e leia o relatório inteiro.
3. Para cada linha `FORA DO PLANO`:
   - se a mudança era necessária para o ticket, explique em uma frase por quê;
   - se não era, desfaça só aquele arquivo e rode o check de novo.
4. Para cada `TESTE APAGADO`, `CASOS DE TESTE A MENOS`, `VERIFICAÇÕES A MENOS` ou `TESTE DESLIGADO`:
   - se foi de propósito (teste duplicado, comportamento que o ticket mudou), explique em uma frase;
   - se foi só para o check passar, desfaça e corrija o código, não o teste.
5. Para cada `REQUISITO SEM TESTE`: escreva o teste que prova aquele requisito, com o ID no título (por exemplo, `@DisplayName("SEC-011: outro usuário não vê o pedido")`), ou explique em uma frase por que ele não se testa neste ticket.
6. Se o relatório diz que o `/verificar-tela` ou o teste com o sistema de verdade é obrigatório, confirme que você fez nesta branch; se não fez, faça agora.
   - Sistema de verdade é: banco, API e frontend rodando juntos, e o fluxo principal do ticket clicado no navegador, sem API simulada (nada de `page.route`, stub ou mock).
   - Não contam: chamar a API sozinha (curl, proxy do Vite) nem a tela com API simulada. Se não der para rodar o sistema inteiro, a linha fica "falta" e você para e avisa o usuário.
7. Ticket importante (a issue tem o rótulo `importante`, ou mexe em login, permissões, dinheiro ou dados pessoais): rode `/code-review` e trate cada achado: corrija os do próprio ticket; o resto vai para a issue de pendências.
8. Mostre uma tabela curta:

| Conferência | Resultado | Prova |
|---|---|---|
| just check-resumo | OK ou REPROVADO | linha do resumo |
| Arquivos fora do plano | nenhum, ou a lista com o motivo | relatório |
| Testes afrouxados | nenhum, ou a lista com o motivo | relatório |
| Requisitos da spec | todos com teste, ou a lista com o motivo | relatório |
| /verificar-tela | feito, não se aplica ou falta | onde |
| Sistema de verdade | feito, não se aplica ou falta | os passos clicados e o que apareceu |
| /code-review | feito, não se aplica ou falta | achados |

Só diga que o ticket está pronto, faça push ou abra a PR quando todas as linhas estiverem OK, feitas ou explicadas.
