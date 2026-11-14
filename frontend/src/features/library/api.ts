import { apiFetch } from "@/lib/api";
import { type LibraryResponse, libraryResponseSchema } from "./schemas";

export class NotLoggedInError extends Error {}

export async function fetchLibrary(): Promise<LibraryResponse> {
  const response = await apiFetch("/api/library");
  if (response.status === 200) {
    return libraryResponseSchema.parse(await response.json());
  }
  if (response.status === 401) throw new NotLoggedInError();
  throw new Error(`Unexpected status ${response.status}`);
}
