import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, type NavigateFunction, useNavigate } from "react-router";
import { afterEach, describe, expect, test, vi } from "vitest";
import ResetPasswordPage from "./ResetPasswordPage";

const CONFIRM_URL = "/api/customers/password-resets/confirmation";
const INVALID_LINK =
  'Este link venceu ou já foi usado. Peça um novo link em "Esqueci minha senha".';
const SUCCESS =
  "Senha alterada. Por segurança, você saiu de todos os aparelhos. Entre com a nova senha.";
const LONG_ENOUGH = "uma senha bem longa 123";

function confirmCalls(spy: { mock: { calls: Parameters<typeof fetch>[] } }) {
  return spy.mock.calls.filter(([url]) => url === CONFIRM_URL);
}

let navigate: NavigateFunction;

function CaptureNavigate() {
  navigate = useNavigate();
  return null;
}

function renderPage(hash = "#tok-123") {
  const client = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/redefinir-senha${hash}`]}>
        <CaptureNavigate />
        <ResetPasswordPage />
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
  await user.click(screen.getByRole("button", { name: "Salvar nova senha" }));
}

describe("ResetPasswordPage", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  test("mostra título, instrução e campo de senha nova", () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    renderPage();

    expect(
      screen.getByRole("heading", { level: 1, name: "Nova senha" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText("Escolha a nova senha da sua conta."),
    ).toBeInTheDocument();
    const field = screen.getByLabelText("Nova senha");
    expect(field).toHaveAttribute("type", "password");
    expect(field).toHaveAttribute("autocomplete", "new-password");
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  test("sem token no fragmento mostra o link inválido, sem formulário", () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    renderPage("");

    expect(screen.getByRole("alert")).toHaveTextContent(INVALID_LINK);
    expect(
      screen.getByRole("link", { name: "Esqueci minha senha" }),
    ).toHaveAttribute("href", "/esqueci-senha");
    expect(screen.queryByLabelText("Nova senha")).not.toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  test("senha curta é bloqueada no cliente", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    renderPage();

    await submit("curta");

    expect(
      await screen.findByText("A senha precisa ter pelo menos 12 caracteres."),
    ).toHaveAttribute("role", "alert");
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  test("senha longa é bloqueada no cliente", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    renderPage();
    const user = userEvent.setup();

    await user.click(screen.getByLabelText("Nova senha"));
    await user.paste("a".repeat(129));
    await user.click(screen.getByRole("button", { name: "Salvar nova senha" }));

    expect(
      await screen.findByText("A senha pode ter no máximo 128 caracteres."),
    ).toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  test("SEC-007: envia o token do fragmento no corpo e mostra o sucesso com link Entrar", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(204));
    renderPage();

    await submit(LONG_ENOUGH);

    const status = await screen.findByText(SUCCESS);
    expect(status).toHaveAttribute("role", "status");
    expect(screen.getByRole("link", { name: "Entrar" })).toHaveAttribute(
      "href",
      "/entrar",
    );
    expect(screen.queryByLabelText("Nova senha")).not.toBeInTheDocument();
    const [url, init] = confirmCalls(fetchSpy)[0];
    expect(url).toBe(CONFIRM_URL);
    expect(init?.method).toBe("POST");
    expect(JSON.parse(String(init?.body))).toEqual({
      token: "tok-123",
      newPassword: LONG_ENOUGH,
    });
  });

  test("SEC-007: um link mais novo aberto na mesma aba envia o token novo", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(204));
    renderPage("#OLD");

    act(() => {
      navigate("/redefinir-senha#NEW");
    });
    await submit(LONG_ENOUGH);

    await screen.findByText(SUCCESS);
    const [, init] = confirmCalls(fetchSpy)[0];
    expect(JSON.parse(String(init?.body))).toEqual({
      token: "NEW",
      newPassword: LONG_ENOUGH,
    });
  });

  test("SEC-007: TOKEN_INVALID mostra o link inválido", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(400, { errors: { token: "TOKEN_INVALID" } }),
    );
    renderPage();

    await submit(LONG_ENOUGH);

    expect(await screen.findByRole("alert")).toHaveTextContent(INVALID_LINK);
    expect(screen.queryByLabelText("Nova senha")).not.toBeInTheDocument();
  });

  test("PASSWORD_COMPROMISED aparece no campo e o formulário continua", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(400, { errors: { newPassword: "PASSWORD_COMPROMISED" } }),
    );
    renderPage();

    await submit(LONG_ENOUGH);

    expect(
      await screen.findByText(
        "Essa senha já apareceu em vazamentos de dados. Escolha outra.",
      ),
    ).toHaveAttribute("role", "alert");
    expect(screen.getByLabelText("Nova senha")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Salvar nova senha" }),
    ).toBeEnabled();
  });

  test("falha inesperada mostra o erro inesperado", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(500));
    renderPage();

    await submit(LONG_ENOUGH);

    expect(
      await screen.findByText(
        "Não foi possível salvar agora. Tente novamente em instantes.",
      ),
    ).toHaveAttribute("role", "alert");
  });

  test('desabilita o botão e mostra "Salvando…" enquanto envia', async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation((url) =>
      url === CONFIRM_URL
        ? new Promise<Response>(() => {})
        : Promise.resolve(jsonResponse(200)),
    );
    renderPage();

    await submit(LONG_ENOUGH);

    const button = await screen.findByRole("button", { name: "Salvando…" });
    await waitFor(() => expect(button).toBeDisabled());
  });
});
