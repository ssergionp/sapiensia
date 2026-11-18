**SAPIENSIA**

Especificação Mestra Completa

*Plataforma inteligente de criação, publicação e comercialização de
conteúdo digital*

Versão 2.0 \| 28 de outubro de 2026

Documento consolidado para produto, arquitetura, segurança,
desenvolvimento e operação

# Controle do documento

| **Campo** | **Descrição**                                                                                         |
|-----------|-------------------------------------------------------------------------------------------------------|
| Nome      | SapiensIA — Especificação Mestra Completa                                                             |
| Versão    | 2.0                                                                                                   |
| Status    | Proposta consolidada para revisão e aprovação                                                         |
| Data      | 28/10/2026                                                                                            |
| Base      | Documento original project_master_specification.md e recomendações arquiteturais/de segurança         |
| Público   | Produto, arquitetura, desenvolvimento, QA, segurança, operações, jurídico e responsáveis pelo negócio |

Convenção: requisitos marcados como MUST/OBRIGATÓRIO são condições de
implementação. Metas numéricas identificadas como propostas devem ser
validadas em descoberta, testes de carga e análise de custos. Decisões
de negócio ainda não tomadas aparecem como decisões pendentes.

# 1. Resumo executivo

SapiensIA é uma plataforma web responsiva e PWA para criação assistida
por inteligência artificial, revisão, publicação, comercialização e
leitura de e-books, cartilhas, tutoriais, checklists e conteúdos
digitais correlatos. O produto integra backoffice editorial,
orquestração de IA, catálogo, checkout, entrega segura, biblioteca do
cliente, acessibilidade, comunicação e governança operacional.

> **Adiado (ADR-0008, 17/11/2026).** A criação e a revisão de conteúdo por IA dentro da plataforma saem do plano atual: o SapiensIA só publica e vende material pronto (PDF + capa). As partes de IA deste documento ficam preservadas, marcadas como adiadas.

A IA é um componente probabilístico e um fornecedor externo; não deve
ser tratada como fonte infalível. Conteúdos factuais precisam de fontes
rastreáveis e controles de qualidade. Categorias de maior impacto — como
saúde, saúde mental, direito, finanças e segurança — exigem política
editorial e revisão humana proporcional ao risco.

## 1.1 Objetivos

- Reduzir o esforço de produção editorial sem comprometer a
  confiabilidade.

- Permitir geração estruturada, revisão por etapas, tradução e
  versionamento.

- Vender e entregar produtos digitais com fluxo financeiro verificável e
  auditável.

- Oferecer leitura acessível, segura e consistente em desktop e
  dispositivos móveis.

- Controlar custo, abuso e qualidade das chamadas a modelos de IA.

- Atender às obrigações aplicáveis de privacidade, direitos autorais e
  comércio eletrônico.

- Manter arquitetura evolutiva, observável, testável e operável por
  equipe enxuta.

## 1.2 Princípios

- Segurança e privacidade desde o desenho; menor privilégio e negação
  por padrão.

- Separar pagamento, pedido, direito de acesso e entrega de arquivos.

- Processamento assíncrono durável, idempotente e recuperável.

- Rastrear fontes, versões, custos, aprovações e ações administrativas.

- Começar com monólito modular e extrair serviços apenas quando houver
  justificativa medida.

- Não afirmar conformidade, segurança ou qualidade sem evidências
  verificáveis.

- Minimizar dados pessoais e evitar coleta desnecessária de CPF ou
  outros identificadores.

- Usar fornecedores substituíveis por adaptadores internos sempre que
  viável.

# 2. Escopo e decisões de produto

## 2.1 Escopo funcional previsto

- Backoffice para usuários administrativos, conteúdo, fontes, revisões,
  publicação e métricas.

- Geração de sumário e capítulos com IA, RAG, revisão humana e controle
  de custos.

- Upload de documentos de referência, extração e indexação controladas.

- Catálogo multilíngue, filtros, amostras e preços localizados.

- Checkout com Pix e integração inicial com um provedor de pagamento.

- Pedidos, eventos de pagamento, conciliação, reembolso e
  concessão/revogação de acesso.

- Biblioteca do usuário, leitura web, progresso, favoritos, notas e
  downloads autorizados.

- Geração de PDF e EPUB, com metadados e validações de saída.

- E-mail transacional e, em fase posterior, integração oficial de
  WhatsApp.

- Auditoria, métricas, logs, alertas, backup, recuperação e pipeline de
  segurança.

## 2.2 Fora do MVP, salvo aprovação

- Marketplace aberto com autores terceiros, comissões e repasses.

- Transferências bancárias de saída ou custódia de recursos de clientes.

- Aplicativos nativos independentes para iOS e Android.

- DRM proprietário avançado, além de controles proporcionais de acesso e
  marca d'água.

- Assinaturas recorrentes, afiliados, comunidade e certificação
  educacional.

- Diagnóstico clínico ou aconselhamento jurídico/financeiro
  individualizado.

- Publicação automática de conteúdo de alto impacto sem revisão.

## 2.3 Decisões pendentes obrigatórias

1.  Modelo comercial: venda avulsa, pacote, assinatura, cupom, presente
    e/ou licença institucional.

2.  Papéis do produto: operação interna ou autores/editores externos com
    múltiplos tenants.

3.  Formatos do MVP: PDF, EPUB, HTML, áudio e requisitos de
    acessibilidade de cada formato.

4.  Mercados, moedas, tributos, regras de reembolso e obrigações de
    consumidor.

5.  Direito de acesso a versões futuras: vitalício, período definido ou
    conforme produto.

6.  Categorias sujeitas a revisão especializada e avisos obrigatórios.

