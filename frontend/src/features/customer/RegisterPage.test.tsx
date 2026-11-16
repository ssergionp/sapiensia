import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, test, vi } from "vitest";
import RegisterPage from "./RegisterPage";

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <RegisterPage />
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
  await user.click(screen.getByRole("button", { name: "Criar conta" }));
}

describe("RegisterPage", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  test("SEC-002: não envia o formulário com senha curta e mostra o motivo", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    renderPage();

    await fillAndSubmit("ana@example.com", "curta");

    expect(
      await screen.findByText("A senha precisa ter pelo menos 12 caracteres."),
    ).toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  test('mostra "Informe um e-mail válido." para e-mail inválido', async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    renderPage();

    await fillAndSubmit("isso-nao-e-email", "uma senha bem longa");

    expect(
      await screen.findByText("Informe um e-mail válido."),
    ).toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  test("#42: 429 mostra o aviso de muitas tentativas e não o erro genérico", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(429));
    renderPage();

    await fillAndSubmit("ana@example.com", "uma senha bem longa");

    expect(
      await screen.findByText(
        "Muitas tentativas. Aguarde alguns minutos e tente de novo.",
      ),
    ).toHaveAttribute("role", "alert");
    expect(screen.queryByText(/Não foi possível criar a conta/)).toBeNull();
  });

  test("envia e mostra a mensagem de sucesso", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(202));
    renderPage();

    await fillAndSubmit("ana@example.com", "uma senha bem longa");

    expect(
      await screen.findByText(
        "Cadastro recebido. Verifique seu e-mail para continuar.",
      ),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText("E-mail")).not.toBeInTheDocument();
    // apiFetch may first fetch the CSRF cookie; exactly one call is the registration.
    const registrations = fetchSpy.mock.calls.filter(
      ([url]) => url === "/api/customers",
    );
    expect(registrations).toHaveLength(1);
    const [url, init] = registrations[0];
    expect(url).toBe("/api/customers");
    expect(init?.method).toBe("POST");
    expect(JSON.parse(String(init?.body))).toEqual({
      email: "ana@example.com",
      password: "uma senha bem longa",
    });
  });

  test("SEC-002: mostra a mensagem de senha vazada no campo", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(400, { errors: { password: "PASSWORD_COMPROMISED" } }),
    );
    renderPage();

    await fillAndSubmit("ana@example.com", "uma senha bem longa");

    const message = await screen.findByText(
      "Essa senha já apareceu em vazamentos de dados. Escolha outra.",
    );
    expect(message).toHaveAttribute("role", "alert");
    expect(screen.getByLabelText("Senha")).toHaveAccessibleDescription(
      expect.stringContaining("Essa senha já apareceu em vazamentos de dados."),
    );
  });

  test("mostra erro amigável quando a API falha", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(500));
    renderPage();

    await fillAndSubmit("ana@example.com", "uma senha bem longa");

    expect(
      await screen.findByText(
        "Não foi possível criar a conta agora. Tente novamente em instantes.",
      ),
    ).toBeInTheDocument();
  });

  test("mostra o erro genérico quando o 400 não traz erro de e-mail nem de senha", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(400, { errors: { other: "X" } }),
    );
    renderPage();

    await fillAndSubmit("ana@example.com", "uma senha bem longa");

    expect(
      await screen.findByText(
        "Não foi possível criar a conta agora. Tente novamente em instantes.",
      ),
    ).toBeInTheDocument();
  });

  test("não mostra o erro genérico enquanto o usuário corrige a senha", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(400, { errors: { password: "PASSWORD_COMPROMISED" } }),
    );
    renderPage();

    await fillAndSubmit("ana@example.com", "uma senha bem longa");
    await screen.findByText(
      "Essa senha já apareceu em vazamentos de dados. Escolha outra.",
    );

    await userEvent.setup().type(screen.getByLabelText("Senha"), "x");

    await waitFor(() =>
      expect(
        screen.queryByText(
          "Essa senha já apareceu em vazamentos de dados. Escolha outra.",
        ),
      ).not.toBeInTheDocument(),
    );
    expect(
      screen.queryByText(
        "Não foi possível criar a conta agora. Tente novamente em instantes.",
      ),
    ).not.toBeInTheDocument();
  });

  test("apara os espaços do e-mail antes de enviar", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(202));
    renderPage();

    await fillAndSubmit(" ana@example.com ", "uma senha bem longa");

    expect(
      await screen.findByText(
        "Cadastro recebido. Verifique seu e-mail para continuar.",
      ),
    ).toBeInTheDocument();
    const call = fetchSpy.mock.calls.find(([url]) => url === "/api/customers");
    expect(JSON.parse(String(call?.[1]?.body)).email).toBe("ana@example.com");
  });

  test('desabilita o botão e mostra "Criando conta…" enquanto envia', async () => {
    vi.spyOn(globalThis, "fetch").mockReturnValue(new Promise(() => {}));
    renderPage();

    await fillAndSubmit("ana@example.com", "uma senha bem longa");

    const button = await screen.findByRole("button", {
      name: "Criando conta…",
    });
    await waitFor(() => expect(button).toBeDisabled());
  });

  test("os campos são achados pelo rótulo", () => {
    renderPage();

    expect(screen.getByLabelText("E-mail")).toBeInTheDocument();
    expect(screen.getByLabelText("Senha")).toHaveAttribute("type", "password");
  });
});
