# #44 Teste de carga (preparação e validação local)

Plano curto, auto-aprovado pelo responsável no pedido da sessão. Decisões conservadoras estão marcadas **conferir**.

Requisitos: NFR-001 (disponibilidade 99,5%), NFR-002 (p95 < 500 ms e p99 < 1,5 s em endpoints comuns sob carga nominal), NFR-010 (carga nominal e de pico definidas). Referência do ticket: 50 usuários simultâneos e 20 compras por hora no pico.

**Fora deste ticket (precisa de autorização do responsável):** rodar contra a homologação e contra o sandbox do Asaas. Aqui o script roda só contra o ambiente local; a PR usa `Refs #44`, porque o critério "executado na homologação" fica aberto.

## O que muda

1. `scripts/carga/cenarios.js` (k6): quatro cenários com as metas como `thresholds`.
   - **Catálogo** (visitante): lista, detalhe e capa; maior parte dos usuários.
   - **Login** (Cliente): `POST /api/sessions` com CSRF, depois a sessão atual.
   - **Biblioteca e Download** (Cliente logado): `GET /api/library`, `POST /api/library/{productId}/download`; respeita o limite de 10 Downloads por dia por Cliente e Produto (cada conta faz no máximo 8 por execução).
   - **Pedido**: taxa de chegada constante de 20 compras por hora (`constant-arrival-rate`, `timeUnit: 1h`), criando o Pedido e lendo o Pedido criado (local: `FakePixGateway`, sem Asaas).
   - Perfil: subida de 1 min até 50 usuários simultâneos, 8 min de platô, 1 min de descida (**conferir**); duração e quantidade configuráveis por variável de ambiente para a validação local curta.
   - Metas (`thresholds`), por tag de grupo:
     - endpoints comuns (Catálogo, sessão atual, Biblioteca, leitura do Pedido): p95 < 500 ms e p99 < 1500 ms (NFR-002);
     - login e criação do Pedido medidos à parte, sem meta de NFR-002 (o login é lento de propósito pelo hash da senha; o Pedido chama o provedor de Pix) — **conferir**;
     - erros (`http_req_failed`, 5xx e falhas de rede) < 0,5% como aproximação dos 99,5% da NFR-001 — **conferir**;
     - respostas `429` contadas numa métrica própria e não tratadas como erro do servidor (são o limite funcionando), mas listadas no resultado.
2. `scripts/carga/preparar.mjs` (Node, sem dependência nova): só contra `http://127.0.0.1|localhost` (recusa outro endereço, como o `demo-pagar.mjs`). Cria N contas confirmadas (cadastro + link da caixa de saída local `.tmp/outbox`), compra para cada uma um Produto publicado e marca o Pedido como pago reaproveitando o `demo-pagar.mjs`, e grava `.tmp/carga/contas.json` (fora do git) com e-mail, senha e id do Produto. Partes puras testadas em `scripts/carga/preparar.test.mjs` (`just test-scripts` passa a incluir `scripts/carga/*.test.mjs`).
3. Execução do k6 por Docker (imagem oficial `grafana/k6` com versão fixa publicada há mais de 7 dias — **conferir** a versão na página oficial), sem instalar nada no computador: receita `just carga` que roda o cenário contra a API local (`host.docker.internal:<porta da API>`), com resumo em `.tmp/carga/resultado.json`. O script k6 também recusa um `BASE_URL` que não seja local, salvo `CARGA_PERMITIR_REMOTO=sim`, para não atingir a homologação por engano.
4. `docs/runbooks/teste-de-carga.md`: como preparar e rodar localmente; o que muda para rodar na homologação (com autorização) e os pontos a decidir; tabela de resultado (local agora; homologação depois); como transformar um gargalo em issue `needs-triage`.

## Decisões conservadoras (conferir)

- **conferir** k6 pela imagem Docker oficial com versão fixa, em vez de instalar o k6 no Windows.
- **conferir** Perfil de 10 min (1 + 8 + 1) e a divisão dos 50 usuários: 35 no Catálogo, 15 Clientes logados (Biblioteca e Download), mais a taxa de 20 Pedidos por hora.
- **conferir** Meta de erro < 0,5% como aproximação da NFR-001 (a NFR-001 é mensal; um teste de 10 min não a mede).
- **conferir** Login e criação do Pedido fora da meta de NFR-002, medidos e registrados.
- **Homologação (decisão para quando autorizar):** a partir de um só IP, os limites por IP de produção bloqueiam o teste (login: 20 tentativas por 15 min; Pedido: 30 por 10 min; leitura do Pedido: 120 por 10 min; Download: 60 por 10 min). Opções: elevar o `APP_RATE_LIMIT_IP_CAPACITY_FLOOR` e o limite de login só durante o teste, ou aceitar os `429` e medir só o que passa. As contas na homologação não se confirmam pela caixa de saída local (o e-mail sai pelo Resend) e as compras passam pelo sandbox do Asaas: o preparo de lá fica descrito, não automatizado.
- O resultado local valida o script, não a meta: a máquina local não é a homologação. Fica registrado como "validação do script".

## Textos que o usuário vê

Nenhum na aplicação. Mensagens dos scripts em português (erro de endereço não local, resumo).

## Testes

- `node --test scripts/carga/*.test.mjs`: recusa de endereço não local, montagem do `contas.json`, leitura do link de confirmação.
- Validação real: banco + API local (`just db`, `just dev-back`), `node scripts/carga/preparar.mjs`, `just carga` numa versão curta (ex.: 2 min, 10 usuários) e depois o perfil completo local; registrar o resumo no runbook.
- `just check-rapido`.

## Riscos

- Carga local disputa CPU com o próprio k6 e o Docker: números locais só servem de referência.
- Download e Pedido gravam linhas reais no banco local (é o banco de desenvolvimento; nada de produção).
