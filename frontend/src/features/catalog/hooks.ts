import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { fetchCatalog, fetchCatalogProduct } from "./api";

export const catalogQueryKey = ["catalog"] as const;

export function useCatalog(page: number) {
  return useQuery({
    queryKey: [...catalogQueryKey, "list", page],
    queryFn: () => fetchCatalog(page),
    placeholderData: keepPreviousData,
    retry: false,
  });
}

export function useCatalogProduct(id: string) {
  return useQuery({
    queryKey: [...catalogQueryKey, "product", id],
    queryFn: () => fetchCatalogProduct(id),
    retry: false,
  });
}
