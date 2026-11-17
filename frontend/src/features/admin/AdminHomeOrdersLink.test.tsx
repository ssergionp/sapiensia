import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, test, vi } from "vitest";
import AdminHomePage from "./AdminHomePage";

function renderWith(capabilities: string[]) {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(
    new Response(
      JSON.stringify({
        email: "a@example.com",
        capabilities,
        oldCodeRequired: true,
      }),
      {
        status: 200,
        headers: { "Content-Type": "application/json" },
      },
    ),
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

describe("AdminHomePage orders link", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  test("shows the Pedidos link with ORDERS_VIEW", async () => {
    renderWith(["PANEL_ACCESS", "ORDERS_VIEW"]);

    expect(
      await screen.findByRole("link", { name: "Pedidos" }),
    ).toHaveAttribute("href", "/painel/pedidos");
  });

  test("hides the Pedidos link without ORDERS_VIEW", async () => {
    renderWith(["PANEL_ACCESS", "PRODUCTS_EDIT"]);

    await screen.findByRole("heading", { name: "Painel do Administrador" });
    expect(
      screen.queryByRole("link", { name: "Pedidos" }),
    ).not.toBeInTheDocument();
  });
});
