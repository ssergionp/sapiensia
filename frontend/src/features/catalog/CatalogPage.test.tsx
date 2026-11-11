import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "@/features/products/testing";
import CatalogPage from "./CatalogPage";
import type { CatalogItem } from "./schemas";

function item(overrides: Partial<CatalogItem> = {}): CatalogItem {
  return {
    id: "p1",
    title: "Guia",
    summary: "Resumo…",
    category: "HEALTH",
    priceCents: 1990,
    ...overrides,
  };
}

function pageBody(items: CatalogItem[], page = 0, totalPages = 1) {
  return { items, page, totalPages };
}

function renderCatalog(path = "/catalogo") {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/catalogo" element={<CatalogPage />} />
          <Route path="/catalogo/:id" element={<p>detalhe</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function urlsOf(spy: { mock: { calls: unknown[][] } }) {
  return spy.mock.calls.map(([url]) => String(url));
}

beforeEach(() => {
  vi.spyOn(window, "scrollTo").mockImplementation(() => {});
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("CatalogPage", () => {
  test('mostra "Carregando…" enquanto carrega', () => {
    vi.spyOn(globalThis, "fetch").mockReturnValue(new Promise(() => {}));
    renderCatalog();

    expect(screen.getByText("Carregando…")).toBeInTheDocument();
  });

  test("FR-016 FR-018 lista título, resumo, categoria e preço em reais", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(200, pageBody([item()])),
    );
    renderCatalog();

    expect(
      await screen.findByRole("heading", { level: 1, name: "Catálogo" }),
    ).toBeInTheDocument();
    const link = await screen.findByRole("link", { name: "Guia" });
    expect(link).toHaveAttribute("href", "/catalogo/p1");
    expect(link.closest("h2")).not.toBeNull();
    const row = screen.getByRole("listitem");
    expect(within(row).getByText(/Saúde/)).toBeInTheDocument();
    expect((row.textContent ?? "").replace(/\s/g, " ")).toContain("R$ 19,90");
    expect(screen.getByText("Resumo…")).toBeInTheDocument();
    expect(document.title).toBe("Catálogo | SapiensIA");
  });

  test("lista vazia mostra a mensagem", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(200, pageBody([])),
    );
    renderCatalog();

    expect(
      await screen.findByText("Nenhum Produto disponível no momento."),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("navigation", { name: "Paginação" }),
    ).not.toBeInTheDocument();
  });

  test("erro mostra a mensagem e Tentar novamente busca de novo", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse(500))
      .mockResolvedValueOnce(jsonResponse(200, pageBody([item()])));
    renderCatalog();

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(
      "Não foi possível carregar o Catálogo agora. Tente novamente.",
    );
    await userEvent
      .setup()
      .click(screen.getByRole("button", { name: "Tentar novamente" }));

    expect(await screen.findByRole("link", { name: "Guia" })).toBeVisible();
    expect(urlsOf(fetchSpy)).toEqual([
      "/api/products?page=0",
      "/api/products?page=0",
    ]);
  });

  test("?pagina=2 pede page=1", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(200, pageBody([item()], 1, 2)));
    renderCatalog("/catalogo?pagina=2");

    await screen.findByRole("link", { name: "Guia" });
    expect(urlsOf(fetchSpy)).toEqual(["/api/products?page=1"]);
  });

  test.each(["abc", "0", "-1", "1.5", "9999999999"])(
    "?pagina=%s pede page=0",
    async (raw) => {
      const fetchSpy = vi
        .spyOn(globalThis, "fetch")
        .mockResolvedValue(jsonResponse(200, pageBody([item()])));
      renderCatalog(`/catalogo?pagina=${raw}`);

      await screen.findByRole("link", { name: "Guia" });
      expect(urlsOf(fetchSpy)).toEqual(["/api/products?page=0"]);
    },
  );

  test("?pagina=999999999 pede page=999999998 e mostra o texto de página sem Produtos", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(200, pageBody([], 999999998, 2)));
    renderCatalog("/catalogo?pagina=999999999");

    expect(
      await screen.findByText(/Esta página do Catálogo não tem Produtos\./),
    ).toBeInTheDocument();
    expect(urlsOf(fetchSpy)).toEqual(["/api/products?page=999999998"]);
  });

  test("cada item tem os rótulos Categoria: e Preço: só para leitor de tela", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(200, pageBody([item()])),
    );
    renderCatalog();

    const row = await screen.findByRole("listitem");
    expect(row).toHaveTextContent(/Categoria:\s*Saúde/);
    expect(row).toHaveTextContent(/Preço:\s*R\$\s19,90/);
    expect(within(row).getByText("Categoria:")).toHaveClass("sr-only");
    expect(within(row).getByText("Preço:")).toHaveClass("sr-only");
    expect(within(row).getByText("·")).toHaveAttribute("aria-hidden", "true");
  });

  test("título e resumo com <script> aparecem como texto", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(
        200,
        pageBody([
          item({
            title: "<script>alert(1)</script>Guia",
            summary: "<img src=x onerror=alert(1)>",
          }),
        ]),
      ),
    );
    const { container } = renderCatalog();

    expect(
      await screen.findByRole("link", {
        name: "<script>alert(1)</script>Guia",
      }),
    ).toBeVisible();
    expect(screen.getByText("<img src=x onerror=alert(1)>")).toBeVisible();
    expect(container.querySelector("script, img")).toBeNull();
  });

  test("Próxima e Anterior trocam a página na URL e levam o foco ao título", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockImplementation(async (input) =>
        String(input) === "/api/products?page=1"
          ? jsonResponse(
              200,
              pageBody([item({ id: "p2", title: "Dois" })], 1, 2),
            )
          : jsonResponse(200, pageBody([item()], 0, 2)),
      );
    renderCatalog();
    const user = userEvent.setup();

    await screen.findByRole("link", { name: "Guia" });
    expect(screen.getByRole("button", { name: "Anterior" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Próxima" }));

    expect(await screen.findByText("Página 2 de 2")).toBeInTheDocument();
    expect(await screen.findByRole("link", { name: "Dois" })).toBeVisible();
    expect(urlsOf(fetchSpy)).toContain("/api/products?page=1");
    expect(
      screen.getByRole("heading", { level: 1, name: "Catálogo" }),
    ).toHaveFocus();
  });

  test("página além do fim mostra a mensagem e o link para a primeira", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockImplementation(async (input) =>
        String(input) === "/api/products?page=0"
          ? jsonResponse(200, pageBody([item()], 0, 2))
          : jsonResponse(200, pageBody([], 4, 2)),
      );
    renderCatalog("/catalogo?pagina=5");

    expect(
      await screen.findByText(/Esta página do Catálogo não tem Produtos\./),
    ).toBeInTheDocument();
    await userEvent
      .setup()
      .click(screen.getByRole("link", { name: "Ir para a primeira página" }));

    expect(await screen.findByRole("link", { name: "Guia" })).toBeVisible();
    expect(urlsOf(fetchSpy)).toEqual([
      "/api/products?page=4",
      "/api/products?page=0",
    ]);
  });
});
