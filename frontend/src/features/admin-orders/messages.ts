import { ORDER_TEXTS } from "@/features/orders/messages";
import type { OrderStatus } from "./schemas";

// Single source of the Administrator's Orders screen texts.
export const ADMIN_ORDERS_TEXTS = {
  heading: "Pedidos",
  documentTitle: "Pedidos | SapiensIA",
  noPermission: "Você não tem permissão para ver os Pedidos.",
  filterLabel: "Filtrar por estado",
  filterAll: "Todos",
  columns: {
    id: "Pedido",
    product: "Produto",
    value: "Valor",
    status: "Estado",
    customer: "Cliente",
    createdAt: "Criado em",
    paidAt: "Pago em",
  },
  notPaid: "—",
  emptyNoFilter: "Nenhum Pedido ainda.",
  emptyWithFilter: "Nenhum Pedido neste estado.",
  loadError: "Não foi possível carregar os Pedidos agora. Tente novamente.",
  // Reused from other screens.
  loading: ORDER_TEXTS.loading,
  retry: ORDER_TEXTS.retry,
  backToPanel: "Voltar ao painel",
  previous: "Anterior",
  next: "Próxima",
  pagination: "Paginação",
  pageOf: (page: number, total: number) => `Página ${page} de ${total}`,
} as const;

export const STATUS_TEXT: Record<OrderStatus, string> = {
  PENDING: ORDER_TEXTS.statusPending,
  PAID: ORDER_TEXTS.statusPaid,
  EXPIRED: ORDER_TEXTS.statusExpired,
};