7.  Volume nominal e de pico: usuários, pedidos, documentos, páginas e
    gerações por dia.

8.  Orçamento de infraestrutura, observabilidade, IA e provedores
    externos.

9.  Política de uso de dados por fornecedores de IA e localização de
    dados.

10. Papel do WhatsApp: suporte, marketing ou ambos; consentimentos e
    descadastro.

# 3. Perfis e autorização

| **Perfil**         | **Permissões típicas**                                                     | **Restrições**                                               |
|--------------------|----------------------------------------------------------------------------|--------------------------------------------------------------|
| Visitante          | Catálogo público e amostras autorizadas                                    | Sem dados privados ou operações de conta                     |
| Cliente            | Conta, compras, biblioteca, progresso, notas e solicitações de privacidade | Somente recursos próprios e produtos licenciados             |
| Editor             | Criar e editar rascunhos, gerenciar fontes autorizadas                     | Não publica categorias sensíveis sem aprovação               |
| Revisor            | Comentar, aprovar ou rejeitar conteúdo                                     | Acesso limitado ao conteúdo designado                        |
| Administrador      | Configurações, usuários e permissões privilegiadas                         | MFA e reautenticação em operações sensíveis                  |
| Financeiro/Suporte | Tarefas de cobrança e suporte estritamente necessárias                     | Sem acesso amplo a conteúdo privado ou segredos              |
| Serviço de sistema | Operações técnicas explicitamente autorizadas                              | Identidade própria, sem privilégios humanos ou acesso global |

O sistema não deve limitar a autorização a ROLE_ADMIN e ROLE_USER. Deve
implementar permissões por capacidade e escopo, verificadas no servidor
em cada operação. A criação, aprovação e publicação de conteúdo de alto
impacto devem ser separáveis quando o risco justificar.

# 4. Arquitetura de referência

## 4.1 Estratégia

Adotar inicialmente um monólito modular Spring Boot, com workers
assíncronos separados logicamente e, se necessário, implantados em
processos independentes. Microserviços devem ser extraídos somente
diante de necessidade comprovada de escala independente, isolamento de
risco ou operação.

## 4.2 Stack proposta

| **Camada**      | **Tecnologia/abordagem**                                                  | **Observações**                                                                   |
|-----------------|---------------------------------------------------------------------------|-----------------------------------------------------------------------------------|
| Frontend        | Next.js, React, TypeScript, PWA, Tailwind, next-intl                      | Acessibilidade e validação de entrada; segredos nunca no cliente                  |
| Backend         | Java LTS suportado, Spring Boot, Spring Security                          | Fixar versões compatíveis e manter política de atualização                        |
| Domínio         | Módulos de catálogo, editorial, pedidos, pagamentos, usuários, biblioteca | Regras de negócio independentes de SDKs e HTTP                                    |
| Fila            | Fila durável com retry, dead-letter e monitoramento                       | Redis pode apoiar cache/rate limiting; não presumir durabilidade sem configuração |
| Banco           | PostgreSQL                                                                | Transações, constraints, índices, migrações versionadas                           |
| Objetos         | Armazenamento privado compatível com S3/Supabase                          | URLs assinadas curtas para conteúdo privado                                       |
| RAG/busca       | PostgreSQL com extensão vetorial inicialmente, se suficiente              | Isolamento por ACL e avaliação de recuperação                                     |
| Observabilidade | Logs estruturados, métricas, traces e alertas                             | Sem tokens ou PII desnecessários                                                  |
| Segredos        | Secret manager do provedor                                                | Rotação, escopo mínimo e separação de ambientes                                   |
| Integrações     | Adaptadores de pagamento, IA, e-mail e WhatsApp                           | Contratos, timeouts, retries e testes de integração                               |

## 4.3 Regras arquiteturais

- Regras de domínio não dependem de componentes HTTP ou SDKs externos.

- Operações críticas usam correlation ID e idempotency key quando
  aplicável.

- Persistir o estado de negócio antes de emitir eventos; avaliar padrão
  Outbox.

- Definir timeout, concorrência, tamanho máximo, retry e fallback para
  cada dependência.

- WebSocket serve para notificação, não como fonte de verdade; após
  reconectar, o cliente consulta o estado persistido.

- Usar ADRs para decisões relevantes, alternativas consideradas e
  consequências.

- Não compartilhar credenciais de produção com desenvolvimento ou
  homologação.

## 4.4 Processamento assíncrono

Virtual Threads facilitam concorrência de operações bloqueantes, mas não
substituem fila durável, persistência, retry ou recuperação. Cada
GenerationJob deve ter estados definidos, tentativas, timeout, custo
acumulado e política de cancelamento.

| **Estado**      | **Significado**                                    |
|-----------------|----------------------------------------------------|
| PENDING         | Criado e aguardando worker                         |
| RUNNING         | Em execução com lease/heartbeat quando aplicável   |
| SUCCEEDED       | Concluído e validado                               |
| FAILED          | Falha final após política de tentativas            |
| RETRY_SCHEDULED | Nova tentativa agendada                            |
| CANCELLED       | Cancelado, registrando trabalho/custo já incorrido |

# 5. Requisitos funcionais

## 5.1 Fábrica editorial e IA
> **Adiado (ADR-0008, Fase 2, Fábrica de IA).** Os requisitos marcados como adiados (FR-001 a FR-006, FR-012, FR-013, FR-014 e FR-015) não valem para o lançamento e voltam só por uma decisão nova. FR-007 e FR-009 a FR-011 continuam valendo para o material enviado à mão (publicar continua exigindo PDF e capa). FR-008 (tradução) fica com a Fase 3.

