import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  createMemoryRouter,
  MemoryRouter,
  Route,
  RouterProvider,
  Routes,
} from "react-router";
import { afterEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "@/features/products/testing";
import LibraryPage from "./LibraryPage";
import { startDownload } from "./startDownload";

vi.mock("./startDownload", () => ({ startDownload: vi.fn() }));

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
  return {
    client,
    ...render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={["/biblioteca"]}>
          <Routes>
            <Route path="/biblioteca" element={<LibraryPage />} />
            <Route path="/entrar" element={<p>tela de entrada</p>} />
            <Route path="/catalogo" element={<p>tela do catálogo</p>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    ),
  };
}

const DOWNLOAD_PATH = `/api/library/${ID}/download`;
const OTHER_ID = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d";

// The list answers 200 with the given items; the download route answers with the queued responses.
function mockApi(downloads: Array<Response | Error>, items = [entry()]) {
  const queue = [...downloads];
  return vi.spyOn(globalThis, "fetch").mockImplementation((input) => {
    const url = String(input);
    if (url.endsWith("/download")) {
      const next = queue.shift();
      if (next instanceof Error) return Promise.reject(next);
      return Promise.resolve(next ?? jsonResponse(500));
    }
    return Promise.resolve(jsonResponse(200, { items }));
  });
}

