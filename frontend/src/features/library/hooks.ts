import { useMutation, useQuery } from "@tanstack/react-query";
import { fetchLibrary, requestDownloadLink } from "./api";

export function useLibrary() {
  return useQuery({
    queryKey: ["library"],
    queryFn: fetchLibrary,
    retry: false,
  });
}

// Every click asks for a new link; there is no retry because each request records a download.
export function useDownloadLink() {
  return useMutation({ mutationFn: requestDownloadLink, retry: false });
}
