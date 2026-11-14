import { useMutation, useQuery } from "@tanstack/react-query";
import { createOrder, fetchOrder } from "./api";

// The order page fetches the order itself, so creating one does not seed the cache.
// gcTime 0: the variables (with the CPF) must not stay in the mutation cache.
export function useCreateOrder() {
  return useMutation({ mutationFn: createOrder, gcTime: 0 });
}

// One read at the provider per page opening (no refetch on focus); gcTime 0 so the Pix code does not stay cached.
export function useOrder(id: string) {
  return useQuery({
    queryKey: ["orders", id],
    queryFn: () => fetchOrder(id),
    retry: false,
    refetchOnWindowFocus: false,
    gcTime: 0,
  });
}
