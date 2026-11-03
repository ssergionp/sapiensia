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
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: sessionQueryKey }),
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
