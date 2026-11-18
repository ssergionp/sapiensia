import {
  type QueryClient,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { useEffect, useRef } from "react";
import { useLocation, useNavigate } from "react-router";
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

export function useLogout() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: logout,
    onSuccess: () => {
      forgetPreviousAccount(queryClient);
      return queryClient.invalidateQueries({ queryKey: sessionQueryKey });
    },
  });
}

// A navigation is not applied synchronously. Without waiting, the session refresh could answer "no session"
// while the page the visitor is leaving is still mounted: it would send them to /entrar and remember itself
// as the return-to. The wait has a limit, so the cache cleanup and the refresh always happen.
const HOME_ARRIVAL_LIMIT_MS = 1000;

// Sign out, go to the home page, and only then refresh the session.
export function useLogoutToHome() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const current = useRef(pathname);
  current.current = pathname;
  const arrived = useRef<(() => void) | null>(null);
  useEffect(() => {
    if (pathname === "/") {
      arrived.current?.();
      arrived.current = null;
    }
  }, [pathname]);

  return useMutation({
    mutationFn: logout,
    onSuccess: async () => {
      if (current.current !== "/") {
        const home = new Promise<void>((resolve) => {
          arrived.current = resolve;
          setTimeout(resolve, HOME_ARRIVAL_LIMIT_MS);
        });
        navigate("/");
        await home;
      }
      forgetPreviousAccount(queryClient);
      return queryClient.invalidateQueries({ queryKey: sessionQueryKey });
    },
  });
}
