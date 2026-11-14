import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "@/features/products/testing";
import LibraryPage from "./LibraryPage";

const ID = "3f2b8c1e-4d5a-4b6c-9d7e-1a2b3c4d5e6f";

function entry(overrides: Record<string, unknown> = {}) {
  return {
    productId: ID,
    title: "Guia de saúde",
    grantedAt: "2026-11-15T01:30:00Z",
    coverVersion: "v 1",
    ...overrides,
  };
}

function renderLibrary() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/biblioteca"]}>
        <Routes>
          <Route path="/biblioteca" element={<LibraryPage />} />
          <Route path="/entrar" element={<p>tela de entrada</p>} />
          <Route path="/catalogo" element={<p>tela do catálogo</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
});

describe("LibraryPage", () => {
  test('FR-029: lista título, capa e "Comprado em"', async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(200, { items: [entry()] }),
    );
    const { container } = renderLibrary();

    expect(await screen.findByText("Guia de saúde")).toBeInTheDocument();
    const heading = screen.getByRole("heading", { name: "Biblioteca" });
    // 2026-11-15T01:30Z is still the 14th in São Paulo.
    expect(screen.getByText("Comprado em 14/11/2026")).toBeInTheDocument();
    expect(container.querySelector("time")).toHaveAttribute(
      "datetime",
      "2026-11-15T01:30:00Z",
    );
    const img = container.querySelector("img");
    expect(img).toHaveAttribute("src", `/api/library/${ID}/cover?v=v%201`);
    expect(img).toHaveAttribute("alt", "");
    expect(screen.getByRole("list")).toHaveAccessibleName("Biblioteca");
    expect(document.activeElement).toBe(heading);
  });

  test("item sem capa mostra o quadro neutro e nenhuma imagem", async () => {
    const spy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(
        jsonResponse(200, { items: [entry({ coverVersion: null })] }),
      );
    const { container } = renderLibrary();

    await screen.findByText("Guia de saúde");
    expect(container.querySelector("img")).toBeNull();
    expect(spy.mock.calls.map(([url]) => String(url))).toEqual([
      "/api/library",
    ]);
  });

  test("lista vazia mostra o texto e o link para o Catálogo", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(200, { items: [] }),
    );
    renderLibrary();

    expect(
      await screen.findByText("Você ainda não tem Produtos na Biblioteca."),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "Ver o Catálogo" }),
    ).toHaveAttribute("href", "/catalogo");
  });

  test("mostra Carregando… enquanto busca", () => {
    vi.spyOn(globalThis, "fetch").mockReturnValue(new Promise(() => {}));
    renderLibrary();

    expect(screen.getByText("Carregando…")).toBeInTheDocument();
  });

  test("erro mostra a mensagem e Tentar novamente busca outra vez", async () => {
    const spy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse(403))
      .mockResolvedValueOnce(jsonResponse(200, { items: [entry()] }));
    renderLibrary();

    expect(
      await screen.findByText(
        "Não foi possível carregar a Biblioteca. Tente novamente.",
      ),
    ).toBeInTheDocument();
    await userEvent.click(
      screen.getByRole("button", { name: "Tentar novamente" }),
    );

    expect(await screen.findByText("Guia de saúde")).toBeInTheDocument();
    expect(spy).toHaveBeenCalledTimes(2);
  });

  test("401 leva a /entrar e guarda /biblioteca para depois do login", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(401));
    renderLibrary();

    expect(await screen.findByText("tela de entrada")).toBeInTheDocument();
    const stored = JSON.parse(
      localStorage.getItem("sapiensia.returnTo") ?? "null",
    );
    expect(stored.path).toBe("/biblioteca");
  });

  test("o título da aba é Biblioteca | SapiensIA", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(200, { items: [] }),
    );
    renderLibrary();

    await screen.findByRole("heading", { name: "Biblioteca" });
    expect(document.title).toBe("Biblioteca | SapiensIA");
  });
});