| **ID** | **Requisito**           | **Critério resumido**                                                                                  |
|--------|-------------------------|--------------------------------------------------------------------------------------------------------|
| FR-001 (adiado, ADR-0008) | Assistente de criação   | Permitir tema, tipo, idioma, público, tom, objetivo, tamanho, estrutura e complexidade.                |
| FR-002 (adiado, ADR-0008) | Fontes de referência    | Registrar origem, título, data de captura, hash, direitos declarados e estado de processamento.        |
| FR-003 (adiado, ADR-0008) | Aprovação do sumário    | Editar, aprovar, reordenar, excluir e regenerar seções antes da redação integral.                      |
| FR-004 (adiado, ADR-0008) | Geração por etapas      | Gerar capítulos individualmente, persistir resultados parciais e retomar após falha.                   |
| FR-005 (adiado, ADR-0008) | Rastreabilidade factual | Associar afirmações relevantes a fontes/trechos quando disponíveis e sinalizar afirmações sem suporte. |
| FR-006 (adiado, ADR-0008) | Revisão editorial       | Comentários, comparação de versões, aprovação/rejeição e identidade do aprovador.                      |
| FR-007 | Política por categoria  | Exigir revisão humana proporcional ao risco para saúde, direito, finanças e segurança.                 |
| FR-008 | Tradução                | Preservar estrutura, glossário, links e referências; permitir revisão humana.                          |
| FR-009 | Exportação              | Gerar PDF/EPUB com metadados, sumário, links e validações de saída.                                    |
| FR-010 | Versionamento           | Versões publicadas imutáveis; nova edição gera nova versão e política de acesso definida.              |
| FR-011 | Publicação              | Bloquear publicação até concluir aprovações e validações requeridas.                                   |
| FR-012 (adiado, ADR-0008) | Telemetria de IA        | Registrar provedor, modelo, configuração, versão de template, tokens/custo, latência e resultado.      |
| FR-013 (adiado, ADR-0008) | Cancelamento            | Permitir cancelar quando tecnicamente possível e informar custos já incorridos.                        |
| FR-014 (adiado, ADR-0008) | Controle de custo       | Limites por tarefa, usuário, dia e mês; alerta, bloqueio ou aprovação ao exceder.                      |
| FR-015 (adiado, ADR-0008) | Avaliação de qualidade  | Executar testes de factualidade, citações, idioma, estrutura e conteúdo inseguro.                      |

## 5.2 Catálogo e comércio

| **ID** | **Requisito**         | **Critério resumido**                                                                                                    |
|--------|-----------------------|--------------------------------------------------------------------------------------------------------------------------|
| FR-016 | Catálogo              | Categorias, tags, idioma, público, formato, nível, preço e status.                                                       |
| FR-017 | Amostras              | Expor somente trechos explicitamente autorizados.                                                                        |
| FR-018 | Preços localizados    | Registrar preço, moeda, mercado, vigência e regra de arredondamento; não usar conversão implícita como preço definitivo. |
| FR-019 | Checkout              | Criar pedido e tentativa de pagamento com IDs únicos.                                                                    |
| FR-020 | Estado do pedido      | Modelar estados de pagamento, cumprimento, cancelamento, reembolso e disputa.                                            |
| FR-021 | Webhooks              | Validar autenticidade, persistir evento, deduplicar e processar efeitos de forma idempotente.                            |
| FR-022 | Conciliação           | Comparar pedidos e pagamentos com o provedor e sinalizar divergências.                                                   |
| FR-023 | Reembolso             | Fluxo autorizado, auditável e alinhado à política publicada.                                                             |
| FR-024 | Entrega               | Conceder acesso após confirmação válida e regras antifraude aplicáveis.                                                  |
| FR-025 | Revogação             | Atualizar acesso conforme estorno, chargeback, fraude ou cancelamento, respeitando obrigações legais.                    |
| FR-026 | Cupons e complementos | Validar validade, limites, cumulatividade e preço final no servidor.                                                     |
| FR-027 | Presente digital      | Token aleatório, de uso único, com expiração e revogação; sem PII ou segredo na URL.                                     |
| FR-028 | Exportação            | Exportar dados de negócio autorizados sob controles de privacidade.                                                      |

## 5.3 Biblioteca, leitor e acessibilidade

| **ID** | **Requisito**       | **Critério resumido**                                                       |
|--------|---------------------|-----------------------------------------------------------------------------|
| FR-029 | Biblioteca          | Listar produtos adquiridos, versão disponível, progresso e favoritos.       |
| FR-030 | Progresso           | Sincronizar leitura entre dispositivos autenticados.                        |
| FR-031 | Notas privadas      | Validar autorização por objeto em toda leitura, edição e exclusão.          |
| FR-032 | Áudio               | Informar idioma/voz, retomar reprodução e apresentar limitações.            |
| FR-033 | Acessibilidade      | Suportar teclado, leitores de tela, foco visível, contraste e preferências. |
| FR-034 | Download seguro     | Autorizar cada download e gerar URL assinada curta ou proxy controlado.     |
| FR-035 | Marca d'água        | Usar apenas dados mínimos necessários; não expor CPF completo por padrão.   |
| FR-036 | Direitos do titular | Permitir exportação, correção e exclusão conforme retenção legal.           |

## 5.4 Comunicação, suporte e recomendação

- FR-037 — Bot de suporte: restringir a intenções autorizadas, catálogo
  e documentação aprovada.

- FR-038 — Escalonamento humano: cobranças, fraude, privacidade, risco à
  saúde e casos complexos.

- FR-039 — WhatsApp: API oficial/provedor autorizado; respeitar
  políticas, templates, janela de atendimento e opt-out aplicáveis.

