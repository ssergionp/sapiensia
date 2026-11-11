import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, test, vi } from "vitest";
import {
  EDIT_ONLY,
  jsonResponse,
  listItem,
  mockApi,
  normalizeSpaces,
  renderProducts,
} from "./testing";

const LIST = "/api/admin/products?page=0";

afterEach(() => {
  vi.restoreAllMocks();
});

describe("ProductListPage", () => {
  test("shows Carregando… while the list loads", async () => {
    vi.spyOn(globalThis, "fetch").mockReturnValue(new Promise(() => {}));
    renderProducts("/painel/produtos");

    expect(screen.getByText("Carregando…")).toBeInTheDocument();
  });

  test("FR-016 shows the real status of each Product", async () => {
    mockApi((url) =>
      url === LIST
        ? jsonResponse(200, {
            items: [
              listItem({ id: "p1", title: "Um", status: "PUBLISHED" }),
              listItem({ id: "p2", title: "Dois", status: "UNPUBLISHED" }),
            ],
            page: 0,
            totalPages: 1,
          })
        : undefined,
    );
    renderProducts("/painel/produtos");
    const one = await screen.findByRole("row", { name: /Um/ });
    expect(within(one).getByText("Publicado")).toBeInTheDocument();
    const two = screen.getByRole("row", { name: /Dois/ });
    expect(within(two).getByText("Despublicado")).toBeInTheDocument();
  });

  test("renders the table with headers, row values and an accessible edit link", async () => {
    mockApi((url) =>
      url === LIST
        ? jsonResponse(200, {
            items: [
              listItem(),
              listItem({
                id: "p2",
                title: "Outro",
                category: "LAW",
                priceCents: 500,
              }),
            ],
            page: 0,
            totalPages: 1,
          })
        : undefined,
    );
    renderProducts("/painel/produtos");

    expect(
      await screen.findByRole("heading", { name: "Produtos" }),
    ).toBeInTheDocument();
    const headers = await screen.findAllByRole("columnheader");
    expect(headers.map((h) => h.textContent)).toEqual([
      "Título",
      "Categoria",
      "Preço",
      "Estado",
      "Ações",
    ]);
    for (const h of headers) expect(h).toHaveAttribute("scope", "col");
    const row = screen.getByRole("row", { name: /Guia de saúde/ });
    expect(within(row).getByText("Saúde")).toBeInTheDocument();
    expect(
      normalizeSpaces(within(row).getAllByRole("cell")[2].textContent),
    ).toBe("R$ 19,90");
    expect(within(row).getByText("Rascunho")).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "Editar Guia de saúde" }),
    ).toHaveAttribute("href", "/painel/produtos/p1");
    expect(screen.getByRole("link", { name: "Novo Produto" })).toHaveAttribute(
      "href",
      "/painel/produtos/novo",
    );
    expect(
      screen.getByRole("link", { name: "Voltar ao painel" }),
    ).toHaveAttribute("href", "/painel");
  });

  test("renders the description as plain text elsewhere: title with markup is escaped", async () => {
    mockApi(() =>
      jsonResponse(200, {
        items: [listItem({ title: "<b>negrito</b>" })],
        page: 0,
        totalPages: 1,
      }),
    );
    renderProducts("/painel/produtos");

    expect(await screen.findByText("<b>negrito</b>")).toBeInTheDocument();
  });

  test("empty list shows the invitation", async () => {
    mockApi(() => jsonResponse(200, { items: [], page: 0, totalPages: 0 }));
    renderProducts("/painel/produtos");

    expect(
      await screen.findByText(
        'Nenhum Produto cadastrado ainda. Use "Novo Produto" para criar o primeiro.',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  test("pagination moves between pages and disables at the ends", async () => {
    const calls: string[] = [];
    mockApi((url) => {
      calls.push(url);
      if (url === LIST)
        return jsonResponse(200, {
          items: [listItem()],
          page: 0,
          totalPages: 2,
        });
      if (url === "/api/admin/products?page=1")
        return jsonResponse(200, {
          items: [listItem({ id: "p9", title: "Segunda página" })],
          page: 1,
          totalPages: 2,
        });
      return undefined;
    });
    renderProducts("/painel/produtos");
    const user = userEvent.setup();

    const nav = await screen.findByRole("navigation", { name: "Paginação" });
    expect(within(nav).getByText("Página 1 de 2")).toBeInTheDocument();
    expect(
      within(nav).getByRole("button", { name: "Anterior" }),
    ).toBeDisabled();
    await user.click(within(nav).getByRole("button", { name: "Próxima" }));

    expect(await screen.findByText("Segunda página")).toBeInTheDocument();
    expect(screen.getByText("Página 2 de 2")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Próxima" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Anterior" })).toBeEnabled();
    expect(calls).toContain("/api/admin/products?page=1");
  });

  test("shows the created notice after a Product is created", async () => {
    mockApi(() => jsonResponse(200, { items: [], page: 0, totalPages: 0 }));
    renderProducts({ pathname: "/painel/produtos", state: { created: true } });

    expect(await screen.findByText("Produto criado.")).toHaveAttribute(
      "role",
      "status",
    );
  });

  test("the created notice stays while on the page but the history state is cleared", async () => {
    mockApi(() => jsonResponse(200, { items: [], page: 0, totalPages: 0 }));
    renderProducts({ pathname: "/painel/produtos", state: { created: true } });

    expect(await screen.findByText("Produto criado.")).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByTestId("location-state")).toHaveTextContent("null"),
    );
    expect(screen.getByText("Produto criado.")).toBeInTheDocument();
  });

  test("a reload of the list at the same URL (no history state) shows no notice", async () => {
    mockApi(() => jsonResponse(200, { items: [], page: 0, totalPages: 0 }));
    renderProducts({ pathname: "/painel/produtos", state: null });

    await screen.findByText(/Nenhum Produto cadastrado/);
    expect(screen.queryByText("Produto criado.")).not.toBeInTheDocument();
  });

  test("no created notice on a plain visit", async () => {
    mockApi(() => jsonResponse(200, { items: [], page: 0, totalPages: 0 }));
    renderProducts("/painel/produtos");

    await screen.findByText(/Nenhum Produto cadastrado/);
    expect(screen.queryByText("Produto criado.")).not.toBeInTheDocument();
  });

  test("401 sends the user to the login", async () => {
    mockApi((url) => (url === LIST ? jsonResponse(401) : undefined));
    renderProducts("/painel/produtos");

    expect(await screen.findByText("tela de entrada")).toBeInTheDocument();
  });

  test("403 shows no permission", async () => {
    mockApi((url) => (url === LIST ? jsonResponse(403) : undefined));
    renderProducts("/painel/produtos");

    expect(
      await screen.findByText(
        "Você não tem permissão para gerenciar Produtos.",
      ),
    ).toHaveAttribute("role", "alert");
  });

  test("503 shows unavailable", async () => {
    mockApi((url) => (url === LIST ? jsonResponse(503) : undefined));
    renderProducts("/painel/produtos");

    expect(
      await screen.findByText(
        "O sistema está indisponível no momento. Tente novamente em alguns instantes.",
      ),
    ).toHaveAttribute("role", "alert");
  });

  test("other errors show the load error", async () => {
    mockApi((url) => (url === LIST ? jsonResponse(500) : undefined));
    renderProducts("/painel/produtos");

    expect(
      await screen.findByText(
        "Não foi possível carregar os Produtos agora. Tente novamente.",
      ),
    ).toHaveAttribute("role", "alert");
  });

  test("without PRODUCTS_EDIT the guard shows no permission and never lists", async () => {
    const spy = mockApi(
      () => jsonResponse(200, { items: [], page: 0, totalPages: 0 }),
      {
        email: "a@example.com",
        capabilities: ["PANEL_ACCESS"],
      },
    );
    renderProducts("/painel/produtos");

    expect(
      await screen.findByText(
        "Você não tem permissão para gerenciar Produtos.",
      ),
    ).toBeInTheDocument();
    expect(
      spy.mock.calls.some(([u]) => String(u).startsWith("/api/admin/products")),
    ).toBe(false);
  });

  test("Customer session shows the Administrator-only message", async () => {
    mockApi(() => undefined, 403);
    renderProducts("/painel/produtos");

    expect(
      await screen.findByText("Esta área é só para o Administrador."),
    ).toBeInTheDocument();
  });

  test("EDIT_ONLY can list", async () => {
    mockApi(
      () => jsonResponse(200, { items: [listItem()], page: 0, totalPages: 1 }),
      EDIT_ONLY,
    );
    renderProducts("/painel/produtos");

    expect(await screen.findByText("Guia de saúde")).toBeInTheDocument();
  });
});
