# sapiensia

Plataforma web que vende conteúdos educativos digitais (e-books, cartilhas, tutoriais e checklists) e os entrega a quem comprou.

## Language

**Produto**:
Um conteúdo digital à venda no catálogo (e-book, cartilha, tutorial ou checklist), com um único preço em reais.
_Avoid_: Item, livro, curso

**Rascunho**:
Estado de um Produto que os clientes ainda não conseguem ver nem comprar. É o único estado em que um Produto é criado. Um Produto que já foi publicado nunca volta a ser Rascunho: ao sair do Catálogo, fica Despublicado.
_Avoid_: Esboço

**Publicar**:
Colocar um Produto no Catálogo, para os clientes poderem vê-lo e comprá-lo. Só é possível com Arquivo do produto enviado. Depois da primeira publicação, o Arquivo do produto só pode ser trocado com nova autenticação do Administrador.
_Avoid_: Ativar, lançar

**Despublicar**:
Tirar um Produto do Catálogo, sem apagá-lo. Não cancela o Acesso de quem já comprou; o Produto pode ser publicado de novo.
_Avoid_: Arquivar, desativar, excluir

**Despublicado**:
Estado de um Produto que já foi publicado e saiu do Catálogo. Os clientes novos não o veem nem o compram; quem já comprou continua com Acesso.
_Avoid_: Inativo, oculto

**Catálogo**:
A vitrine pública dos Produtos publicados, visível a qualquer visitante. Mostra título, descrição, categoria e preço (a capa ainda não existe); não tem busca, filtros nem amostra grátis na Fase 1.
_Avoid_: Loja, vitrine

**Arquivo do produto**:
O PDF pronto, enviado pelo administrador, que o cliente baixa depois de comprar o Produto. Cada Produto tem uma só versão atual; uma correção substitui o arquivo para todos os clientes que já compraram.
_Avoid_: Conteúdo, e-book (quando falar do arquivo)

**Administrador**:
A pessoa que opera o painel: cadastra Produtos, envia Arquivos do produto e devolve o dinheiro dos Reembolsos no painel do Asaas. Na Fase 1 é um só perfil, sempre com MFA; cada operação do painel exige uma capacidade própria, para permitir novos perfis no futuro.
_Avoid_: Admin, editor, usuário interno

**Cliente**:
Pessoa com conta que compra Produtos e baixa os Arquivos do produto que comprou.
_Avoid_: Usuário, comprador

**Visitante**:
Qualquer pessoa que abre o site, com ou sem conta. Vê o Catálogo e a página de cada Produto publicado; para comprar e baixar precisa entrar como Cliente.
_Avoid_: Usuário anônimo, lead

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
O registro de que um Cliente quer comprar um Produto, com preço recalculado no servidor e um Pix de 30 minutos. Nasce pendente, vira pago só quando o Asaas confirma o pagamento, e vira expirado se o Pix não for pago a tempo. Se o Asaas confirmar o pagamento de um Pedido já expirado, ele vira pago e o Acesso é concedido normalmente.
_Avoid_: Compra, transação, carrinho

**Evento de pagamento**:
O aviso que o Asaas envia ao sistema (webhook) sobre uma cobrança ou o saldo, identificado pelo `id` do Asaas. É gravado antes de qualquer processamento, e um aviso repetido é descartado sem efeito.
_Avoid_: Notificação, callback

**Reembolso**:
Devolução do valor de um Pedido pago, aceita em até 7 dias após a compra (arrependimento) ou por defeito no Arquivo do produto. O Administrador devolve o dinheiro no painel do Asaas; o sistema não tem botão de reembolso, e o aviso de estorno do Asaas (ou de devolução por fraude, o MED do Pix) cancela sozinho o Acesso do Cliente.
_Avoid_: Estorno (é do cartão), chargeback

**Venda avulsa**:
Compra de um único Produto, paga uma só vez, sem cupom, pacote, presente nem renovação. É o único modelo de venda da Fase 1.
_Avoid_: Assinatura, pacote