- FR-040 — Newsletter: consentimento de marketing separado de mensagens
  transacionais; descadastro simples.

- FR-041 — Recomendações: explicar a recomendação quando pertinente e
  permitir editar preferências.

- FR-042 — Radar de interesse: agregar pesquisas sem resultados e
  reduzir dados pessoais desnecessários.

# 6. Segurança — requisitos obrigatórios

A segurança deve ser verificada por evidências e testes. Usar OWASP ASVS
5.0.0 como catálogo de requisitos, OWASP API Security Top 10 para riscos
de API e OWASP GenAI Security Project para riscos de IA. O nível ASVS
pretendido deve ser formalmente escolhido e mapeado aos controles
abaixo.

## 6.1 Identidade, autenticação e sessão

- SEC-001 — MFA obrigatório para administradores; preferir
  passkeys/WebAuthn ou MFA resistente a phishing quando viável.

- SEC-002 — Política de senha, proteção contra credenciais comprometidas
  e limitação progressiva de tentativas.

- SEC-003 — Usar Argon2id com parâmetros calibrados ou algoritmo
  aprovado atualizado; nunca criar hashing próprio.

- SEC-004 — Preferir sessões seguras em cookies HttpOnly, Secure e
  SameSite apropriado. Se JWT for usado, documentar revogação, rotação,
  audience, issuer, expiração e armazenamento.

- SEC-005 — Tokens de acesso de curta duração e escopo mínimo; não
  armazenar tokens sensíveis em localStorage.

- SEC-006 — Invalidar sessões/refresh tokens após logout, troca de
  senha, recuperação, remoção de MFA e eventos de risco.

- SEC-007 — Recuperação com token aleatório, uso único, expiração curta,
  resposta que não revele existência da conta e notificação ao titular.

- SEC-008 — Reautenticação para operações administrativas sensíveis.

- SEC-009 — Permitir visualizar e revogar sessões.

## 6.2 Autorização e APIs

- SEC-010 — Negar por padrão e validar autorização no servidor em toda
  operação.

- SEC-011 — Prevenir BOLA/IDOR: validar propriedade/escopo para pedidos,
  notas, arquivos, biblioteca e tarefas.

- SEC-012 — Não confiar em IDs, roles, preços ou permissões enviados
  pelo cliente.

- SEC-013 — Validar esquema, tamanho, tipo e semântica; usar queries
  parametrizadas e escaping contextual.

- SEC-014 — Rate limiting por IP, conta, token, endpoint e operação
  cara; quotas específicas para IA.

- SEC-015 — Limitar paginação, filtros, payload, concorrência, duração e
  custo.

- SEC-016 — Inventariar e versionar endpoints; desativar rotas antigas;
  documentar API com OpenAPI.

- SEC-017 — CORS restrito, proteção CSRF quando aplicável, CSP, HSTS,
  X-Content-Type-Options e política de framing.

- SEC-018 — Não expor stack traces, segredos, PII ou detalhes internos
  em erros públicos.

- SEC-019 — Validar URLs e destinos para prevenir SSRF; bloquear redes
  privadas, loopback, metadata endpoints e redirecionamentos perigosos.

- SEC-020 — Restringir métodos HTTP e tipos de conteúdo aceitos.

## 6.3 Pagamentos e webhooks

- SEC-021 — Não armazenar senhas bancárias, chaves de saque ou
  credenciais de movimentação.

- SEC-022 — Guardar credenciais em secret manager, com escopo mínimo,
  rotação e separação por ambiente.

- SEC-023 — Validar assinatura conforme mecanismo oficial do provedor;
  verificar timestamp/janela contra replay quando suportado e corpo
  bruto quando exigido.

- SEC-024 — Não presumir que todos os provedores usam HMAC-SHA256;
  implementar o método documentado.

- SEC-025 — Deduplicar eventos por ID e processar idempotentemente.

- SEC-026 — Validar valor, moeda, pedido, recebedor e estado consultando
  o provedor quando necessário.

- SEC-027 — Nunca liberar produto com base apenas no redirecionamento do
  navegador ou parâmetro do cliente.

- SEC-028 — Registrar eventos financeiros append-only, com acesso
  restrito; considerar armazenamento externo imutável para evidências
  críticas.

- SEC-029 — Separar permissões de reembolso, alteração de preço e
  administração de credenciais.

- SEC-030 — Conciliar pagamentos periodicamente e alertar divergências.

- SEC-031 — Testar assinatura inválida, duplicação, eventos fora de
  ordem, atraso, replay e indisponibilidade.

## 6.4 Privacidade e LGPD

- SEC-032 — Inventariar dados, finalidades, bases legais, titulares,
  destinos, retenção, operadores e atendimento de direitos.

- SEC-033 — Coletar apenas dados necessários; não exigir CPF sem
  justificativa legal/operacional.

- SEC-034 — Classificar dados como públicos, internos, confidenciais ou
  restritos.

- SEC-035 — TLS moderno em trânsito e criptografia gerenciada em
  repouso; chaves separadas dos dados e rotação definida.

- SEC-036 — Considerar criptografia de campo para atributos de alta
  sensibilidade; evitar PII desnecessária em logs.

- SEC-037 — Definir retenção para pedidos, logs, notas, arquivos
  temporários, prompts e suporte.

- SEC-038 — Implementar exportação, correção e exclusão, ressalvadas
  retenções legais.

- SEC-039 — Documentar contratos, subprocessadores, localização e uso de
  dados por fornecedores de IA.

- SEC-040 — Procedimento de incidente: avaliação, contenção, evidências
  e comunicação conforme regras aplicáveis.

