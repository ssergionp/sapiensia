import { SUPPORT_EMAIL } from "@/features/legal/legalTexts";

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
  goToLibrary: "Ir para a Biblioteca",
  paidHeading: "Pagamento confirmado",
  paidText: "Recebemos o seu Pix. Você já tem Acesso a este Produto.",
  refundedDocumentTitle: "Pedido reembolsado | SapiensIA",
  refundedHeading: "Pedido reembolsado",
  refundedText:
    "O valor deste Pedido foi devolvido e o Acesso ao Produto foi cancelado.",
  notFoundDocumentTitle: "Pedido não encontrado | SapiensIA",
  notFoundHeading: "Pedido não encontrado",
  notFoundText:
    "Não encontramos este Pedido. Confira o endereço ou volte ao Catálogo.",
  loadError: "Não foi possível carregar o Pedido agora. Tente novamente.",
  retry: "Tentar novamente",
  backToCatalog: "Voltar ao Catálogo",
  // My Orders list
  listDocumentTitle: "Meus Pedidos | SapiensIA",
  listHeading: "Meus Pedidos",
  orderedOn: (date: string) => `Pedido em ${date}`,
  statusPending: "Aguardando pagamento",
  statusPaid: "Pago",
  statusExpired: "Expirado",
  statusRefunded: "Reembolsado",
  pixValidUntil: (time: string) => `Pix válido até ${time}.`,
  viewPix: "Ver o Pix",
  viewPixLabel: (title: string) => `Ver o Pix de ${title}`,
  listEmpty: "Você ainda não fez nenhum Pedido.",
  goToCatalog: "Ver o Catálogo",
  listLoadError: "Não foi possível carregar os seus Pedidos. Tente novamente.",
  // Refund request (Customer)
  refundLink: "Pedir Reembolso",
  refundLinkLabel: (title: string) => `Pedir Reembolso de ${title}`,
  refundUntilLine: (date: string) => `Você pode pedir o Reembolso até ${date}.`,
  refundRequestedBadge: "Reembolso solicitado",
  refundRequestedLine: (date: string) =>
    `Reembolso solicitado em ${date}. O Acesso a este Produto está suspenso.`,
  refundDocumentTitle: "Pedir Reembolso | SapiensIA",
  refundHeading: "Pedir Reembolso",
  refundDeadlineNotice: (date: string) =>
    `Você pode pedir o Reembolso deste Pedido até ${date}. O valor volta pelo Pix, para quem pagou.`,
  refundAccessWarning:
    "Ao enviar, o seu Acesso a este Produto é suspenso na hora: ele sai da sua Biblioteca e você não consegue mais baixar o arquivo.",
  refundReasonQuestion: "Qual é o motivo?",
  refundReasons: {
    NOT_AS_EXPECTED: "Não atendeu ao que eu esperava",
    DEFECTIVE_FILE: "Arquivo com defeito",
    BOUGHT_BY_MISTAKE: "Comprei por engano",
    OTHER: "Outro",
  },
  refundDetailsLabel: "Quer contar mais? (opcional)",
  refundDetailsHint:
    "Até 500 caracteres. Não escreva senhas nem números de documentos.",
  refundSubmit: "Enviar solicitação",
  refundSubmitting: "Enviando…",
  backToOrders: "Voltar a Meus Pedidos",
  reasonRequired: "Escolha um motivo.",
  detailsTooLong: "Use até 500 caracteres.",
  refundWindowClosed: `O prazo para pedir o Reembolso pelo site terminou. Se o arquivo tiver defeito, escreva para ${SUPPORT_EMAIL}.`,
  refundNotAvailable: `Este Pedido não pode ser reembolsado pelo site. Se precisar de ajuda, escreva para ${SUPPORT_EMAIL}.`,
  refundFailed: "Não foi possível enviar a solicitação agora. Tente novamente.",
  refundRequestedDocumentTitle: "Reembolso solicitado | SapiensIA",
  refundRequestedHeading: "Reembolso solicitado",
  refundRequestedText: (amount: string) =>
    `Recebemos a sua solicitação. O valor de ${amount} será devolvido pelo Pix, para quem pagou, em até 2 dias úteis. O seu Acesso a este Produto está suspenso e será cancelado quando o valor for devolvido. Você recebe um e-mail quando isso acontecer.`,
  orderRefundRequestedText:
    "Você pediu o Reembolso deste Pedido. O Acesso ao Produto está suspenso e o valor será devolvido pelo Pix.",
  refundInProgress:
    "Você pediu o Reembolso deste Produto. Depois que o valor for devolvido, você pode comprar de novo.",
  // Screen C: Login notice (used by LoginPage, Task 9)
  loginNotice: "Entre ou crie sua conta para continuar a compra.",
} as const;
