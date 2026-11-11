import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router";
import { vi } from "vitest";
import { adminSessionQueryKey } from "@/features/admin/hooks";
import ProductFormPage from "./ProductFormPage";
import ProductListPage from "./ProductListPage";
import type { Product, ProductListItem } from "./schemas";

// Test helpers shared by the product screen tests.

export const SESSION_URL = "/api/admin/sessions/current";

export function jsonResponse(status: number, body?: unknown) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
  });
}

export function adminBody(capabilities: string[]) {
  return { email: "admin@example.com", capabilities };
}

export const FULL = adminBody([
  "PANEL_ACCESS",
  "PRODUCTS_EDIT",
  "PRODUCTS_PRICE",
  "PRODUCTS_PUBLISH",
]);
export const EDIT_ONLY = adminBody(["PANEL_ACCESS", "PRODUCTS_EDIT"]);

export function product(overrides: Partial<Product> = {}): Product {
  return {
    id: "p1",
    title: "Guia de saúde",
    description: "Texto do guia",
    category: "HEALTH",
    priceCents: 1990,
    status: "DRAFT",
    firstPublishedAt: null,
    version: 3,
    file: null,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

// A list item has no description (ProductListItem in the backend).
export function listItem(
  overrides: Partial<ProductListItem> = {},
): ProductListItem {
  const { description: _description, file: _file, ...item } = product();
  return { ...item, ...overrides };
}

type Handler = (
  url: string,
  init?: RequestInit,
) => Response | Promise<Response> | undefined;

// The session call is answered here; the others go to `handler` (default 200 without body).
export function mockApi(handler: Handler, session: unknown = FULL) {
  return vi
    .spyOn(globalThis, "fetch")
    .mockImplementation(async (input, init) => {
      const url = String(input);
      if (url === SESSION_URL) {
        return typeof session === "number"
          ? jsonResponse(session)
          : jsonResponse(200, session);
      }
      return (await handler(url, init)) ?? jsonResponse(200);
    });
}

// Exposes the current history state so tests can assert it was cleared.
function LocationState() {
  const location = useLocation();
  return (
    <output data-testid="location-state">
      {JSON.stringify(location.state)}
    </output>
  );
}

export function renderProducts(
  path: string | { pathname: string; state?: unknown },
  client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  }),
) {
  const view = render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <LocationState />
        <Routes>
          <Route path="/painel" element={<p>painel</p>} />
          <Route path="/painel/produtos" element={<ProductListPage />} />
          <Route path="/painel/produtos/novo" element={<ProductFormPage />} />
          <Route path="/painel/produtos/:id" element={<ProductFormPage />} />
          <Route path="/painel/entrar" element={<p>tela de entrada</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { client, ...view, sessionKey: adminSessionQueryKey };
}

export function normalizeSpaces(text: string | null) {
  return (text ?? "").replace(/\s/g, " ");
}