- SEC-041 — Avaliar necessidade de RIPD, especialmente para perfis,
  dados sensíveis ou tratamento em escala.

- SEC-042 — Separar consentimento de marketing de execução contratual e
  mensagens transacionais.

## 6.5 Upload, documentos e renderização

- SEC-043 — Permitir formatos autorizados; verificar extensão, MIME
  real, assinatura, tamanho e número de páginas.

- SEC-044 — Upload em quarentena, análise antimalware e CDR quando
  adequado.

- SEC-045 — Processar em ambiente isolado, com CPU, memória, tempo e
  rede limitados.

- SEC-046 — Não executar macros, scripts, links ou conteúdo ativo de
  arquivos submetidos.

- SEC-047 — Prevenir path traversal, ZIP bombs, XXE, SSRF e exploração
  de bibliotecas de PDF/DOCX/EPUB/imagem.

- SEC-048 — Impedir acesso à rede pelo renderizador, salvo recursos
  locais explicitamente permitidos.

- SEC-049 — Validar HTML/EPUB, remover scripts/atributos perigosos e
  aplicar CSP ao leitor.

- SEC-050 — Controlar limpeza de temporários e registrar a cadeia de
  processamento.

## 6.6 IA generativa e RAG
> **Adiado (ADR-0008, Fase 2, Fábrica de IA).** SEC-051 a SEC-064 só voltam a valer se a IA voltar ao plano.

- SEC-051 (adiado, ADR-0008) — Tratar prompts, documentos, páginas e resultados de
  ferramentas como entradas não confiáveis.

- SEC-052 (adiado, ADR-0008) — Mitigar prompt injection direta/indireta; instruções dentro
  de documentos não alteram políticas nem permissões.

- SEC-053 (adiado, ADR-0008) — Separar instruções do sistema, usuário e conteúdo
  recuperado; rotular fontes não confiáveis.

- SEC-054 (adiado, ADR-0008) — Não expor segredos, tokens, credenciais ou ferramentas
  irrestritas ao modelo.

- SEC-055 (adiado, ADR-0008) — Ações privilegiadas implementadas em código determinístico e
  protegidas por autorização.

- SEC-056 (adiado, ADR-0008) — Validar saídas antes de renderizar, persistir, executar ou
  usar em chamadas subsequentes.

- SEC-057 (adiado, ADR-0008) — Aplicar ACL/tenant no RAG para impedir recuperação de fontes
  privadas de terceiros.

- SEC-058 (adiado, ADR-0008) — Registrar origem, versão e hash das fontes; permitir remoção
  e reindexação.

- SEC-059 (adiado, ADR-0008) — Avaliar relevância, suporte de citações, factualidade,
  conteúdo nocivo e vazamento com testes versionados.

- SEC-060 (adiado, ADR-0008) — Limitar tokens, tempo, chamadas, concorrência e custo;
  detectar loops e exaustão de recursos.

- SEC-061 (adiado, ADR-0008) — Não apresentar saída como aconselhamento profissional
  definitivo; avisos conforme categoria.

- SEC-062 (adiado, ADR-0008) — Exigir revisão humana antes de publicar conteúdo de alto
  impacto.

- SEC-063 (adiado, ADR-0008) — Testar prompt injection, extração de prompt, vazamento,
  poisoning, citações fabricadas e abuso de ferramentas.

- SEC-064 (adiado, ADR-0008) — Avaliar retenção de prompts, uso para treinamento,
  residência, limites, disponibilidade e mudança de modelo.

## 6.7 Auditoria, segredos e cadeia de fornecimento

- SEC-065 — Registrar ator, ação, objeto, timestamp UTC, resultado,
  correlation ID e origem necessária; sem tokens ou dados sensíveis.

- SEC-066 — Restringir acesso a logs e separar operadores da aplicação
  de administradores das evidências.

- SEC-067 — Segredos fora do repositório/imagens; secret scanning no
  desenvolvimento e CI.

- SEC-068 — Dependências fixadas/atualizadas, SBOM, varredura de
  vulnerabilidades, análise estática e scan de imagens.

- SEC-069 — Proteger branches, exigir revisão/checks e usar credenciais
  CI/CD de privilégio mínimo.

- SEC-070 — Promover o mesmo artefato entre ambientes; assinar
  artefatos/imagens quando viável.

- SEC-071 — Separar contas, redes, dados e credenciais de
  desenvolvimento, homologação e produção.

- SEC-072 — Testes de invasão antes do lançamento e após mudanças de
  alto risco; SLA de correção por severidade.

# 7. Requisitos não funcionais e metas

Os valores abaixo são metas iniciais propostas, não garantias. Devem ser
confirmados por testes de carga, orçamento, perfil de uso e criticidade
do serviço.

