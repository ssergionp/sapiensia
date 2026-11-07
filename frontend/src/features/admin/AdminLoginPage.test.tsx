import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, describe, expect, test, vi } from "vitest";
import { sessionQueryKey } from "../session/hooks";
import AdminLoginPage from "./AdminLoginPage";

function renderPage(
  client = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  }),
) {
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/painel/entrar"]}>
        <Routes>
          <Route path="/painel/entrar" element={<AdminLoginPage />} />
          <Route path="/painel/verificacao" element={<p>verificacao</p>} />
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

async function fillAndSubmit(email: string, password: string) {
  const user = userEvent.setup();
  if (email) await user.type(screen.getByLabelText("E-mail"), email);
  if (password) await user.type(screen.getByLabelText("Senha"), password);
  await user.click(screen.getByRole("button", { name: "Entrar" }));
}

const FAILURE = "E-mail ou senha incorretos.";
const BLOCKED =
  "Muitas tentativas de entrada. Aguarde alguns minutos e tente de novo.";
const UNEXPECTED =
  "Não foi possível entrar agora. Tente novamente em instantes.";

describe("AdminLoginPage", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  test("mostra o título e os campos com rótulo", () => {
    renderPage();

    expect(
      screen.getByRole("heading", { name: "Entrar no painel" }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("E-mail")).toBeInTheDocument();
    expect(screen.getByLabelText("Senha")).toHaveAttribute("type", "password");
  });

  test("e-mail inválido não envia e aponta o campo", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    renderPage();

    await fillAndSubmit("isso-nao-e-email", "qualquer");

    expect(
      await screen.findByText("Informe um e-mail válido."),
    ).toHaveAttribute("role", "alert");
    expect(screen.getByLabelText("E-mail")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
    expect(screen.getByLabelText("E-mail")).toHaveAccessibleDescription(
      "Informe um e-mail válido.",
    );
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  test("senha vazia não envia e aponta o campo", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    renderPage();

    await fillAndSubmit("admin@example.com", "");

    expect(await screen.findByText("Informe a senha.")).toHaveAttribute(
      "role",
      "alert",
    );
    expect(screen.getByLabelText("Senha")).toHaveAccessibleDescription(
      "Informe a senha.",
    );
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  test("204 envia para /api/admin/sessions e vai para /painel/verificacao", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(204));
    renderPage();

    await fillAndSubmit(" admin@example.com ", "uma senha");

    expect(await screen.findByText("verificacao")).toBeInTheDocument();
    const logins = fetchSpy.mock.calls.filter(
      ([url]) => url === "/api/admin/sessions",
    );
    expect(logins).toHaveLength(1);
    const [, init] = logins[0];
    expect(init?.method).toBe("POST");
    expect(JSON.parse(String(init?.body))).toEqual({
      email: "admin@example.com",
      password: "uma senha",
    });
  });

  test("204 invalida a sessão de Cliente em cache", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(204));
    const client = new QueryClient({
      defaultOptions: { mutations: { retry: false } },
    });
    client.setQueryData(sessionQueryKey, { email: "cliente@example.com" });
    renderPage(client);

    await fillAndSubmit("admin@example.com", "senha-do-painel-123");

    expect(await screen.findByText("verificacao")).toBeInTheDocument();
    expect(client.getQueryState(sessionQueryKey)?.isInvalidated).toBe(true);
  });

  test("401 mostra a falha do painel", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(401));
    renderPage();

    await fillAndSubmit("admin@example.com", "errada");

    expect(await screen.findByText(FAILURE)).toHaveAttribute("role", "alert");
    expect(screen.queryByText(UNEXPECTED)).not.toBeInTheDocument();
  });

  test("429 mostra o bloqueio e o botão volta a ficar ativo", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(null, { status: 429, headers: { "Retry-After": "900" } }),
    );
    renderPage();

    await fillAndSubmit("admin@example.com", "qualquer");

    expect(await screen.findByText(BLOCKED)).toHaveAttribute("role", "alert");
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Entrar" })).toBeEnabled(),
    );
  });

  test("503 mostra o erro inesperado", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(503));
    renderPage();

    await fillAndSubmit("admin@example.com", "qualquer");

    expect(await screen.findByText(UNEXPECTED)).toBeInTheDocument();
  });

  test("erro de rede mostra o erro inesperado", async () => {
    vi.spyOn(globalThis, "fetch").mockRejectedValue(new TypeError("network"));
    renderPage();

    await fillAndSubmit("admin@example.com", "qualquer");

    expect(await screen.findByText(UNEXPECTED)).toBeInTheDocument();
  });

  test('desabilita o botão e mostra "Entrando…" enquanto envia', async () => {
    vi.spyOn(globalThis, "fetch").mockReturnValue(new Promise(() => {}));
    renderPage();

    await fillAndSubmit("admin@example.com", "qualquer");

    const button = await screen.findByRole("button", { name: "Entrando…" });
    await waitFor(() => expect(button).toBeDisabled());
  });
});
