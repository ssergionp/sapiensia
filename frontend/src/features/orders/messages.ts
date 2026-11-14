// Single source of the Order screen texts (section 8 of the plan, screens A and B, plus the notice of screen C).
export const ORDER_TEXTS = {
  // Screen A: Product page
  buy: "Comprar com Pix",
  buying: "Gerando o Pix…",
  cpfHeading: "Informe seu CPF",
  cpfExplanation:
    "O CPF é exigido para gerar a cobrança por Pix. Ele é enviado ao Asaas, que processa o pagamento, e não fica guardado na SapiensIA.",
  cpfLabel: "CPF",
  cpfHint: "Digite os 11 números.",
  generate: "Gerar Pix",
  cpfEmpty: "Informe o CPF.",
  cpfInvalid: "CPF inválido. Confira os números.",
  paymentUnavailable:
    "Não foi possível gerar o Pix agora. Tente novamente em alguns minutos.",
  inProgress:
    "Já existe um Pix sendo gerado para você. Aguarde alguns segundos e tente novamente.",
  productUnavailable: "Este Produto não está mais disponível.",
  alreadyOwned: "Você já tem Acesso a este Produto.",
  unexpected: "Não foi possível concluir agora. Tente novamente.",
  // Screen B: Order (used by OrderPage, Task 8)
  documentTitle: "Pagamento por Pix | SapiensIA",
  loadingDocumentTitle: "Carregando… | SapiensIA",
  loading: "Carregando…",
  heading: "Pague com Pix",
  productTerm: "Produto",
  valueTerm: "Valor",
  instruction:
    "No aplicativo do seu banco, escolha pagar com Pix e leia o QR Code ou cole o código abaixo.",
  qrName: "QR Code do Pix",
  codeLabel: "Pix copia e cola",
  copy: "Copiar código",
  copied: "Código copiado.",
  copyFailed:
    "Não foi possível copiar. Selecione o código e copie manualmente.",
  countdown: (mmss: string) => `Este Pix expira em ${mmss}.`,
  warnFiveMinutes: "Faltam 5 minutos para o Pix expirar.",
  warnOneMinute: "Falta 1 minuto para o Pix expirar.",
  afterPaying:
    "Depois de pagar, aguarde nesta página: ela se atualiza sozinha quando o pagamento for confirmado e o Acesso ao Produto for liberado.",
  expiredDocumentTitle: "Pix expirado | SapiensIA",
  expiredHeading: "Este Pix expirou",
  expiredText:
    "O prazo de 30 minutos para pagar terminou e este código não vale mais. Se ainda quiser o Produto, gere um novo Pix.",
  expiredAlreadyPaid:
    "Se você já pagou, aguarde alguns minutos: esta página se atualiza sozinha quando o pagamento for confirmado.",
  backToProduct: "Voltar ao Produto",
  paidDocumentTitle: "Pagamento confirmado | SapiensIA",
  paidHeading: "Pagamento confirmado",
  paidText: "Recebemos o seu Pix. Você já tem Acesso a este Produto.",
  notFoundDocumentTitle: "Pedido não encontrado | SapiensIA",
  notFoundHeading: "Pedido não encontrado",
  notFoundText:
    "Não encontramos este Pedido. Confira o endereço ou volte ao Catálogo.",
  loadError: "Não foi possível carregar o Pedido agora. Tente novamente.",
  retry: "Tentar novamente",
  backToCatalog: "Voltar ao Catálogo",
  // Screen C: Login notice (used by LoginPage, Task 9)
  loginNotice: "Entre ou crie sua conta para continuar a compra.",
} as const;
