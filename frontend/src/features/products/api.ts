import { apiFetch } from "@/lib/api";
import {
  type Product,
  type ProductCategory,
  type ProductPage,
  productPageSchema,
  productSchema,
} from "./schemas";

// 401: the panel session ended.
export class ProductSessionEndedError extends Error {}
// 403: the capability is missing.
export class ProductForbiddenError extends Error {}
// 404: no such Product.
export class ProductNotFoundError extends Error {}
// 409: the Product changed since it was loaded (stale version).
export class ProductConflictError extends Error {}
// 503: database or audit trail unavailable; nothing was saved.
export class ProductUnavailableError extends Error {}
// 400 with the errors map of field to code.
export class ProductValidationError extends Error {
  readonly errors: Record<string, string>;
  constructor(errors: Record<string, string>) {
    super("Product rejected");
    this.errors = errors;
  }
}

async function check(response: Response, ok: number): Promise<Response> {
  if (response.status === ok) return response;
  if (response.status === 401) throw new ProductSessionEndedError();
  if (response.status === 403) throw new ProductForbiddenError();
  if (response.status === 404) throw new ProductNotFoundError();
  if (response.status === 409) throw new ProductConflictError();
  if (response.status === 503) throw new ProductUnavailableError();
  if (response.status === 400) {
    const body: unknown = await response.json().catch(() => null);
    const errors =
      typeof body === "object" && body !== null && "errors" in body
        ? body.errors
        : null;
    if (typeof errors === "object" && errors !== null) {
      throw new ProductValidationError(errors as Record<string, string>);
    }
  }
  throw new Error(`Unexpected status ${response.status}`);
}

function sendJson(method: string, body: unknown): RequestInit {
  return {
    method,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  };
}

export async function fetchProducts(page: number): Promise<ProductPage> {
  const response = await check(
    await apiFetch(`/api/admin/products?page=${page}`),
    200,
  );
  return productPageSchema.parse(await response.json());
}

export async function fetchProduct(id: string): Promise<Product> {
  const raw = await apiFetch(`/api/admin/products/${encodeURIComponent(id)}`);
  // The backend answers 400 when the id is not a number: for the user it is the same as no such Product.
  if (raw.status === 400) throw new ProductNotFoundError();
  const response = await check(raw, 200);
  return productSchema.parse(await response.json());
}

export type ProductData = {
  title: string;
  description: string;
  category: ProductCategory;
};

// priceCents is a JSON integer: the backend refuses 49.9.
export async function createProduct(
  input: ProductData & { priceCents: number },
): Promise<Product> {
  const response = await check(
    await apiFetch("/api/admin/products", sendJson("POST", input)),
    201,
  );
  return productSchema.parse(await response.json());
}

export async function updateProduct(
  id: string,
  input: ProductData & { version: number },
): Promise<Product> {
  const response = await check(
    await apiFetch(
      `/api/admin/products/${encodeURIComponent(id)}`,
      sendJson("PUT", input),
    ),
    200,
  );
  return productSchema.parse(await response.json());
}

export async function updateProductPrice(
  id: string,
  input: { priceCents: number; version: number },
): Promise<Product> {
  const response = await check(
    await apiFetch(
      `/api/admin/products/${encodeURIComponent(id)}/price`,
      sendJson("PUT", input),
    ),
    200,
  );
  return productSchema.parse(await response.json());
}
