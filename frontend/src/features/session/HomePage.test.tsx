import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, test, vi } from "vitest";
import HomePage from "./HomePage";

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <HomePage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function jsonResponse(status: number, body?: unknown) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
  });
}

const LOGOUT_ERROR = "Não foi possível sair agora. Tente novamente.";
const SESSION_URL = "/api/sessions/current";
const SESSIONS_URL = "/api/sessions";
const LOGOUT_ALL_SUCCESS = "Você saiu de todos os aparelhos.";
const LOGOUT_ALL_ERROR =
  "Não foi possível sair de todos os aparelhos agora. Tente novamente.";

describe("HomePage", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  test('mostra "Carregando…" enquanto verifica a sessão', () => {
    vi.spyOn(globalThis, "fetch").mockReturnValue(new Promise(() => {}));
    renderPage();

    expect(screen.getByText("Carregando…")).toBeInTheDocument();
  });

  test("sem sessão (401) mostra os links Entrar e Criar conta", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(401));
    renderPage();

    expect(await screen.findByRole("link", { name: "Entrar" })).toHaveAttribute(
      "href",
      "/entrar",
    );
    expect(screen.getByRole("link", { name: "Criar conta" })).toHaveAttribute(
      "href",
      "/cadastro",
    );
    expect(
      screen.queryByRole("button", { name: "Sair" }),
    ).not.toBeInTheDocument();
  });

  test("mostra o link Ver o Catálogo sem sessão e com sessão", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(401));
    const first = renderPage();
    const anonymous = await screen.findByRole("link", {
      name: "Ver o Catálogo",
    });
    expect(anonymous).toHaveAttribute("href", "/catalogo");
    first.unmount();

    fetchSpy.mockResolvedValue(jsonResponse(200, { email: "ana@example.com" }));
    renderPage();
    expect(
      await screen.findByRole("link", { name: "Ver o Catálogo" }),
    ).toHaveAttribute("href", "/catalogo");
    expect(screen.getByRole("button", { name: "Sair" })).toBeInTheDocument();
  });

  test("com sessão mostra o e-mail e o botão Sair", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(200, { email: "ana@example.com" }),
    );
    renderPage();

    expect(
      await screen.findByText("Você entrou como ana@example.com."),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sair" })).toBeInTheDocument();
    expect(
      screen.queryByRole("link", { name: "Entrar" }),
    ).not.toBeInTheDocument();
  });

  test("Sair chama DELETE e volta ao estado sem sessão", async () => {
    let loggedIn = true;
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockImplementation(async (input, init) => {
        if (String(input) === SESSION_URL && init?.method === "DELETE") {
          loggedIn = false;
          return jsonResponse(204);
        }
        if (String(input) === SESSION_URL) {
          return loggedIn
            ? jsonResponse(200, { email: "ana@example.com" })
            : jsonResponse(401);
        }
        return jsonResponse(200);
      });
    renderPage();

    await userEvent
      .setup()
      .click(await screen.findByRole("button", { name: "Sair" }));

    expect(await screen.findByRole("link", { name: "Entrar" })).toBeVisible();
    expect(
      fetchSpy.mock.calls.filter(
        ([url, init]) => url === SESSION_URL && init?.method === "DELETE",
      ),
    ).toHaveLength(1);
  });

  test("falha ao sair mostra a mensagem e mantém a sessão", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      if (String(input) === SESSION_URL && init?.method === "DELETE")
        return jsonResponse(500);
      if (String(input) === SESSION_URL)
        return jsonResponse(200, { email: "ana@example.com" });
      return jsonResponse(200);
    });
    renderPage();

    await userEvent
      .setup()
      .click(await screen.findByRole("button", { name: "Sair" }));

    const message = await screen.findByText(LOGOUT_ERROR);
    expect(message).toHaveAttribute("role", "alert");
    expect(
      screen.getByText("Você entrou como ana@example.com."),
    ).toBeInTheDocument();
  });

  test("com sessão mostra também o botão Sair de todos os aparelhos", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(200, { email: "ana@example.com" }),
    );
    renderPage();

    expect(
      await screen.findByRole("button", { name: "Sair de todos os aparelhos" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sair" })).toBeInTheDocument();
  });

  test("Sair de todos os aparelhos chama DELETE /api/sessions e avisa", async () => {
    let loggedIn = true;
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockImplementation(async (input, init) => {
        if (String(input) === SESSIONS_URL && init?.method === "DELETE") {
          loggedIn = false;
          return jsonResponse(204);
        }
        if (String(input) === SESSION_URL) {
          return loggedIn
            ? jsonResponse(200, { email: "ana@example.com" })
            : jsonResponse(401);
        }
        return jsonResponse(200);
      });
    renderPage();

    await userEvent.setup().click(
      await screen.findByRole("button", {
        name: "Sair de todos os aparelhos",
      }),
    );

    expect(await screen.findByRole("link", { name: "Entrar" })).toBeVisible();
    expect(screen.getByRole("link", { name: "Criar conta" })).toBeVisible();
    const status = await screen.findByText(LOGOUT_ALL_SUCCESS);
    expect(status).toHaveAttribute("role", "status");
    expect(
      fetchSpy.mock.calls.filter(
        ([url, init]) => url === SESSIONS_URL && init?.method === "DELETE",
      ),
    ).toHaveLength(1);
  });

  test("falha ao sair de todos os aparelhos mostra a mensagem e mantém a sessão", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      if (String(input) === SESSIONS_URL && init?.method === "DELETE")
        return jsonResponse(500);
      if (String(input) === SESSION_URL)
        return jsonResponse(200, { email: "ana@example.com" });
      return jsonResponse(200);
    });
    renderPage();

    await userEvent.setup().click(
      await screen.findByRole("button", {
        name: "Sair de todos os aparelhos",
      }),
    );

    const message = await screen.findByText(LOGOUT_ALL_ERROR);
    expect(message).toHaveAttribute("role", "alert");
    expect(
      screen.getByText("Você entrou como ana@example.com."),
    ).toBeInTheDocument();
  });

  test('mostra "Saindo de todos os aparelhos…" enquanto sai', async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation((input, init) => {
      if (String(input) === SESSIONS_URL && init?.method === "DELETE")
        return new Promise(() => {});
      if (String(input) === SESSION_URL)
        return Promise.resolve(jsonResponse(200, { email: "ana@example.com" }));
      return Promise.resolve(jsonResponse(200));
    });
    renderPage();

    await userEvent.setup().click(
      await screen.findByRole("button", {
        name: "Sair de todos os aparelhos",
      }),
    );

    expect(
      await screen.findByRole("button", {
        name: "Saindo de todos os aparelhos…",
      }),
    ).toBeDisabled();
  });

  test('mostra "Saindo…" enquanto sai', async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation((input, init) => {
      if (String(input) === SESSION_URL && init?.method === "DELETE")
        return new Promise(() => {});
      if (String(input) === SESSION_URL)
        return Promise.resolve(jsonResponse(200, { email: "ana@example.com" }));
      return Promise.resolve(jsonResponse(200));
    });
    renderPage();

    await userEvent
      .setup()
      .click(await screen.findByRole("button", { name: "Sair" }));

    expect(
      await screen.findByRole("button", { name: "Saindo…" }),
    ).toBeDisabled();
  });

  test("the tab title is SapiensIA while loading, signed out and signed in", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockReturnValue(new Promise(() => {}));
    document.title = "outro";
    const loading = renderPage();
    expect(document.title).toBe("SapiensIA");
    loading.unmount();

    fetchSpy.mockResolvedValue(jsonResponse(401));
    document.title = "outro";
    const signedOut = renderPage();
    await screen.findByRole("link", { name: "Entrar" });
    expect(document.title).toBe("SapiensIA");
    signedOut.unmount();

    fetchSpy.mockResolvedValue(jsonResponse(200, { email: "ana@example.com" }));
    document.title = "outro";
    renderPage();
    await screen.findByRole("button", { name: "Sair" });
    expect(document.title).toBe("SapiensIA");
  });
});