| **ID**                      | **Meta proposta**                                                      | **Critério de verificação**                                                 |
|-----------------------------|------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| NFR-001 Disponibilidade     | 99,5% mensal para aplicação principal                                  | Medição de SLI, exclusões de manutenção documentadas                        |
| NFR-002 API                 | p95 \< 500 ms e p99 \< 1,5 s em endpoints comuns sob carga nominal     | Teste de carga com cenário e dataset definidos                              |
| NFR-003 Geração (adiado, ADR-0008)             | Confirmação da tarefa em até 2 s p95; duração medida por classe        | Benchmark por tamanho, idioma e categoria; 3–5 min não é garantia universal |
| NFR-004 Resiliência         | Tarefas sobrevivem a reinício; retry e dead-letter operacionais        | Testes de falha e replay                                                    |
| NFR-005 Acessibilidade      | WCAG 2.2 AA geral; AAA selecionado para critérios prioritários         | Ferramentas automáticas e auditoria manual                                  |
| NFR-006 Compatibilidade     | Matriz explícita de navegadores/dispositivos                           | Testes de PWA, instalação, atualização e offline                            |
| NFR-007 Recuperação         | RPO até 15 min e RTO até 4 h como proposta inicial                     | Exercício de recuperação documentado                                        |
| NFR-008 Backup              | Backups criptografados, segregados e restaurados periodicamente        | Evidência de restauração e integridade                                      |
| NFR-009 Observabilidade     | Logs, métricas, traces, dashboards e alertas                           | Simulação de incidentes e revisão de alertas                                |
| NFR-010 Escala              | Carga nominal e de pico definidas antes do lançamento                  | Teste de carga e teste de estresse                                          |
| NFR-011 Internacionalização | Moeda, fuso, pluralização, fallback e conteúdo não traduzido definidos | Testes por idioma/mercado                                                   |
| NFR-012 Privacidade         | Sem dados reais em dev sem anonimização e autorização                  | Verificação de ambientes e pipeline                                         |
| NFR-013 Portabilidade       | Exportação documentada de dados e conteúdos autorizados                | Teste de exportação/importação                                              |
| NFR-014 Manutenibilidade    | Testes e qualidade proporcionais ao risco                              | Gates CI, revisão e métricas por módulo                                     |

# 8. Modelo de dados mínimo

| **Entidade**                                                          | **Responsabilidade**                              |
|-----------------------------------------------------------------------|---------------------------------------------------|
| User, Role, Permission, UserSession, MfaCredential                    | Identidade, acesso, sessão e MFA                  |
| Book, BookVersion, Chapter, ContentBlock, PublicationReview           | Conteúdo editorial, versões e aprovação           |
| SourceDocument, SourceChunk, SourceCitation, IngestionJob             | (adiado, ADR-0008) Fontes, indexação e proveniência |
| GenerationJob, GenerationAttempt, PromptTemplateVersion, ModelUsage   | (adiado, ADR-0008) Execução de IA, tentativas, configuração e custos |
| Product, ProductPrice, Category, Tag, SampleAsset                     | Catálogo e preços                                 |
| Order, OrderItem, PaymentAttempt, PaymentEvent, Refund, Entitlement   | Pedido, pagamento, reembolso e direito de acesso  |
| DownloadGrant, GiftInvitation, ReadingProgress, Bookmark, PrivateNote | Entrega e experiência de leitura                  |
| Consent, Notification, SupportConversation, AuditEvent                | Consentimentos, comunicação e auditoria           |
| SecurityIncident, RetentionPolicy, DataSubjectRequest                 | Incidentes, retenção e direitos do titular        |

- Usar IDs externos não sequenciais, sem tratá-los como substituto de
  autorização.

- Valores monetários em unidades menores inteiras ou decimal apropriado;
  nunca float/double.

- Persistir timestamps em UTC e converter na apresentação.

- Definir constraints, índices, integridade referencial e política de
  exclusão.

- Separar estado de pagamento de concessão de entitlement.

- Definir retenção e eliminação para cada entidade.

# 9. Fluxos críticos

## 9.1 Geração e publicação
> **Adiado (ADR-0008, Fase 2, Fábrica de IA).** Este fluxo não existe hoje: o Administrador envia o PDF e a capa e publica à mão.

11. Usuário autorizado cria solicitação; backend valida categoria,
    fontes, limites e orçamento.

12. Sistema persiste tarefa e publica mensagem de forma confiável.

13. Worker recupera fontes respeitando ACLs e registra versão/hash.

14. Modelo gera sumário; editor aprova.

15. Worker gera capítulos, referências e metadados.

16. Validadores verificam estrutura, citações, conteúdo e formatos.

17. Revisor aprova; sistema publica versão imutável.

18. Artefatos ficam privados; somente metadados aprovados ficam
    públicos.

19. Sistema registra custo, eventos, métricas e trilha de auditoria.

## 9.2 Compra e entrega

20. Cliente cria pedido; preço e moeda são recalculados no servidor.

21. Sistema registra tentativa e cria cobrança no provedor.

22. Webhook é autenticado, validado e persistido.

23. Evento é deduplicado e processado idempotentemente.

24. Sistema confirma o pagamento segundo as regras e consulta o provedor
    quando necessário.

25. Sistema concede entitlement de forma idempotente.

26. Cliente recebe confirmação e acesso autenticado.

27. Rotina reconcilia pedidos e pagamentos.

28. Estorno/chargeback atualiza estados e aplica política de acesso.

## 9.3 Download seguro

29. Validar autenticação, entitlement, estado do produto e política.

30. Emitir URL assinada de curta duração ou usar proxy controlado.

31. Registrar metadados mínimos e aplicar limites de abuso.

32. Não disponibilizar URLs públicas permanentes para arquivos pagos.

## 9.4 Atendimento por IA
> **Adiado (ADR-0008, Fase 4).** Não há atendimento por IA hoje; o suporte é por e-mail (docs/runbooks/suporte-cliente.md).

33. Classificar intenção e risco.

34. Consultar somente fontes e dados autorizados ao usuário.

35. Para pedido, cobrança ou dado pessoal, autenticar e validar
    autorização antes de responder.

36. Escalonar casos sensíveis, de baixa confiança ou fora do escopo.

37. Registrar somente os dados necessários à auditoria.

# 10. Testes e critérios de aceite

## 10.1 Testes obrigatórios

- Unitários para regras de domínio.

- Integração para PostgreSQL, fila e armazenamento.

- Contrato para provedores de pagamento, IA, e-mail e WhatsApp.

- End-to-end para compra, pagamento, entrega, reembolso, geração e
  publicação.

