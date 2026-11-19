import { ADMIN_ORDERS_TEXTS } from "@/features/admin-orders/messages";
import { ORDER_TEXTS } from "@/features/orders/messages";

// Single source of the Administrator's Refund requests screen texts.
export const ADMIN_REFUNDS_TEXTS = {
  heading: "Reembolsos solicitados",
  documentTitle: "Reembolsos solicitados | SapiensIA",
  guidance:
    "Para devolver o dinheiro, estorne o valor inteiro da cobrança no painel do Asaas. Quando o Asaas confirmar, o Pedido passa a Reembolsado e o Acesso é cancelado sozinho.",
  noPermission: "Você não tem permissão para ver os Reembolsos.",
  filterLabel: "Filtrar por situação",
  filterAll: "Todos",
  filterOpen: "Aguardando devolução",
  filterRefunded: "Devolvidos",
  columns: {
    id: "Pedido",
    product: "Produto",
    value: "Valor",
    customer: "Cliente",
    reason: "Motivo",
    requestedAt: "Solicitado em",
    paidAt: "Pago em",
    downloads: "Downloads do Produto",
    otherRefunds: "Outros Reembolsos em 12 meses",
    charge: "Cobrança no Asaas",
    status: "Situação",
  },
  statusOpen: "Aguardando devolução",
  statusRefundedAt: (dateTime: string) => `Devolvido em ${dateTime}`,
  customerComment: (text: string) => `Comentário do Cliente: ${text}`,
  emptyOpen: "Nenhum Reembolso aguardando devolução.",
  emptyRefunded: "Nenhum Reembolso devolvido.",
  emptyAll: "Nenhum Reembolso solicitado ainda.",
  loadError: "Não foi possível carregar os Reembolsos agora. Tente novamente.",
  // Reused from other screens.
  notPaid: ADMIN_ORDERS_TEXTS.notPaid,
  loading: ADMIN_ORDERS_TEXTS.loading,
  retry: ADMIN_ORDERS_TEXTS.retry,
  backToPanel: ADMIN_ORDERS_TEXTS.backToPanel,
  previous: ADMIN_ORDERS_TEXTS.previous,
  next: ADMIN_ORDERS_TEXTS.next,
  pagination: ADMIN_ORDERS_TEXTS.pagination,
  pageOf: ADMIN_ORDERS_TEXTS.pageOf,
  reasons: ORDER_TEXTS.refundReasons,
} as const;
