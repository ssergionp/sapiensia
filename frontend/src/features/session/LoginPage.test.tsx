import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, describe, expect, test, vi } from "vitest";
import LoginPage from "./LoginPage";

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/entrar"]}>
        <Routes>
          <Route path="/entrar" element={<LoginPage />} />
          <Route path="/" element={<p>pagina inicial</p>} />
          <Route path="/cadastro" element={<p>pagina de cadastro</p>} />
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

const FAILURE =
  "E-mail ou senha incorretos, ou o e-mail ainda não foi confirmado.";
const BLOCKED =
  "Muitas tentativas de entrada. Aguarde alguns minutos e tente de novo.";
const UNEXPECTED =
  "Não foi possível entrar agora. Tente novamente em instantes.";

describe("LoginPage", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  test("mostra o título e os campos com rótulo", () => {
    renderPage();

    expect(screen.getByRole("heading", { name: "Entrar" })).toBeInTheDocument();
    expect(screen.getByLabelText("E-mail")).toHaveAttribute(
      "autocomplete",
      "email",
    );
    expect(screen.getByLabelText("Senha")).toHaveAttribute("type", "password");
    expect(screen.getByLabelText("Senha")).toHaveAttribute(
      "autocomplete",
      "current-password",
    );
  });

  test("não envia com e-mail inválido e mostra o motivo no campo", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    renderPage();

    await fillAndSubmit("isso-nao-e-email", "qualquer");

    const message = await screen.findByText("Informe um e-mail válido.");
    expect(message).toHaveAttribute("role", "alert");
    expect(screen.getByLabelText("E-mail")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
    expect(screen.getByLabelText("E-mail")).toHaveAccessibleDescription(
      "Informe um e-mail válido.",
    );
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  test("não envia com senha vazia e mostra o motivo no campo", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    renderPage();

    await fillAndSubmit("ana@example.com", "");

    const message = await screen.findByText("Informe a senha.");
    expect(message).toHaveAttribute("role", "alert");
    expect(screen.getByLabelText("Senha")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
    expect(screen.getByLabelText("Senha")).toHaveAccessibleDescription(
      "Informe a senha.",
    );
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  test("aceita senha curta (sem regra de força no login) e vai para a página inicial", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(204));
    renderPage();

    await fillAndSubmit(" ana@example.com ", "curta");

    expect(await screen.findByText("pagina inicial")).toBeInTheDocument();
    const logins = fetchSpy.mock.calls.filter(
      ([url]) => url === "/api/sessions",
    );
    expect(logins).toHaveLength(1);
    const [, init] = logins[0];
    expect(init?.method).toBe("POST");
    expect(JSON.parse(String(init?.body))).toEqual({
      email: "ana@example.com",
      password: "curta",
    });
  });

  test("401 mostra a mensagem de falha de login", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(401));
    renderPage();

    await fillAndSubmit("ana@example.com", "senha errada");

    const message = await screen.findByText(FAILURE);
    expect(message).toHaveAttribute("role", "alert");
    expect(screen.queryByText(UNEXPECTED)).not.toBeInTheDocument();
  });

  test("SEC-002: 429 mostra a mensagem de bloqueio e o botão volta a ficar ativo", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(null, { status: 429, headers: { "Retry-After": "900" } }),
    );
    renderPage();

    await fillAndSubmit("ana@example.com", "senha qualquer");

    const message = await screen.findByText(BLOCKED);
    expect(message).toHaveAttribute("role", "alert");
    expect(screen.queryByText(FAILURE)).not.toBeInTheDocument();
    expect(screen.queryByText(UNEXPECTED)).not.toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Entrar" })).toBeEnabled(),
    );
  });

  test("erro de rede mostra a mensagem inesperada", async () => {
    vi.spyOn(globalThis, "fetch").mockRejectedValue(new TypeError("network"));
    renderPage();

    await fillAndSubmit("ana@example.com", "senha qualquer");

    expect(await screen.findByText(UNEXPECTED)).toBeInTheDocument();
    expect(screen.queryByText(FAILURE)).not.toBeInTheDocument();
  });

  test("resposta que não é 204 nem 401 mostra a mensagem inesperada", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(500));
    renderPage();

    await fillAndSubmit("ana@example.com", "senha qualquer");

    expect(await screen.findByText(UNEXPECTED)).toBeInTheDocument();
  });

  test('desabilita o botão e mostra "Entrando…" enquanto envia', async () => {
    vi.spyOn(globalThis, "fetch").mockReturnValue(new Promise(() => {}));
    renderPage();

    await fillAndSubmit("ana@example.com", "senha qualquer");

    const button = await screen.findByRole("button", { name: "Entrando…" });
    await waitFor(() => expect(button).toBeDisabled());
  });

  test("o link Esqueci minha senha aponta para /esqueci-senha", () => {
    renderPage();

    expect(
      screen.getByRole("link", { name: "Esqueci minha senha" }),
    ).toHaveAttribute("href", "/esqueci-senha");
  });

  test("o link para criar conta vai para /cadastro", async () => {
    renderPage();

    await userEvent
      .setup()
      .click(
        screen.getByRole("link", { name: "Ainda não tem conta? Criar conta" }),
      );

    expect(await screen.findByText("pagina de cadastro")).toBeInTheDocument();
  });
});
