import { apiFetch } from "@/lib/api";
import {
  downloadLinkSchema,
  type LibraryResponse,
  libraryResponseSchema,
} from "./schemas";

export class NotLoggedInError extends Error {}

export class DownloadUnavailableError extends Error {}

export async function fetchLibrary(): Promise<LibraryResponse> {
  const response = await apiFetch("/api/library");
  if (response.status === 200) {
    return libraryResponseSchema.parse(await response.json());
  }
  if (response.status === 401) throw new NotLoggedInError();
  throw new Error(`Unexpected status ${response.status}`);
}

export async function requestDownloadLink(productId: string): Promise<string> {
  const response = await apiFetch(
    `/api/library/${encodeURIComponent(productId)}/download`,
    { method: "POST" },
  );
  if (response.status === 200) {
    return downloadLinkSchema.parse(await response.json()).url;
  }
  if (response.status === 401) throw new NotLoggedInError();
  if (response.status === 404) throw new DownloadUnavailableError();
  throw new Error(`Unexpected status ${response.status}`);
}
