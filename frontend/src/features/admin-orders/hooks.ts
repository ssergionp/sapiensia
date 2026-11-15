import {
  keepPreviousData,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { adminSessionQueryKey } from "@/features/admin/hooks";
import { AdminOrdersSessionEndedError, fetchAdminOrders } from "./api";
import type { OrderStatus } from "./schemas";

export function useAdminOrders(page: number, status: OrderStatus | null) {
  const queryClient = useQueryClient();
  return useQuery({
    queryKey: ["admin-orders", page, status],
    queryFn: async () => {
      try {
        return await fetchAdminOrders(page, status);
      } catch (error) {
        // A 401 means the panel session ended: mark it as signed out so the guard redirects to the login.
        if (error instanceof AdminOrdersSessionEndedError) {
          queryClient.setQueryData(adminSessionQueryKey, null);
        }
        throw error;
      }
    },
    placeholderData: keepPreviousData,
    retry: false,
  });
}
