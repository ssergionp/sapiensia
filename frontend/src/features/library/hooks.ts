import { useMutation, useQuery } from "@tanstack/react-query";
import { fetchLibrary, requestDownloadLink } from "./api";
import { startDownload } from "./startDownload";

export function useLibrary() {
  return useQuery({
    queryKey: ["library"],
    queryFn: fetchLibrary,
    retry: false,
  });
}

// Every click asks for a new link; there is no retry because each request records a download.
// The link is used inside the mutation function and never returned, so it does not reach the mutation
// cache or the observer state; gcTime 0 drops the finished mutation right away.
export function useDownloadLink() {
  return useMutation({
    mutationFn: async (productId: string): Promise<void> => {
      startDownload(await requestDownloadLink(productId));
    },
    retry: false,
    gcTime: 0,
  });
}
