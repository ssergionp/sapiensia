import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, describe, expect, test, vi } from "vitest";
import AdminHomePage from "./AdminHomePage";

const ADMIN_URL = "/api/admin/sessions/current";
const LOGOUT_URL = "/api/sessions/current";
const LOGOUT_ERROR = "Não foi possível sair agora. Tente novamente.";
const LONG_EMAIL = `${"a".repeat(64)}@${"b".repeat(63)}.${"c".repeat(63)}.${"d".repeat(55)}.com`;

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/painel"]}>
        <Routes>
          <Route path="/painel" element={<AdminHomePage />} />
          <Route path="/painel/entrar" element={<p>tela de entrada</p>} />
          <Route path="/" element={<p>pagina inicial</p>} />
        </Routes>
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

const ADMIN_BODY = {
  email: "admin@example.com",
  capabilities: ["PANEL_ACCESS"],
};

describe("AdminHomePage", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  test('mostra "Carregando…" enquanto verifica a sessão', () => {
    vi.spyOn(globalThis, "fetch").mockReturnValue(new Promise(() => {}));
    renderPage();

    expect(screen.getByText("Carregando…")).toBeInTheDocument();
  });

  test("com sessão mostra título, e-mail e o botão Sair", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(200, ADMIN_BODY),
    );
    renderPage();

    expect(
      await screen.findByRole("heading", { name: "Painel do Administrador" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText("admin@example.com", { exact: false }),
    ).toBeInTheDocument();
    expect(screen.getByRole("main")).toHaveTextContent(
      "Você entrou como admin@example.com.",
    );
    expect(screen.getByRole("button", { name: "Sair" })).toBeInTheDocument();
  });

  test("o e-mail quebra desde o início ([overflow-wrap:anywhere])", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(200, { email: LONG_EMAIL, capabilities: [] }),
    );
    renderPage();

    const email = await screen.findByText(LONG_EMAIL);
    expect(email).toHaveClass("[overflow-wrap:anywhere]");
  });

  test("sem sessão (401) redireciona para /painel/entrar", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(401));
    renderPage();

    expect(await screen.findByText("tela de entrada")).toBeInTheDocument();
  });

  test("Cliente logado (403) vê a mensagem e o link para o início", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(403));
    renderPage();

    expect(
      await screen.findByText("Esta área é só para o Administrador."),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "Voltar ao início" }),
    ).toHaveAttribute("href", "/");
    expect(
      screen.queryByRole("button", { name: "Sair" }),
    ).not.toBeInTheDocument();
  });

  test("erro ao carregar mostra a mensagem", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(500));
    renderPage();

    expect(
      await screen.findByText(
        "Não foi possível carregar o painel agora. Tente novamente.",
      ),
    ).toHaveAttribute("role", "alert");
  });

  test("Sair chama DELETE e leva a /painel/entrar", async () => {
    let loggedIn = true;
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockImplementation(async (input, init) => {
        if (String(input) === LOGOUT_URL && init?.method === "DELETE") {
          loggedIn = false;
          return jsonResponse(204);
        }
        if (String(input) === ADMIN_URL) {
          return loggedIn ? jsonResponse(200, ADMIN_BODY) : jsonResponse(401);
        }
        return jsonResponse(200);
      });
    renderPage();

    await userEvent
      .setup()
      .click(await screen.findByRole("button", { name: "Sair" }));

    expect(await screen.findByText("tela de entrada")).toBeInTheDocument();
    expect(
      fetchSpy.mock.calls.filter(
        ([url, init]) => url === LOGOUT_URL && init?.method === "DELETE",
      ),
    ).toHaveLength(1);
  });

  test("falha ao sair mostra a mensagem e mantém a sessão", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      if (String(input) === LOGOUT_URL && init?.method === "DELETE")
        return jsonResponse(500);
      if (String(input) === ADMIN_URL) return jsonResponse(200, ADMIN_BODY);
      return jsonResponse(200);
    });
    renderPage();

    await userEvent
      .setup()
      .click(await screen.findByRole("button", { name: "Sair" }));

    expect(await screen.findByText(LOGOUT_ERROR)).toHaveAttribute(
      "role",
      "alert",
    );
    expect(screen.getByRole("main")).toHaveTextContent(
      "Você entrou como admin@example.com.",
    );
  });

  test('desabilita o botão e mostra "Saindo…" enquanto sai', async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation((input, init) => {
      if (String(input) === LOGOUT_URL && init?.method === "DELETE")
        return new Promise(() => {});
      return Promise.resolve(jsonResponse(200, ADMIN_BODY));
    });
    renderPage();

    await userEvent
      .setup()
      .click(await screen.findByRole("button", { name: "Sair" }));

    expect(
      await screen.findByRole("button", { name: "Saindo…" }),
    ).toBeDisabled();
  });
});
