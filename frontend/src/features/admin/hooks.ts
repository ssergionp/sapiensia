import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { sessionQueryKey } from "../session/hooks";
import { adminLogin, fetchAdminSession, setAdminPassword } from "./api";

export const adminSessionQueryKey = ["admin-session"] as const;

export function useAdminSession() {
  return useQuery({
    queryKey: adminSessionQueryKey,
    queryFn: fetchAdminSession,
    retry: false,
  });
}

export function useAdminLogin() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: adminLogin,
    // Refetch (also inactive queries) so /painel already has the fresh session when we navigate there.
    // The login replaced any Customer session of this browser, so the cached Customer session is stale too.
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: sessionQueryKey });
      await queryClient.refetchQueries({ queryKey: adminSessionQueryKey });
    },
  });
}

export function useSetAdminPassword() {
  return useMutation({ mutationFn: setAdminPassword });
}
