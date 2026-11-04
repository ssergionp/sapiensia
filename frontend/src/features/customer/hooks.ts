import { useMutation } from "@tanstack/react-query";
import {
  confirmEmail,
  registerCustomer,
  requestPasswordReset,
  resetPassword,
} from "./api";

export function useRegisterCustomer() {
  return useMutation({ mutationFn: registerCustomer });
}

export function useConfirmEmail() {
  return useMutation({ mutationFn: confirmEmail });
}

export function useRequestPasswordReset() {
  return useMutation({ mutationFn: requestPasswordReset });
}

export function useResetPassword() {
  return useMutation({ mutationFn: resetPassword });
}
