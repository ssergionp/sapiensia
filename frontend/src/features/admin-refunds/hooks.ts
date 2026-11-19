import {
  keepPreviousData,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { adminSessionQueryKey } from "@/features/admin/hooks";
import { AdminRefundsSessionEndedError, fetchAdminRefunds } from "./api";
import type { RefundFilter } from "./schemas";

export function useAdminRefunds(page: number, status: RefundFilter | null) {
  const queryClient = useQueryClient();
  return useQuery({
    queryKey: ["admin-refunds", page, status],
    queryFn: async () => {
      try {
        return await fetchAdminRefunds(page, status);
      } catch (error) {
        // A 401 means the panel session ended: mark it as signed out so the guard redirects to the login.
        if (error instanceof AdminRefundsSessionEndedError) {
          queryClient.setQueryData(adminSessionQueryKey, null);
        }
        throw error;
      }
    },
    placeholderData: keepPreviousData,
    retry: false,
  });
}