function downloadCalls(spy: ReturnType<typeof mockApi>) {
  return spy.mock.calls.filter(([url]) => String(url).endsWith("/download"));
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.mocked(startDownload).mockClear();
  localStorage.clear();
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
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

  test("401 ao abrir a página substitui a entrada do histórico: Voltar não retorna à Biblioteca", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(401));
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    const router = createMemoryRouter(
      [
        { path: "/", element: <p>tela inicial</p> },
        { path: "/biblioteca", element: <LibraryPage /> },
        { path: "/entrar", element: <p>tela de entrada</p> },
      ],
      { initialEntries: ["/", "/biblioteca"], initialIndex: 1 },
    );
    render(
      <QueryClientProvider client={client}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    expect(await screen.findByText("tela de entrada")).toBeInTheDocument();
    await act(() => router.navigate(-1));

    expect(await screen.findByText("tela inicial")).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/");
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

  describe("FR-034: botão Baixar", () => {
    test("cada item tem um botão Baixar com o título no nome acessível", async () => {
      mockApi(
        [],
        [entry(), entry({ productId: OTHER_ID, title: "Outro guia" })],
      );
      renderLibrary();

      const first = await screen.findByRole("button", {
        name: "Baixar Guia de saúde",
      });
      expect(first).toHaveTextContent("Baixar");
      expect(first).toBeEnabled();
      expect(
        screen.getByRole("button", { name: "Baixar Outro guia" }),
      ).toBeInTheDocument();
    });

    test("clique faz POST com CSRF, bloqueia o botão e inicia o download uma vez", async () => {
      document.cookie = "XSRF-TOKEN=tok123";
      let release: (r: Response) => void = () => {};
      const spy = mockApi([]);
      spy.mockImplementation((input) => {
        if (String(input).endsWith("/download")) {
          return new Promise<Response>((resolve) => {
            release = resolve;
          });
        }
        return Promise.resolve(jsonResponse(200, { items: [entry()] }));
      });
      renderLibrary();

      const button = await screen.findByRole("button", {
        name: "Baixar Guia de saúde",
      });
      await userEvent.click(button);

      expect(button).toHaveAttribute("aria-disabled", "true");
      expect(button).toHaveTextContent("Preparando…");
      await userEvent.click(button);
      const calls = downloadCalls(spy);
      expect(calls).toHaveLength(1);
      const [url, init] = calls[0] as [string, RequestInit];
      expect(url).toBe(DOWNLOAD_PATH);
      expect(init.method).toBe("POST");
      expect(new Headers(init.headers).get("X-XSRF-TOKEN")).toBe("tok123");

      release(jsonResponse(200, { url: "/api/local-downloads/abc" }));
      expect(
        await screen.findByText(
          "Download iniciado. Se não começar, clique em Baixar de novo.",
        ),
      ).toHaveAttribute("role", "status");
      expect(startDownload).toHaveBeenCalledTimes(1);
      expect(startDownload).toHaveBeenCalledWith("/api/local-downloads/abc");
      expect(button).toBeEnabled();
      expect(button).toHaveTextContent("Baixar");
      expect(document.body.innerHTML).not.toContain("local-downloads/abc");
    });

    test("a URL do link não fica no cache de mutações depois do clique", async () => {
      mockApi([jsonResponse(200, { url: "/api/local-downloads/segredo" })]);
      const { client } = renderLibrary();

      await userEvent.click(
        await screen.findByRole("button", { name: "Baixar Guia de saúde" }),
      );
      await vi.waitFor(() => expect(startDownload).toHaveBeenCalledTimes(1));
      await screen.findByText(/Download iniciado/);

      // The mounted button may still hold its finished mutation, but never with the link in it.
      const states = client
        .getMutationCache()
        .getAll()
        .map((mutation) => mutation.state);
      expect(states.every((state) => state.data === undefined)).toBe(true);
      expect(JSON.stringify(states)).not.toContain("segredo");
    });

    test("segundo clique depois de terminar faz outro POST com a URL nova", async () => {
      const spy = mockApi([
        jsonResponse(200, { url: "/api/local-downloads/um" }),
        jsonResponse(200, { url: "/api/local-downloads/dois" }),
      ]);
      renderLibrary();

      const button = await screen.findByRole("button", {
        name: "Baixar Guia de saúde",
      });
      await userEvent.click(button);
      await vi.waitFor(() => expect(startDownload).toHaveBeenCalledTimes(1));
      await userEvent.click(button);
      await vi.waitFor(() => expect(startDownload).toHaveBeenCalledTimes(2));

      expect(downloadCalls(spy)).toHaveLength(2);
      expect(startDownload).toHaveBeenNthCalledWith(
        1,
        "/api/local-downloads/um",
      );
      expect(startDownload).toHaveBeenNthCalledWith(
        2,
        "/api/local-downloads/dois",
      );
    });

    test.each([
      ["503", jsonResponse(503)],
      ["falha de rede", new TypeError("network down")],
    ])("%s mostra o erro e libera o botão", async (_name, failure) => {
      mockApi([failure]);
      renderLibrary();

      const button = await screen.findByRole("button", {
        name: "Baixar Guia de saúde",
      });
      await userEvent.click(button);

      expect(await screen.findByRole("alert")).toHaveTextContent(
        "Não foi possível preparar o download. Tente novamente.",
      );
      expect(button).toBeEnabled();
      expect(startDownload).not.toHaveBeenCalled();
    });

    test("teclado: o foco continua no botão depois do erro e Espaço pede de novo", async () => {
      const spy = mockApi([jsonResponse(503), jsonResponse(503)]);
      renderLibrary();

      const button = await screen.findByRole("button", {
        name: "Baixar Guia de saúde",
      });
      button.focus();
      await userEvent.keyboard("{Enter}");
      await screen.findByRole("alert");

      expect(button).toHaveFocus();
      await userEvent.keyboard(" ");
      await vi.waitFor(() => expect(downloadCalls(spy)).toHaveLength(2));
    });

    test("404 mostra indisponível e busca a lista de novo", async () => {
      const spy = mockApi([jsonResponse(404)]);
      renderLibrary();

      await userEvent.click(
        await screen.findByRole("button", { name: "Baixar Guia de saúde" }),
      );

      expect(await screen.findByRole("alert")).toHaveTextContent(
        "Este Produto não está mais disponível na sua Biblioteca.",
      );
      await vi.waitFor(() => {
        const listCalls = spy.mock.calls.filter(
          ([url]) => String(url) === "/api/library",
        );
        expect(listCalls).toHaveLength(2);
      });
      expect(startDownload).not.toHaveBeenCalled();
    });

    test("SEC-015: o 429 mostra a mensagem do limite e o botão continua ativo", async () => {
      const spy = mockApi([
        jsonResponse(429, { errors: { download: "DOWNLOAD_LIMIT" } }),
      ]);
      renderLibrary();

      const button = await screen.findByRole("button", {
        name: "Baixar Guia de saúde",
      });
      button.focus();
      await userEvent.keyboard("{Enter}");

      expect(await screen.findByRole("alert")).toHaveTextContent(
        "Você já baixou este Produto 10 vezes hoje. Tente novamente amanhã.",
      );
      expect(button).toHaveAttribute("aria-disabled", "false");
      expect(button).toHaveFocus();
      expect(startDownload).not.toHaveBeenCalled();
      const listCalls = spy.mock.calls.filter(
        ([url]) => String(url) === "/api/library",
      );
      expect(listCalls).toHaveLength(1);
    });

    test("401 leva a /entrar com retorno para /biblioteca, sem mensagem de erro", async () => {
      mockApi([jsonResponse(401)]);
      renderLibrary();

      await userEvent.click(
        await screen.findByRole("button", { name: "Baixar Guia de saúde" }),
      );

      expect(await screen.findByText("tela de entrada")).toBeInTheDocument();
      const stored = JSON.parse(
        localStorage.getItem("sapiensia.returnTo") ?? "null",
      );
      expect(stored.path).toBe("/biblioteca");
      expect(screen.queryByRole("alert")).toBeNull();
      expect(startDownload).not.toHaveBeenCalled();
    });

    test("o estado de um item não aparece em outro", async () => {
      mockApi(
        [jsonResponse(503)],
        [entry(), entry({ productId: OTHER_ID, title: "Outro guia" })],
      );
      renderLibrary();

      await userEvent.click(
        await screen.findByRole("button", { name: "Baixar Guia de saúde" }),
      );

      expect(await screen.findAllByRole("alert")).toHaveLength(1);
      const other = screen.getByRole("button", { name: "Baixar Outro guia" });
      expect(other).toBeEnabled();
      expect(other).toHaveTextContent("Baixar");
    });
  });
});