- SAST, DAST, SCA, secret scanning, container scanning e testes de
  autorização.

- Carga e resiliência: timeout, falha de dependência, fila acumulada e
  reinício de worker.

- Acessibilidade automatizada e manual com teclado/leitor de tela.

- (Adiado, ADR-0008) Avaliação de IA com conjunto de referência, regressão, factualidade e
  testes adversariais.

- Teste periódico de restauração de backup e reprocessamento.

## 10.2 Gate de lançamento

- Nenhuma vulnerabilidade crítica ou alta sem tratamento formal aprovado
  por responsável autorizado.

- Testes negativos de autorização para pedidos, notas, arquivos, tarefas
  e downloads.

- Webhooks com validação, deduplicação, idempotência e testes de replay.

- Restauração demonstrada e metas RPO/RTO validadas.

- (Adiado, ADR-0008) Limites de custo de IA, quotas e alertas ativos e testados.

- Consentimento, descadastro e mensagens transacionais testados.

- Auditoria de acessibilidade das jornadas críticas concluída.

- Plano de incidente, contatos de fornecedores, rollback e suporte
  documentados.

- Termos comerciais, privacidade, reembolso e direitos de conteúdo
  revisados por profissionais habilitados.

# 11. DevSecOps, Git e implantação

Gitflow com main/staging/develop pode ser mantido se a governança da
equipe justificar. Para equipe pequena e entrega frequente, trunk-based
development com branches curtas pode reduzir complexidade. A decisão
deve ser documentada.

- Pipeline: formatação, compilação, testes unitários, SAST, SCA, secret
  scanning e verificação de licenças.

- Executar testes de integração/contrato; construir imagem e gerar SBOM.

- Fazer scan de imagem e assinar artefato quando viável.

- Implantar em ambiente efêmero/homologação; executar E2E, DAST e smoke
  tests.

- Promover o mesmo artefato testado para produção.

- Executar verificação pós-deploy, monitoramento e rollback.

- Não permitir que pipelines de PR não confiáveis acessem segredos.

- Usar credenciais temporárias/OIDC para cloud quando disponível.

- Versionar migrações de banco e definir rollout/rollback.

- Usar feature flags para mudanças arriscadas e rollout gradual.

# 12. Observabilidade e operação

| **Área**              | **Métricas mínimas**                                                                      |
|-----------------------|-------------------------------------------------------------------------------------------|
| Aplicação             | Erro, latência p50/p95/p99, disponibilidade por endpoint                                  |
| Fila                  | Tamanho, idade, tarefas por estado, tentativas, falhas e dead letters                     |
| IA (adiado, ADR-0008)  | Tokens, custo por provedor/modelo/tarefa/livro, latência, sucesso e qualidade de citações |
| Pagamentos            | Pendências, eventos rejeitados/duplicados, divergências de conciliação                    |
| Entrega               | Downloads negados, links expirados e padrões de abuso                                     |
| Comunicação           | Entregabilidade de e-mail, falhas de mensagens e opt-outs                                 |
| Privacidade/segurança | Solicitações de titular, incidentes, alertas e tempo de atendimento                       |

Cada alerta deve ter severidade, responsável, ação recomendada, runbook
e caminho de escalonamento. Logs não devem conter senhas, tokens,
segredos, dados completos de cartão ou CPF completo.

# 13. Direitos autorais e confiança editorial

- Exigir declaração de direitos ou base legítima para cada fonte.

- Manter autoria, licença, proveniência e permissões de reutilização.

- Não presumir que conteúdo disponível na web pode ser reproduzido
  integralmente.

- Verificar citações, atribuição, marcas, imagens e fontes antes da
  publicação.

- Definir política para plágio, difamação, conteúdo ilegal, enganoso ou
  infrator.

- (Adiado, ADR-0008) Informar uso de IA conforme política editorial e obrigações
  aplicáveis.

- Estabelecer denúncia, revisão, correção, retirada e contestação.

- Manter histórico das versões e correções.

- Para temas de saúde, finanças, direito e segurança, definir limites
  editoriais e revisão especializada proporcional ao risco.

# 14. Acessibilidade

Meta recomendada: WCAG 2.2 AA como objetivo geral. Selecionar critérios
AAA aplicáveis a necessidades prioritárias, sem declarar conformidade
global AAA antes de auditoria.

- Navegação por teclado, foco visível, estrutura semântica e nomes
  acessíveis.

- Contraste, zoom, redimensionamento de texto e espaçamento.

- Testes com leitores de tela em jornadas críticas.

- Tamanho de controles e mensagens de erro acessíveis.

- Legendas/transcrições quando houver mídia.

- Ordem de leitura e acessibilidade dos PDFs/EPUBs gerados.

- Redução de movimento e preferências de contraste.

- Teste em dispositivos móveis e conexão lenta.

# 15. Plano de entrega por fases

| **Fase**                      | **Escopo**                                                                                                 | **Critério para avançar**                          |
|-------------------------------|------------------------------------------------------------------------------------------------------------|----------------------------------------------------|
| Fase 0 — Descoberta           | Modelo comercial, público, volume, ameaças, fornecedores, SLOs e privacidade                               | Decisões pendentes prioritárias aprovadas          |
| Fase 1 — MVP seguro           | Conta/admin MFA, catálogo, conteúdo manual, um provedor de pagamento, pedidos, biblioteca e entrega segura | Fluxo de compra/entrega e recuperação testados     |
| Fase 2 — Fábrica de IA (ADIADA, backlog) | Fontes/RAG, sumário, capítulos, revisão, quotas, avaliação e versionamento                                 | Qualidade e custo dentro dos limites definidos     |
| Fase 3 — Experiência ampliada | Leitor acessível, notas, áudio, tradução, presentes e recomendações                                        | Acessibilidade e políticas de dados verificadas    |
| Fase 4 — Escala (ADIADA, backlog)         | WhatsApp, novos modelos comerciais e otimização                                                            | Métricas demonstram necessidade e sustentabilidade |

