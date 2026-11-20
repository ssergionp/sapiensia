import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  createOrder,
  fetchOrder,
  fetchOrderStatus,
  listOrders,
  requestRefund,
} from "./api";

// The list reads only the database; default refetching (on open and on window focus), no polling.
// gcTime 0: another Customer logging in on the same tab must not see this list.
export function useOrders() {
  return useQuery({
    queryKey: ["orders"],
    queryFn: listOrders,
    retry: false,
    gcTime: 0,
  });
}

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

// Polls only the light status route; the page refetches the full Order when the status changes.
export function useOrderStatus(id: string, enabled: boolean) {
  return useQuery({
    queryKey: ["orders", id, "status"],
    queryFn: () => fetchOrderStatus(id),
    enabled,
    refetchInterval: 5000,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
    retry: false,
    gcTime: 0,
  });
}

// gcTime 0: the variables (with the free-text comment) must not stay in the mutation cache.
// A request suspends the Access, so the Orders list and the Library are reloaded.
export function useRequestRefund() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: requestRefund,
    gcTime: 0,
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ["orders"] });
      void client.invalidateQueries({ queryKey: ["library"] });
    },
  });
}
