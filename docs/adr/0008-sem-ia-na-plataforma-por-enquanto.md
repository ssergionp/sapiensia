---
status: accepted
---

# Sem criação de conteúdo por IA na plataforma, por enquanto: só envio de material pronto

O SapiensIA passa a ser uma plataforma para **publicar e vender material pronto** (PDF + Capa, como a Fase 1 já faz). A criação e a revisão de conteúdo por IA dentro da plataforma (Fase 2 da spec, "Fábrica de IA") saem do plano atual. Decisão do Sérgio em 17/11/2026 (#180).

Escolhemos isso para concentrar o esforço em lançar e vender, e porque a IA traz custo variável e risco que ainda não compensam: fontes e RAG, quotas e orçamento, avaliação de qualidade, injeção de prompt e revisão humana de conteúdo de saúde, direito e finanças. Quem cria o material usa a ferramenta que quiser, fora da plataforma, e envia o PDF pronto.

## Consequences

- **Fases 2 e 4 viram backlog evolutivo.** A Fase 2 (Fábrica de IA) e a Fase 4 (Escala: WhatsApp, novos modelos comerciais) só voltam a ser consideradas depois que o site estiver em produção e gerando renda. A Fase 3 (Experiência ampliada) continua no plano, depois do lançamento.
- **A Fase 2 pode voltar como decisão nova**, com ADR próprio que substitua este. Até lá, nenhum código, tabela, dependência ou fornecedor de IA entra no sistema.
- **A spec não é apagada.** Em `docs/spec/sapiensia-spec.md`, a seção 15 marca as Fases 2 e 4 como adiadas, e os requisitos que só existem por causa da IA (FR-001 a FR-006, FR-012, FR-013, FR-014, FR-015, SEC-051 a SEC-064, o fluxo 9.1, o atendimento por IA da 9.4 e os itens de IA dos gates e dos riscos) levam a marca "(adiado, ADR-0008)". Eles deixam de valer para os gates de lançamento.
- **Requisitos com outro uso continuam valendo.** FR-007 (política por categoria, que o código já cita na categoria do Produto), FR-009 a FR-011 (exportação, versionamento e publicação) e SEC-014, SEC-037 e SEC-039 seguem para o material enviado à mão, só sem a parte de IA.
- **O Glossário não muda:** não há termo de IA nele.
- **O marketplace (versão 2.0, #151) continua valendo**, sem relação com esta decisão.
