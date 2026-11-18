import {
  type QueryClient,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { fetchSession, login, logout, logoutEverywhere } from "./api";

export const sessionQueryKey = ["session"] as const;

// Drops the previous account's cached data. Only inactive queries go: a query still on screen
// (the admin panel's) would refetch if removed, and the session query is handled by each hook.
function forgetPreviousAccount(queryClient: QueryClient) {
  queryClient.removeQueries({
    type: "inactive",
    predicate: (query) => query.queryKey[0] !== sessionQueryKey[0],
  });
}

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
    onSuccess: () => {
      forgetPreviousAccount(queryClient);
      return queryClient.refetchQueries({ queryKey: sessionQueryKey });
    },
  });
}

export function useLogoutEverywhere() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: logoutEverywhere,
    onSuccess: () => {
      forgetPreviousAccount(queryClient);
      return queryClient.invalidateQueries({ queryKey: sessionQueryKey });
    },
  });
}

// `onSignedOut` runs before the session is refreshed: the page the visitor leaves must be gone by then,
// otherwise it would see "no session" first and send them to /entrar (remembering itself as return-to).
export function useLogout(onSignedOut?: () => void) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: logout,
    onSuccess: () => {
      onSignedOut?.();
      forgetPreviousAccount(queryClient);
      return queryClient.invalidateQueries({ queryKey: sessionQueryKey });
    },
  });
}
