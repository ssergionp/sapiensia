import { apiFetch } from "@/lib/api";
import {
  type AdminRefundPage,
  adminRefundPageSchema,
  type RefundFilter,
} from "./schemas";

// 401: the panel session ended.
export class AdminRefundsSessionEndedError extends Error {}
// 403: the REFUNDS_VIEW capability is missing.
export class AdminRefundsForbiddenError extends Error {}

export async function fetchAdminRefunds(
  page: number,
  status: RefundFilter | null,
): Promise<AdminRefundPage> {
  const query =
    status === null ? `page=${page}` : `status=${status}&page=${page}`;
  const response = await apiFetch(`/api/admin/refund-requests?${query}`);
  if (response.status === 401) throw new AdminRefundsSessionEndedError();
  if (response.status === 403) throw new AdminRefundsForbiddenError();
  if (response.status !== 200) {
    throw new Error(`Unexpected status ${response.status}`);
  }
  return adminRefundPageSchema.parse(await response.json());
}
