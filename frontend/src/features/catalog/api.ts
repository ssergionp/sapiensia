import { apiFetch } from "@/lib/api";
import {
  type CatalogPage,
  type CatalogProduct,
  catalogPageSchema,
  catalogProductSchema,
} from "./schemas";

// 404 (unknown, draft or unpublished Product) or 400 (malformed id): the screen shows "not found".
export class CatalogProductNotFoundError extends Error {}

function ensureOk(response: Response): Response {
  if (response.status === 200) return response;
  throw new Error(`Unexpected status ${response.status}`);
}

export async function fetchCatalog(page: number): Promise<CatalogPage> {
  const response = ensureOk(await apiFetch(`/api/products?page=${page}`));
  return catalogPageSchema.parse(await response.json());
}

export async function fetchCatalogProduct(id: string): Promise<CatalogProduct> {
  const response = await apiFetch(`/api/products/${encodeURIComponent(id)}`);
  if (response.status === 404 || response.status === 400) {
    throw new CatalogProductNotFoundError();
  }
  return catalogProductSchema.parse(await ensureOk(response).json());
}
