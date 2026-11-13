import {
  keepPreviousData,
  type QueryClient,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { adminSessionQueryKey } from "@/features/admin/hooks";
import {
  createProduct,
  fetchProduct,
  fetchProducts,
  ProductSessionEndedError,
  publishProduct,
  removeProductCover,
  unpublishProduct,
  updateProduct,
  updateProductPrice,
  uploadProductCover,
  uploadProductFile,
} from "./api";
import type { Product } from "./schemas";

export const productsQueryKey = ["admin-products"] as const;
export const productQueryKey = (id: string) => ["admin-product", id] as const;

// A 401 means the panel session ended: mark it as signed out (like the sign-out does) so the page redirects.
function endSessionOn401(queryClient: QueryClient) {
  return (error: unknown) => {
    if (error instanceof ProductSessionEndedError) {
      queryClient.setQueryData(adminSessionQueryKey, null);
    }
  };
}

async function guard<T>(queryClient: QueryClient, call: Promise<T>) {
  try {
    return await call;
  } catch (error) {
    endSessionOn401(queryClient)(error);
    throw error;
  }
}

export function useProducts(page: number) {
  const queryClient = useQueryClient();
  return useQuery({
    queryKey: [...productsQueryKey, page],
    queryFn: () => guard(queryClient, fetchProducts(page)),
    placeholderData: keepPreviousData,
    retry: false,
  });
}

// The version of the loaded Product is what the saves send, so a background refetch must not swap it silently.
export function useProduct(id: string | undefined) {
  const queryClient = useQueryClient();
  return useQuery({
    queryKey: productQueryKey(id ?? ""),
    queryFn: () => guard(queryClient, fetchProduct(id ?? "")),
    enabled: id !== undefined,
    retry: false,
    staleTime: Number.POSITIVE_INFINITY,
    refetchOnMount: "always",
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
}

export function useCreateProduct() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: createProduct,
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: productsQueryKey }),
    onError: endSessionOn401(queryClient),
  });
}

// Both saves return the whole Product: the cache takes it, so both blocks use the new version.
export function useUpdateProduct(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: Parameters<typeof updateProduct>[1]) =>
      updateProduct(id, input),
    onSuccess: (saved: Product) => {
      queryClient.setQueryData(productQueryKey(id), saved);
      void queryClient.invalidateQueries({ queryKey: productsQueryKey });
    },
    onError: endSessionOn401(queryClient),
  });
}

// The answer is the whole Product: the cache takes it, so the "Arquivo atual" line and the version are current.
export function useUploadProductFile(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (file: File) => uploadProductFile(id, file),
    onSuccess: (saved: Product) => {
      queryClient.setQueryData(productQueryKey(id), saved);
      void queryClient.invalidateQueries({ queryKey: productsQueryKey });
    },
    onError: endSessionOn401(queryClient),
  });
}

export function useUploadProductCover(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (file: File) => uploadProductCover(id, file),
    onSuccess: (saved: Product) => {
      queryClient.setQueryData(productQueryKey(id), saved);
      void queryClient.invalidateQueries({ queryKey: productsQueryKey });
    },
    onError: endSessionOn401(queryClient),
  });
}

// The DELETE answers 204 without a body: the Product is reloaded so the cover line and the version are current.
export function useRemoveProductCover(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => removeProductCover(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: productQueryKey(id) });
      void queryClient.invalidateQueries({ queryKey: productsQueryKey });
    },
    onError: endSessionOn401(queryClient),
  });
}

export function useUpdateProductPrice(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: Parameters<typeof updateProductPrice>[1]) =>
      updateProductPrice(id, input),
    onSuccess: (saved: Product) => {
      queryClient.setQueryData(productQueryKey(id), saved);
      void queryClient.invalidateQueries({ queryKey: productsQueryKey });
    },
    onError: endSessionOn401(queryClient),
  });
}

// The answer is the whole Product: the cache takes it, so the status and the file section are current.
function usePublication(id: string, call: (id: string) => Promise<Product>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => call(id),
    onSuccess: (saved: Product) => {
      queryClient.setQueryData(productQueryKey(id), saved);
      void queryClient.invalidateQueries({ queryKey: productsQueryKey });
    },
    // A refused change means the screen is stale (file removed, already published...): reload the real state.
    onError: (error: unknown) => {
      endSessionOn401(queryClient)(error);
      void queryClient.invalidateQueries({ queryKey: productQueryKey(id) });
      void queryClient.invalidateQueries({ queryKey: productsQueryKey });
    },
  });
}

export const usePublishProduct = (id: string) =>
  usePublication(id, publishProduct);

export const useUnpublishProduct = (id: string) =>
  usePublication(id, unpublishProduct);
