import { useQuery } from "@tanstack/react-query";
import { fetchLibrary } from "./api";

export function useLibrary() {
  return useQuery({
    queryKey: ["library"],
    queryFn: fetchLibrary,
    retry: false,
  });
}
