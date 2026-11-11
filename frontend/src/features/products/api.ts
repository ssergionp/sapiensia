import { apiFetch } from "@/lib/api";
import {
  FILE_REJECTION_CODES,
  type Product,
  type ProductCategory,
  type ProductFileRejectionCode,
  type ProductPage,
  productPageSchema,
  productSchema,
} from "./schemas";

// 401: the panel session ended.
export class ProductSessionEndedError extends Error {}
// 403: the capability is missing.
export class ProductForbiddenError extends Error {}
// 403 with errors.reauthentication = REAUTHENTICATION_REQUIRED: the Product was published before and the password and code are not recent.
export class ProductFileReauthenticationRequiredError extends Error {}
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

// The code in {"errors":{"file":CODE}} of an error answer, or undefined when the body has no such string.
async function readFileErrorCode(raw: Response): Promise<string | undefined> {
  const body: unknown = await raw.json().catch(() => null);
  if (typeof body !== "object" || body === null || !("errors" in body))
    return undefined;
  const errors = body.errors;
  if (typeof errors !== "object" || errors === null || !("file" in errors))
    return undefined;
  return typeof errors.file === "string" ? errors.file : undefined;
}

async function isReauthenticationRequired(raw: Response): Promise<boolean> {
  const body: unknown = await raw.json().catch(() => null);
  if (typeof body !== "object" || body === null || !("errors" in body))
    return false;
  const errors = body.errors;
  return (
    typeof errors === "object" &&
    errors !== null &&
    "reauthentication" in errors &&
    errors.reauthentication === "REAUTHENTICATION_REQUIRED"
  );
}

// 400/409/413 with a known code in errors.file.
export class ProductFileRejectedError extends Error {
  readonly code: ProductFileRejectionCode;
  constructor(code: ProductFileRejectionCode) {
    super("Product file rejected");
    this.code = code;
  }
}

// The browser sets the multipart Content-Type (with the boundary) itself: apiFetch adds only the CSRF header.
export async function uploadProductFile(
  id: string,
  file: File,
): Promise<Product> {
  const form = new FormData();
  form.append("file", file);
  const raw = await apiFetch(
    `/api/admin/products/${encodeURIComponent(id)}/file`,
    {
      method: "POST",
      body: form,
    },
  );
  if (raw.status === 400 || raw.status === 409 || raw.status === 413) {
    const code = await readFileErrorCode(raw);
    if ((FILE_REJECTION_CODES as readonly unknown[]).includes(code)) {
      throw new ProductFileRejectedError(code as ProductFileRejectionCode);
    }
    throw new Error(`Unexpected status ${raw.status}`);
  }
  if (raw.status === 403 && (await isReauthenticationRequired(raw))) {
    throw new ProductFileReauthenticationRequiredError();
  }
  const response = await check(raw, 200);
  return productSchema.parse(await response.json());
}

// 409 with errors.file: the Product cannot be published without a file. Any other 409 stays a conflict.
async function publication(id: string, method: "PUT" | "DELETE") {
  const raw = await apiFetch(
    `/api/admin/products/${encodeURIComponent(id)}/publication`,
    { method },
  );
  if (raw.status === 409) {
    const code = await readFileErrorCode(raw);
    if (code !== undefined) throw new ProductValidationError({ file: code });
    throw new ProductConflictError();
  }
  const response = await check(raw, 200);
  return productSchema.parse(await response.json());
}

export const publishProduct = (id: string) => publication(id, "PUT");
export const unpublishProduct = (id: string) => publication(id, "DELETE");

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
