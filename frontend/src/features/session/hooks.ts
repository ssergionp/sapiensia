import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { fetchSession, login, logout } from "./api";

export const sessionQueryKey = ["session"] as const;

export function useSession() {
  return useQuery({
    queryKey: sessionQueryKey,
    queryFn: fetchSession,
    retry: false,
  });
}

export function useLogin() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: login,
    // The login page has no active session observer, so invalidate would only mark the cache stale.
    // Refetching (also inactive queries) makes the mutation wait for the fresh state before navigating.
    onSuccess: () => queryClient.refetchQueries({ queryKey: sessionQueryKey }),
  });
}

export function useLogout() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: logout,
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: sessionQueryKey }),
  });
}
