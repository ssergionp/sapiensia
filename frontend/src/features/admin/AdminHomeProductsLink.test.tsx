import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, test, vi } from "vitest";
import AdminHomePage from "./AdminHomePage";

function renderWith(capabilities: string[]) {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(
    new Response(JSON.stringify({ email: "a@example.com", capabilities }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }),
  );
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter>
        <AdminHomePage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("AdminHomePage products link", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  test("shows the Produtos link with PRODUCTS_EDIT", async () => {
    renderWith(["PANEL_ACCESS", "PRODUCTS_EDIT"]);

    expect(
      await screen.findByRole("link", { name: "Produtos" }),
    ).toHaveAttribute("href", "/painel/produtos");
  });

  test("hides the Produtos link without PRODUCTS_EDIT", async () => {
    renderWith(["PANEL_ACCESS", "PRODUCTS_PRICE"]);

    await screen.findByRole("heading", { name: "Painel do Administrador" });
    expect(
      screen.queryByRole("link", { name: "Produtos" }),
    ).not.toBeInTheDocument();
  });
});
