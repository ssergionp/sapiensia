# sapiensia

Plataforma web que vende conteúdos educativos digitais (e-books, cartilhas, tutoriais e checklists) e os entrega a quem comprou.

## Language

**Produto**:
Um conteúdo digital à venda no catálogo (e-book, cartilha, tutorial ou checklist), com um único preço em reais.
_Avoid_: Item, livro, curso

**Catálogo**:
A vitrine pública dos Produtos publicados, visível a qualquer visitante. Mostra capa, título, descrição, categoria e preço; não tem busca, filtros nem amostra grátis na Fase 1.
_Avoid_: Loja, vitrine

**Arquivo do produto**:
O PDF pronto, enviado pelo administrador, que o cliente baixa depois de comprar o Produto. Cada Produto tem uma só versão atual; uma correção substitui o arquivo para todos os clientes que já compraram.
_Avoid_: Conteúdo, e-book (quando falar do arquivo)

**Administrador**:
A pessoa que opera o painel: cadastra Produtos, envia Arquivos do produto e trata Reembolsos. Na Fase 1 é um só perfil, sempre com MFA; cada operação do painel exige uma capacidade própria, para permitir novos perfis no futuro.
_Avoid_: Admin, editor, usuário interno

**Cliente**:
Pessoa com conta que compra Produtos e baixa os Arquivos do produto que comprou.
_Avoid_: Usuário, comprador

**Acesso**:
O direito de um Cliente baixar o Arquivo do produto de um Produto que comprou. Nasce quando o Pedido é pago e vale para sempre; só é cancelado por Reembolso ou fraude confirmada. Despublicar o Produto não o cancela.
_Avoid_: Licença, permissão, entitlement

**Download**:
O ato de um Cliente com Acesso baixar o Arquivo do produto, por um link temporário de 60 segundos emitido a cada clique. Limitado a 10 por dia por Cliente e Produto.
_Avoid_: Baixa, entrega (a entrega é a concessão do Acesso)

**Registro de download**:
A anotação mínima de cada Download: qual Cliente, qual Produto e quando. Não guarda o IP completo.
_Avoid_: Log de download, histórico

**Biblioteca**:
A lista dos Produtos que um Cliente comprou, de onde ele baixa o Arquivo do produto. Só existe para quem tem conta e está autenticado; não há compra nem download como visitante.
_Avoid_: Minha conta, área do cliente

**Pedido**:
O registro de que um Cliente quer comprar um Produto, com preço recalculado no servidor e um Pix de 30 minutos. Nasce pendente, vira pago só quando o Asaas confirma o pagamento, e vira expirado se o Pix não for pago a tempo.
_Avoid_: Compra, transação, carrinho

**Reembolso**:
Devolução do valor de um Pedido pago, aceita em até 7 dias após a compra (arrependimento) ou por defeito no Arquivo do produto. O dinheiro é devolvido pelo administrador no painel do Asaas; no sistema, o Reembolso registra o fato e cancela o acesso do cliente ao arquivo.
_Avoid_: Estorno (é do cartão), chargeback

**Venda avulsa**:
Compra de um único Produto, paga uma só vez, sem cupom, pacote, presente nem renovação. É o único modelo de venda da Fase 1.
_Avoid_: Assinatura, pacote
