# Auditoria de acessibilidade das jornadas de compra e Download

O que é verificado sozinho, o que o resultado de 16/11/2026 mostrou e o roteiro para você conferir à mão o que a máquina não vê. Origem: #45 (NFR-005, FR-033). A execução manual está na issue #143.

Legenda: **[RESPONSÁVEL]** você faz, com teclado, NVDA e celular.

## O que é verificado automaticamente

- O **axe** roda dentro do teste e2e, na tela já carregada, em 12 telas das duas jornadas (compra e Download), no projeto `desktop`.
- As regras são só as de WCAG A e AA: tags `wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa` e `wcag22aa`. Regras `best-practice` ficam de fora.
- O helper é `frontend/e2e/a11y.ts` (`expectNoA11yViolations(page, "<tela>")`). As chamadas estão em `frontend/e2e/admin-panel.e2e.ts`.
- Se achar violação, o teste falha e mostra a regra, o impacto, o link da regra e até 5 seletores. Nunca mostra o HTML do nó, porque ele pode ter dado de cliente.
- As 12 telas: `cadastro`, `entrar`, `inicio`, `catalogo`, `produto`, `comprar-pix`, `pedido-pendente`, `pedido-pago`, `meus-pedidos`, `biblioteca`, `download-iniciado`, `download-limite`.

### Como acrescentar uma exceção

1. Abra uma issue `needs-triage` ("Acessibilidade: <regra> em <tela>") com a regra, o impacto, os seletores, o critério WCAG e a origem #45.
2. Acrescente uma linha em `KNOWN_ISSUES`, em `frontend/e2e/a11y.ts`: `{ rule: "<id da regra do axe>", screen: "<tela>", issue: <número> }`.
3. A exceção vale só para aquela regra naquela tela. Nunca desligue uma regra inteira.

### Como rodar só esses testes

Antes: `just db`, `just dev-back` e `just dev-front` (banco, API e frontend no ar).

```powershell
cd frontend
pnpm exec playwright test --project=desktop -g "NFR-005"
```

Esse comando roda só os testes que levam `NFR-005` no nome: os dois de jornada (compra e Download), que têm os scans, e o teste do próprio helper (`frontend/e2e/a11y-helper.e2e.ts`), que prova que ele reprova uma violação conhecida (`image-alt`).

## Resultado de 16/11/2026

Execução local no desktop, com o filtro antigo `-g "FR-016|FR-034"` (que também pegou outros 5 testes do painel): 7 testes passaram, entre eles os dois de jornada. Foram 12 scans, todos com **0 violações**. `KNOWN_ISSUES` está vazia e nenhuma correção foi necessária.

| Tela | Violações |
|---|---|
| cadastro | 0 |
| entrar | 0 |
| inicio | 0 |
| catalogo | 0 |
| produto | 0 |
| comprar-pix | 0 |
| pedido-pendente | 0 |
| pedido-pago | 0 |
| meus-pedidos | 0 |
| biblioteca | 0 |
| download-iniciado | 0 |
| download-limite | 0 |

### O que esse resultado não exercitou

- O Produto de teste **não tinha capa**. Catálogo e biblioteca foram escaneados sem imagem, então o texto alternativo da capa não foi checado pelo axe nessas telas.
- **Celular**: o teste das jornadas só roda no projeto `desktop`. Nenhum scan rodou em tela estreita.

## O que o axe não cobre

O axe acha cerca de um terço dos problemas de acessibilidade. Ficam para o roteiro abaixo:

- ordem de foco e armadilhas de teclado;
- foco visível em cada controle;
- o que o leitor de tela anuncia (NVDA + Chrome no Windows);
- zoom de 200% e espaçamento de texto;
- título da aba por página;
- link para pular ao conteúdo;
- as telas no celular;
- os critérios do WCAG 2.2 que não dá para automatizar.

## Observações já conhecidas pela leitura do código

Vistas só no código, **não verificadas**. A conferir na auditoria manual:

1. Não há link para pular ao conteúdo.
2. O título da aba é sempre "SapiensIA", em qualquer página.
3. As páginas de erro e de "não encontrado" não movem o foco para o título.

## Roteiro manual

### Como preparar

1. Suba o sistema (`just demo`) e crie uma conta de cliente com um Produto à venda. Para pagar o Pedido, use `just demo-pagar <id do Pedido>`.
2. Use um Produto **com capa** e outro sem.
3. Teclado: só o teclado, sem o mouse. Tab avança, Shift+Tab volta, Enter ativa links e botões, Espaço ativa botões e caixas de seleção, Esc fecha o que abriu.
4. Leitor de tela: NVDA com Chrome, no Windows. Ligue e desligue com Ctrl+Alt+N. Navegue por Tab, pelas setas e pela tecla H (de título em título).
5. Celular: TalkBack (Android) ou VoiceOver (iPhone), no navegador do aparelho. Gire a tela e aumente a fonte do sistema.
6. Zoom: no Chrome, Ctrl + "+" até 200%. A tela não pode perder conteúdo nem ganhar rolagem horizontal.

### O que conferir em toda tela

