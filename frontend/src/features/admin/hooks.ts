import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { sessionQueryKey } from "../session/hooks";
import {
  adminLogin,
  confirmMfaEnrollment,
  fetchAdminSession,
  fetchMfaStatus,
  regenerateRecoveryCodes,
  setAdminPassword,
  verifyMfaCode,
  verifyRecoveryCode,
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
    // The password alone no longer opens a panel session (the code does, see useMfaSubmit), so there is no
    // Administrator session to refetch here. It did replace any Customer session of this browser, so that one is stale.
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: sessionQueryKey });
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

// For "enroll" the recovery codes go to onCodes (component state) and never into the mutation result, so they
// do not stay in the TanStack Query cache.
export function useMfaSubmit(
  mode: "enroll" | "verify" | "recovery",
  onCodes?: (codes: string[]) => void,
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (code: string): Promise<void> => {
      if (mode === "enroll") onCodes?.(await confirmMfaEnrollment(code));
      else if (mode === "recovery") await verifyRecoveryCode(code);
      else await verifyMfaCode(code);
    },
    // The code completes the session, so refresh the cached sessions like the password login does.
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: sessionQueryKey });
      await queryClient.refetchQueries({ queryKey: adminSessionQueryKey });
    },
  });
}

export function useRegenerateRecoveryCodes(onCodes: (codes: string[]) => void) {
  return useMutation({
    mutationFn: async (code: string): Promise<void> => {
      onCodes(await regenerateRecoveryCodes(code));
    },
  });
}

export function useSetAdminPassword() {
  return useMutation({ mutationFn: setAdminPassword });
}
