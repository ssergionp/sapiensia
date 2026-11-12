---
status: accepted
---

# Modelo comercial da Fase 1: venda avulsa de PDF com Acesso vitalício

A Fase 1 vende um Produto por vez (venda avulsa, em reais), entregue como um PDF pronto enviado pelo Administrador. Não há pacote, cupom, presente, assinatura, EPUB, leitor no navegador nem amostra grátis; isso fica para as Fases 3 e 4. Escolhemos isso para concentrar o esforço na compra e na entrega seguras, que a spec manda priorizar.

## Regras que o cliente enxerga

- **Acesso vitalício** ao Arquivo do produto, sempre na versão mais recente. Despublicar um Produto não tira o Acesso de quem já comprou; só um Reembolso ou fraude confirmada o cancela. O advogado deve rever a expressão "Acesso vitalício" (por exemplo, trocá-la por "enquanto a SapiensIA estiver em operação").
- **Reembolso** em até 7 dias (arrependimento do CDC) ou por defeito no arquivo. O Administrador devolve o dinheiro no painel do Asaas; o sistema recebe o aviso de estorno do Asaas (e o de devolução por fraude, o MED do Pix) e cancela o Acesso sozinho. Não há botão de reembolso no painel na Fase 1. A regra e os textos devem ser confirmados pelo advogado antes da primeira venda.
- **Conta obrigatória** para comprar e baixar; não há compra nem link de download como visitante.
- **Download protegido**: link de 60 segundos emitido a cada clique, depois de conferir login e Acesso, com limite de 10 downloads por dia por Cliente e Produto, e registro mínimo (Cliente, Produto, data; sem IP completo). Marca d'água só se vendas reais mostrarem vazamento.
- **Preço**: mudar o preço afeta só Pedidos novos; o valor do Pix é sempre o calculado no servidor.
- **Limites do preço**: de R$ 5,00 a R$ 9.999,99, em reais (BRL), guardado em centavos inteiros (de 500 a 999999).
- **Categoria do Produto**: é o tema, numa lista fechada: Saúde, Direito, Finanças, Segurança, Educação, Tecnologia, Negócios e Outros (códigos `HEALTH`, `LAW`, `FINANCE`, `SECURITY`, `EDUCATION`, `TECHNOLOGY`, `BUSINESS` e `OTHER`).
- **Direitos da LGPD** atendidos por e-mail, à mão; apagar uma conta anonimiza os dados pessoais e mantém o registro do Pedido pelo prazo que o contador definir.

## Consequences

- Sem advogado e contador não há venda real: o gate de lançamento bloqueia a primeira venda até a revisão dos Termos, da Política de privacidade e da Política de reembolso, e até o contador responder sobre pessoa física, nota fiscal e prazo de guarda.
- **Catálogo público (#20):** a leitura do Catálogo é pública de propósito: qualquer Visitante, sem conta, vê os Produtos publicados. `GET /api/products` e `GET /api/products/{id}` são `permitAll` no `SecurityConfig`, só no verbo `GET`; qualquer outro verbo e qualquer caminho abaixo de `/api/products/{id}/` seguem negados. Mostram só Produtos publicados, com id, título, descrição (um resumo de até 200 caracteres na lista), categoria e preço em centavos; nunca o estado, a versão, as datas ou qualquer dado do Arquivo do produto. Rascunho, Despublicado e Produto inexistente recebem o mesmo `404` sem corpo, para a resposta não revelar que o Produto existe. As respostas não abrem sessão nem gravam cookie e mantêm o `Cache-Control: no-store` padrão, para um Produto despublicado sair do Catálogo na hora; cache HTTP só se o tráfego pedir. O tamanho da página é fixo (20), e o limite de taxa por rota fica no #42.
