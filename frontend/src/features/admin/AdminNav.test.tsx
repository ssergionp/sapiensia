import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, test, vi } from "vitest";
import AdminNav from "./AdminNav";

const LOGOUT_ERROR = "Não foi possível sair agora. Tente novamente.";

function renderNav(capabilities: string[], path = "/painel") {
  render(
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: {
            queries: { retry: false },
            mutations: { retry: false },
          },
        })
      }
    >
      <MemoryRouter initialEntries={[path]}>
        <AdminNav
          session={{
            email: "a@example.com",
            capabilities,
            oldCodeRequired: true,
          }}
        />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return screen.getByRole("navigation", { name: "Navegação do painel" });
}

describe("AdminNav", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  test("with both capabilities shows Painel, Produtos, Pedidos and Sair", () => {
    const nav = renderNav(["PANEL_ACCESS", "PRODUCTS_EDIT", "ORDERS_VIEW"]);

    expect(within(nav).getByRole("link", { name: "Painel" })).toHaveAttribute(
      "href",
      "/painel",
    );
    expect(within(nav).getByRole("link", { name: "Produtos" })).toHaveAttribute(
      "href",
      "/painel/produtos",
    );
    expect(within(nav).getByRole("link", { name: "Pedidos" })).toHaveAttribute(
      "href",
      "/painel/pedidos",
    );
    expect(screen.getByRole("button", { name: "Sair" })).toBeInTheDocument();
  });

  test("without ORDERS_VIEW hides Pedidos", () => {
    const nav = renderNav(["PANEL_ACCESS", "PRODUCTS_EDIT"]);

    expect(
      within(nav).queryByRole("link", { name: "Pedidos" }),
    ).not.toBeInTheDocument();
    expect(within(nav).getByRole("link", { name: "Produtos" })).toBeVisible();
  });

  test("without PRODUCTS_EDIT hides Produtos", () => {
    const nav = renderNav(["PANEL_ACCESS", "ORDERS_VIEW"]);

    expect(
      within(nav).queryByRole("link", { name: "Produtos" }),
    ).not.toBeInTheDocument();
    expect(within(nav).getByRole("link", { name: "Pedidos" })).toBeVisible();
  });

  test("marks only the current page with aria-current", () => {
    const nav = renderNav(
      ["PANEL_ACCESS", "PRODUCTS_EDIT", "ORDERS_VIEW"],
      "/painel/produtos/novo",
    );

    expect(within(nav).getByRole("link", { name: "Produtos" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(
      within(nav).getByRole("link", { name: "Painel" }),
    ).not.toHaveAttribute("aria-current");
    expect(
      within(nav).getByRole("link", { name: "Pedidos" }),
    ).not.toHaveAttribute("aria-current");
  });

  test("a failed sign-out shows LOGOUT_ERROR", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(null, { status: 500 }),
    );
    renderNav(["PANEL_ACCESS"], "/painel/pedidos");

    await userEvent.setup().click(screen.getByRole("button", { name: "Sair" }));

    expect(await screen.findByText(LOGOUT_ERROR)).toHaveAttribute(
      "role",
      "alert",
    );
  });
});
