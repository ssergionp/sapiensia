import { apiFetch } from "@/lib/api";
import {
  type AdminOrderPage,
  adminOrderPageSchema,
  type OrderStatus,
} from "./schemas";

// 401: the panel session ended.
export class AdminOrdersSessionEndedError extends Error {}
// 403: the ORDERS_VIEW capability is missing.
export class AdminOrdersForbiddenError extends Error {}

export async function fetchAdminOrders(
  page: number,
  status: OrderStatus | null,
): Promise<AdminOrderPage> {
  const query =
    status === null ? `page=${page}` : `status=${status}&page=${page}`;
  const response = await apiFetch(`/api/admin/orders?${query}`);
  if (response.status === 401) throw new AdminOrdersSessionEndedError();
  if (response.status === 403) throw new AdminOrdersForbiddenError();
  if (response.status !== 200) {
    throw new Error(`Unexpected status ${response.status}`);
  }
  return adminOrderPageSchema.parse(await response.json());
}
