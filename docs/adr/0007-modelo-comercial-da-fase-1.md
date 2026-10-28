---
status: accepted
---

# Modelo comercial da Fase 1: venda avulsa de PDF com Acesso vitalício

A Fase 1 vende um Produto por vez (venda avulsa, em reais), entregue como um PDF pronto enviado pelo Administrador. Não há pacote, cupom, presente, assinatura, EPUB, leitor no navegador nem amostra grátis; isso fica para as Fases 3 e 4. Escolhemos isso para concentrar o esforço na compra e na entrega seguras, que a spec manda priorizar.

## Regras que o cliente enxerga

- **Acesso vitalício** ao Arquivo do produto, sempre na versão mais recente. Despublicar um Produto não tira o Acesso de quem já comprou; só um Reembolso ou fraude confirmada o cancela.
- **Reembolso** em até 7 dias (arrependimento do CDC) ou por defeito no arquivo. O Administrador devolve o dinheiro no painel do Asaas, e o sistema cancela o Acesso. A regra e os textos devem ser confirmados pelo advogado antes da primeira venda.
- **Conta obrigatória** para comprar e baixar; não há compra nem link de download como visitante.
- **Download protegido**: link de 60 segundos emitido a cada clique, depois de conferir login e Acesso, com limite de 10 downloads por dia por Cliente e Produto, e registro mínimo (Cliente, Produto, data; sem IP completo). Marca d'água só se vendas reais mostrarem vazamento.
- **Preço**: mudar o preço afeta só Pedidos novos; o valor do Pix é sempre o calculado no servidor.
- **Direitos da LGPD** atendidos por e-mail, à mão; apagar uma conta anonimiza os dados pessoais e mantém o registro do Pedido pelo prazo que o contador definir.

## Consequences

- Sem advogado e contador não há venda real: o gate de lançamento bloqueia a primeira venda até a revisão dos Termos, da Política de privacidade e da Política de reembolso, e até o contador responder sobre pessoa física, nota fiscal e prazo de guarda.
