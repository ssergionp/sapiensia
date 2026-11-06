import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, test, vi } from "vitest";
import SetAdminPasswordPage from "./SetAdminPasswordPage";

const SETUP_URL = "/api/admin/password-setup";
const INVALID_LINK =
  "Este link venceu ou já foi usado. Se você ainda não definiu a senha, um novo link chega por e-mail quando o sistema for reiniciado.";
const SUCCESS = "Senha definida. Entre no painel com a nova senha.";
const PASSWORD = "uma senha bem longa 123";

function renderPage(hash = "#tok-123") {
  const client = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/painel/definir-senha${hash}`]}>
        <SetAdminPasswordPage />
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

async function submit(password: string) {
  const user = userEvent.setup();
  if (password) await user.type(screen.getByLabelText("Nova senha"), password);
  await user.click(screen.getByRole("button", { name: "Salvar senha" }));
}

function setupCalls(spy: { mock: { calls: Parameters<typeof fetch>[] } }) {
  return spy.mock.calls.filter(([url]) =>
    String(url).includes("password-setup"),
  );
}

describe("SetAdminPasswordPage", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  test("mostra título, instrução e campo de senha", () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    renderPage();

    expect(
      screen.getByRole("heading", {
        level: 1,
        name: "Definir senha do painel",
      }),
    ).toBeInTheDocument();
    expect(
      screen.getByText("Escolha a senha da sua conta de Administrador."),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Nova senha")).toHaveAttribute(
      "autocomplete",
      "new-password",
    );
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  test("sem token no fragmento mostra o link inválido, sem formulário", () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    renderPage("");

    expect(screen.getByRole("alert")).toHaveTextContent(INVALID_LINK);
    expect(screen.queryByLabelText("Nova senha")).not.toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  test("senha vazia mostra o erro de senha curta", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    renderPage();

    await submit("");

    expect(
      await screen.findByText("A senha precisa ter pelo menos 12 caracteres."),
    ).toHaveAttribute("role", "alert");
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  test("senha curta é bloqueada no cliente", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    renderPage();

    await submit("curta");

    expect(
      await screen.findByText("A senha precisa ter pelo menos 12 caracteres."),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Nova senha")).toHaveAccessibleDescription(
      "A senha precisa ter pelo menos 12 caracteres.",
    );
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  test("senha longa é bloqueada no cliente", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    renderPage();
    const user = userEvent.setup();

    await user.click(screen.getByLabelText("Nova senha"));
    await user.paste("a".repeat(129));
    await user.click(screen.getByRole("button", { name: "Salvar senha" }));

    expect(
      await screen.findByText("A senha pode ter no máximo 128 caracteres."),
    ).toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  test("envia o token só no corpo do POST e mostra o sucesso com o link", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(204));
    renderPage();

    await submit(PASSWORD);

    const status = await screen.findByText(SUCCESS);
    expect(status).toHaveAttribute("role", "status");
    expect(
      screen.getByRole("link", { name: "Entrar no painel" }),
    ).toHaveAttribute("href", "/painel/entrar");
    expect(screen.queryByLabelText("Nova senha")).not.toBeInTheDocument();
    const calls = setupCalls(fetchSpy);
    expect(calls).toHaveLength(1);
    const [url, init] = calls[0];
    expect(url).toBe(SETUP_URL);
    expect(String(url)).not.toContain("tok-123");
    expect(init?.method).toBe("POST");
    expect(JSON.parse(String(init?.body))).toEqual({
      token: "tok-123",
      newPassword: PASSWORD,
    });
    for (const [callUrl] of fetchSpy.mock.calls) {
      expect(String(callUrl)).not.toContain("tok-123");
    }
  });

  test("TOKEN_INVALID mostra o link inválido", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(400, { errors: { token: "TOKEN_INVALID" } }),
    );
    renderPage();

    await submit(PASSWORD);

    expect(await screen.findByRole("alert")).toHaveTextContent(INVALID_LINK);
    expect(screen.queryByLabelText("Nova senha")).not.toBeInTheDocument();
  });

  test.each([
    ["PASSWORD_TOO_SHORT", "A senha precisa ter pelo menos 12 caracteres."],
    ["PASSWORD_REQUIRED", "A senha precisa ter pelo menos 12 caracteres."],
    ["PASSWORD_TOO_LONG", "A senha pode ter no máximo 128 caracteres."],
    [
      "PASSWORD_COMPROMISED",
      "Essa senha já apareceu em vazamentos de dados. Escolha outra.",
    ],
  ])("%s aparece no campo e o formulário continua", async (code, text) => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(400, { errors: { newPassword: code } }),
    );
    renderPage();

    await submit(PASSWORD);

    expect(await screen.findByText(text)).toHaveAttribute("role", "alert");
    expect(screen.getByLabelText("Nova senha")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Salvar senha" })).toBeEnabled();
  });

  test("falha inesperada mostra o erro inesperado", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(500));
    renderPage();

    await submit(PASSWORD);

    expect(
      await screen.findByText(
        "Não foi possível salvar agora. Tente novamente em instantes.",
      ),
    ).toHaveAttribute("role", "alert");
  });

  test('desabilita o botão e mostra "Salvando…" enquanto envia', async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation((url) =>
      url === SETUP_URL
        ? new Promise<Response>(() => {})
        : Promise.resolve(jsonResponse(200)),
    );
    renderPage();

    await submit(PASSWORD);

    const button = await screen.findByRole("button", { name: "Salvando…" });
    await waitFor(() => expect(button).toBeDisabled());
  });
});
