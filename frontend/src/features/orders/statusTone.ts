import type { StatusTone } from "@/components/StatusBadge";
import type { OrderList } from "./schemas";

// The badge tone of each Order status, shared by Meus Pedidos and the admin Orders table
export const ORDER_STATUS_TONE: Record<
  OrderList["items"][number]["status"],
  StatusTone
> = {
  PENDING: "pending",
  PAID: "success",
  EXPIRED: "muted",
  REFUNDED: "neutral",
};
