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
    // apiFetch may first fetch the CSRF cookie; the registration is the last call.
    const [url, init] = fetchSpy.mock.calls[fetchSpy.mock.calls.length - 1];
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
