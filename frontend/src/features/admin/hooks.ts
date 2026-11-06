import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { sessionQueryKey } from "../session/hooks";
import {
  adminLogin,
  confirmMfaEnrollment,
  fetchAdminSession,
  fetchMfaStatus,
  setAdminPassword,
  verifyMfaCode,
} from "./api";

export const adminSessionQueryKey = ["admin-session"] as const;
export const mfaStatusQueryKey = ["admin-mfa-status"] as const;

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

// The status never refetches by itself: opening the enrollment replaces the secret on the server.
export function useMfaStatus() {
  return useQuery({
    queryKey: mfaStatusQueryKey,
    queryFn: fetchMfaStatus,
    retry: false,
    staleTime: Number.POSITIVE_INFINITY,
    gcTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
}

export function useMfaSubmit(mode: "enroll" | "verify") {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: mode === "enroll" ? confirmMfaEnrollment : verifyMfaCode,
    // The code completes the session, so refresh the cached sessions like the password login does.
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: sessionQueryKey });
      await queryClient.refetchQueries({ queryKey: adminSessionQueryKey });
    },
  });
}

export function useSetAdminPassword() {
  return useMutation({ mutationFn: setAdminPassword });
}