> **Adiado (ADR-0008, Fases 2 e 4).** A Fase 2 (Fábrica de IA) e a Fase 4 (Escala: WhatsApp, novos modelos comerciais) ficam no backlog como evolutivas, para depois que o site estiver em produção e gerando renda. A Fase 3 continua no plano, depois do lançamento. A Fase 2 pode voltar como decisão nova.

Não desenvolver todas as fases em paralelo. Priorizar compra e entrega
confiáveis antes de ampliar automações e complexidade de IA.

# 16. Registro de riscos

| **Risco**                         | **Prioridade** | **Tratamento principal**                                              |
|-----------------------------------|----------------|-----------------------------------------------------------------------|
| Liberação indevida de produto     | Crítica        | Webhook autenticado, conciliação, idempotência e entitlement separado |
| Acesso cruzado entre usuários     | Crítica        | Autorização por objeto e testes negativos                             |
| (Adiado, ADR-0008) Prompt injection/vazamento no RAG | Alta           | Isolamento, menor privilégio, validação e testes adversariais         |
| (Adiado, ADR-0008) Abuso e custo imprevisível de IA  | Alta           | Quotas, budgets, timeouts, limite de tokens e alertas                 |
| Upload malicioso/renderizador     | Alta           | Quarentena, análise, sandbox e renderização sem rede                  |
| PII em logs ou marca d'água       | Alta           | Minimização, mascaramento e retenção                                  |
| Fraude em links de presente       | Alta           | Token aleatório, uso único, expiração e revogação                     |
| Conteúdo factual incorreto        | Alta           | Fontes, avaliação e revisão proporcional ao risco                     |
| Perda de dados ou tarefas         | Alta           | Backups testados, fila durável, outbox e reprocessamento              |
| Dependência de fornecedor         | Média          | Adaptadores, exportação e plano de substituição                       |

# 17. Referências técnicas

- OWASP ASVS 5.0.0 — https://asvs.owasp.org/

- OWASP API Security Project —
  https://owasp.org/www-project-api-security/

- OWASP GenAI Security Project — https://genai.owasp.org/

- W3C WCAG 2.2 — https://www.w3.org/TR/WCAG22/

- NIST AI Risk Management Framework — Generative AI Profile —
  https://www.nist.gov/publications/artificial-intelligence-risk-management-framework-generative-artificial-intelligence

- Leanpub — https://help.leanpub.com/en/articles/110765-what-is-leanpub

- Gumroad — https://gumroad.com/features/

- GitBook — https://www.gitbook.com/features/docs-analytics

Plataformas comerciais são referências de produto e experiência; sua
menção não constitui auditoria dos controles internos nem comprova
conformidade de segurança.

# 18. Definição de pronto para produção

- Requisitos críticos têm responsável, evidência e teste de aceite.

- Modelo de ameaças e matriz de permissões foram revisados.

- Fluxos financeiros foram testados em cenários normais, duplicados e
  adversariais.

- Recuperação de dados e tarefas foi demonstrada.

- (Adiado, ADR-0008) Limites de consumo de IA estão ativos.

- Integrações externas têm timeout, retry, observabilidade e fallback
  definidos.

- Privacidade, retenção e atendimento ao titular estão implementados.

- Controles de segurança têm evidências verificáveis.

- Termos comerciais, políticas editoriais e direitos de conteúdo foram
  revisados.

- Plano de incidentes, suporte, monitoramento e rollback está
  operacional.

# Anexo A — Principais mudanças em relação ao documento original

- AES-256 foi refinado para distinguir criptografia em repouso, gestão
  de chaves e eventual criptografia por campo.

- HMAC-SHA256 universal foi substituído por validação conforme a
  documentação de cada provedor de pagamento.

- JWT obrigatório foi flexibilizado; sessões seguras podem ser mais
  simples em aplicação web first-party.

- WCAG AAA global foi ajustado para AA geral e AAA seletivo com
  auditoria.

- Meta fixa de 3–5 minutos foi convertida em meta por classe de
  documento, a validar.

- Redis e Virtual Threads deixaram de ser tratados como garantia
  suficiente de fila durável.

- Marca d'água com CPF completo foi substituída por minimização de
  dados.

- Logs PostgreSQL “imutáveis” foram refinados para append-only, controle
  de acesso e armazenamento externo imutável quando justificado.

- RBAC de dois papéis foi ampliado para editor, revisor, financeiro,
  suporte e serviços de sistema.

- Foram incluídos requisitos específicos para prompt injection,
  isolamento de RAG, validação de saída, abuso de ferramentas e custo.

# Anexo B — Checklist de decisões para aprovação

- Modelo comercial e regras de reembolso aprovados.

- MVP e funcionalidades adiadas aprovados.

- Formatos de conteúdo e estratégia de DRM definidos.

- Mercados, moedas, tributos e provedores escolhidos.

- Volume, SLOs, RPO/RTO e orçamento aprovados.

- Modelo de identidade, MFA e papéis aprovado.

- Nível de verificação OWASP ASVS definido.

- Política de fontes, direitos autorais e revisão editorial aprovada.

- (Adiado, ADR-0008) Fornecedor de IA, retenção de dados e limites de custo aprovados.

- Política de privacidade, retenção e resposta a incidentes revisada.

- Critérios de aceite e responsáveis por cada requisito definidos.
