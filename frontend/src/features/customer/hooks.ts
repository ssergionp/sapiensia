import { useMutation } from "@tanstack/react-query";
import { confirmEmail, registerCustomer } from "./api";

export function useRegisterCustomer() {
  return useMutation({ mutationFn: registerCustomer });
}

export function useConfirmEmail() {
  return useMutation({ mutationFn: confirmEmail });
}
