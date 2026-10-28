import { useMutation } from "@tanstack/react-query";
import { registerCustomer } from "./api";

export function useRegisterCustomer() {
  return useMutation({ mutationFn: registerCustomer });
}
