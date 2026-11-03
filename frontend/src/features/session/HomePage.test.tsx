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
});