- **Teclado:** todo controle recebe foco, na ordem em que aparece na tela. O foco aparece de forma visível. Nenhum controle prende o foco. Nada exige mouse.
- **NVDA:** o título da página (h1) é anunciado. Cada campo é anunciado com o seu rótulo. Mensagens de status e de erro são anunciadas sem o foco mudar.
- **Celular:** a tela cabe na largura, nada fica cortado e os alvos de toque são fáceis de acertar. O leitor de tela percorre tudo em ordem.
- **Zoom 200%:** nada se sobrepõe nem some.

### Por tela

| Tela | Teclado | NVDA + Chrome | Celular | Resultado |
|---|---|---|---|---|
| Cadastro `/cadastro` | Tab passa por cada campo e pelo botão de enviar; Enter envia; um erro de validação leva o foco ao campo com erro ou o anuncia | Título da página; cada campo com o rótulo; erro de campo anunciado | Teclado do aparelho certo para e-mail e senha; campos sem corte | |
| Entrar `/entrar` | Mesma conferência do cadastro; Enter envia; depois do login o foco não se perde | Título; rótulos; erro de login anunciado; "Você entrou como…" anunciado | Idem cadastro | |
| Início | Links da navegação em ordem lógica; Enter abre o catálogo | Título; navegação anunciada como navegação; nome do usuário logado | Menu usável com um dedo; sem corte | |
| Catálogo `/catalogo` | Cada Produto é um link alcançável; Enter abre; foco visível no cartão | Título; cada link com o nome do Produto; capa com texto alternativo (ou ignorada se decorativa) | Cartões em coluna; toque abre o Produto | |
| Produto `/catalogo/:id` | Tab chega ao botão "Comprar com Pix"; Enter ativa | h1 com o nome; preço anunciado junto ao Produto; botão com nome claro | Preço e botão visíveis sem rolar demais | |
| Comprar com Pix (CPF) | Tab chega ao campo CPF e ao botão de confirmar; Enter envia; erro de CPF leva ao campo | Rótulo do campo CPF; erro de CPF anunciado; sem "armadilha" de foco | Teclado numérico no campo CPF; campo sem corte | |
| Pedido pendente `/pedidos/:id` | Tab chega ao botão de copiar o código; Enter/Espaço copia; o código é selecionável | "Pague com Pix" como título; o código copia e cola anunciado ou lido; o **contador** e o **anúncio do Pedido pendente** são anunciados sem tirar o foco do lugar; o aviso de cópia é anunciado | Código legível e copiável; contador visível | |
| Pedido pago | Foco chega ao texto ou ao link de continuar sem passar por controles inúteis | A confirmação de pagamento é anunciada ao aparecer (sem recarregar a página); título correto | Mensagem e link visíveis | |
| Meus Pedidos `/pedidos` | Cada Pedido é alcançável por Tab; Enter abre o Pedido | Título; cada item com Produto, data e estado ("Pago"); lista anunciada como lista | Lista em coluna sem corte | |
| Biblioteca `/biblioteca` | Cada item e o botão de Download são alcançáveis; Enter/Espaço ativa | Título; item com o nome do Produto; botão de Download com o nome do Produto, não só "Baixar" | Botão fácil de tocar; capa com texto alternativo | |
| Download iniciado | Depois de ativar o botão, o foco continua no botão ou segue para a mensagem; o Esc não é necessário | "Download iniciado…" anunciado sem mover o foco | Mensagem visível e anunciada; o arquivo baixa | |
| Limite do Download | Depois do 11º clique, o foco não se perde; o alerta é alcançável | O alerta do limite é anunciado na hora (papel de alerta) e dá para ler de novo com as setas | Alerta visível e anunciado | |

### Passos extras, uma vez só

| Conferência | Como | Resultado |
|---|---|---|
| Link para pular ao conteúdo | Abra qualquer página e aperte Tab uma vez. Tem que aparecer um link "pular" antes da navegação. | |
| Título da aba | Passe por 5 telas e leia o título da aba de cada uma. Tem que ser diferente em cada tela. | |
| Foco em erro e "não encontrado" | Abra `/catalogo/00000000-0000-0000-0000-000000000000` e uma rota que não existe. O foco tem que ir para o título ou o NVDA tem que anunciar o erro. | |
| Zoom 200% e espaçamento de texto | Zoom 200% nas 12 telas; depois aplique espaçamento de texto maior (extensão "Text Spacing"). Nada pode se sobrepor, sumir nem cortar. | |
| Alvos de toque (WCAG 2.2) | No celular, confira botões e links pequenos: devem ter pelo menos 24 por 24 pixels ou espaço em volta. | |
| Foco não encoberto (WCAG 2.2) | Com Tab, confira que um cabeçalho fixo ou um aviso nunca cobre o controle focado. | |

## Quando terminar

- Preencha a coluna "Resultado" (OK, ou o que foi errado).
- Abra uma issue `needs-triage` por problema, citando a tela, o critério e #45.
- Se um problema pedir texto novo de tela, ele volta para o Sérgio decidir antes de corrigir.
- Se passar a existir uma violação que o axe já detecta e que não dá para corrigir agora, use a exceção da seção "Como acrescentar uma exceção".
- Telas novas numa jornada crítica ganham um `expectNoA11yViolations` no e2e.
