import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "@/features/products/testing";
import CatalogProductPage from "./CatalogProductPage";
import type { CatalogProduct } from "./schemas";

function product(overrides: Partial<CatalogProduct> = {}): CatalogProduct {
  return {
    id: "p1",
    title: "Guia",
    description: "Descrição",
    category: "LAW",
    priceCents: 500,
    ...overrides,
  };
}

function renderProduct(path = "/catalogo/p1") {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/catalogo/:id" element={<CatalogProductPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe("CatalogProductPage", () => {
  test('mostra "Carregando…" enquanto carrega', () => {
    vi.spyOn(globalThis, "fetch").mockReturnValue(new Promise(() => {}));
    renderProduct();

    expect(screen.getByText("Carregando…")).toBeInTheDocument();
  });

  test("FR-016 FR-018 mostra título, categoria, preço e a descrição inteira", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(200, product({ description: "Linha 1\nLinha 2" })),
    );
    const { container } = renderProduct();

    expect(
      await screen.findByRole("heading", { level: 1, name: "Guia" }),
    ).toBeInTheDocument();
    const terms = container.querySelectorAll("dt");
    const values = container.querySelectorAll("dd");
    expect(terms[0]).toHaveTextContent("Categoria");
    expect(values[0]).toHaveTextContent("Direito");
    expect(terms[1]).toHaveTextContent("Preço");
    expect(values[1]).toHaveTextContent("R$ 5,00");
    const paragraph = screen.getByText(
      (_, el) => el?.tagName === "P" && el.textContent === "Linha 1\nLinha 2",
    );
    expect(paragraph).toHaveClass("whitespace-pre-line");
    expect(document.title).toBe("Guia | SapiensIA");
    expect(
      screen.getByRole("link", { name: "Voltar ao Catálogo" }),
    ).toHaveAttribute("href", "/catalogo");
  });

  test("SEC-010 404 mostra Produto não encontrado sem nenhum dado", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(404, {}));
    const { container } = renderProduct();

    expect(
      await screen.findByRole("heading", {
        level: 1,
        name: "Produto não encontrado",
      }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "Este Produto não está disponível. Ele pode ter saído do Catálogo ou o endereço pode estar errado.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "Voltar ao Catálogo" }),
    ).toHaveAttribute("href", "/catalogo");
    expect(document.title).toBe("Produto não encontrado | SapiensIA");
    expect(container.textContent).not.toContain("R$");
  });

  test("ID malformado (400) mostra o mesmo Produto não encontrado", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(400, {}));
    renderProduct("/catalogo/abc");

    expect(
      await screen.findByRole("heading", {
        level: 1,
        name: "Produto não encontrado",
      }),
    ).toBeInTheDocument();
  });

  test("descrição com <script> aparece como texto", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(
        200,
        product({
          description: "<script>alert(1)</script>\n<b>negrito</b>",
        }),
      ),
    );
    const { container } = renderProduct();

    const paragraph = await screen.findByText(/<script>alert\(1\)<\/script>/);
    expect(paragraph.textContent).toBe(
      "<script>alert(1)</script>\n<b>negrito</b>",
    );
    expect(container.querySelector("script, b")).toBeNull();
  });

  test("erro 500 mostra a mensagem e Tentar novamente busca de novo", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse(500, {}))
      .mockResolvedValue(jsonResponse(200, product()));
    renderProduct();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Não foi possível carregar este Produto agora. Tente novamente.",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Tentar novamente" }),
    );

    expect(
      await screen.findByRole("heading", { level: 1, name: "Guia" }),
    ).toBeInTheDocument();
    expect(fetchSpy).toHaveBeenCalledTimes(2);
  });
});

describe("CatalogProductPage tab title", () => {
  test("is Carregando… | SapiensIA while loading", () => {
    vi.spyOn(globalThis, "fetch").mockReturnValue(new Promise(() => {}));
    document.title = "outro";
    renderProduct();

    expect(document.title).toBe("Carregando… | SapiensIA");
  });

  test("is the Product title once loaded", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(200, product()),
    );
    renderProduct();
    await screen.findByRole("heading", { level: 1, name: "Guia" });

    expect(document.title).toBe("Guia | SapiensIA");
  });

  test.each([404, 400])("is Produto não encontrado on %i", async (status) => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(status, {}));
    document.title = "outro";
    renderProduct();
    await screen.findByRole("heading", { name: "Produto não encontrado" });

    expect(document.title).toBe("Produto não encontrado | SapiensIA");
  });

  test("is Erro ao carregar o Produto on a 500", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(500, {}));
    document.title = "outro";
    renderProduct();
    await screen.findByRole("alert");

    expect(document.title).toBe("Erro ao carregar o Produto | SapiensIA");
  });

  test("goes back to SapiensIA when navigating to a page without its own title", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(200, product()),
    );
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={["/catalogo/p1"]}>
          <Routes>
            <Route path="/catalogo/:id" element={<CatalogProductPage />} />
            <Route path="/catalogo" element={<p>lista</p>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await userEvent
      .setup()
      .click(await screen.findByRole("link", { name: "Voltar ao Catálogo" }));
    await screen.findByText("lista");

    expect(document.title).toBe("SapiensIA");
  });
});
