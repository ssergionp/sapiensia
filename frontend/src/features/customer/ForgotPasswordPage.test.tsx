import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, test, vi } from "vitest";
import ForgotPasswordPage from "./ForgotPasswordPage";

const URL_RESETS = "/api/customers/password-resets";
const SUCCESS =
  "Pedido recebido. Se o e-mail estiver cadastrado, você receberá um link em instantes.";

function calls(spy: { mock: { calls: Parameters<typeof fetch>[] } }) {
  return spy.mock.calls.filter(([url]) => url === URL_RESETS);
}

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ForgotPasswordPage />
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

async function submit(email: string) {
  const user = userEvent.setup();
  if (email) await user.type(screen.getByLabelText("E-mail"), email);
  await user.click(screen.getByRole("button", { name: "Enviar link" }));
}

describe("ForgotPasswordPage", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  test("mostra título, instrução e campo com rótulo", () => {
    renderPage();

    expect(
      screen.getByRole("heading", { level: 1, name: "Recuperar senha" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "Informe o e-mail da sua conta. Se ele estiver cadastrado, enviaremos um link para você criar uma nova senha.",
      ),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("E-mail")).toHaveAttribute(
      "autocomplete",
      "email",
    );
  });

  test("e-mail inválido mostra o erro e não chama a API", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    renderPage();

    await submit("isso-nao-e-email");

    const message = await screen.findByText("Informe um e-mail válido.");
    expect(message).toHaveAttribute("role", "alert");
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  test("sucesso mostra a mensagem única e envia o e-mail aparado", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(202));
    renderPage();

    await submit(" ana@example.com ");

    const status = await screen.findByText(SUCCESS);
    expect(status).toHaveAttribute("role", "status");
    const [, init] = calls(fetchSpy)[0];
    expect(init?.method).toBe("POST");
    expect(JSON.parse(String(init?.body))).toEqual({
      email: "ana@example.com",
    });
  });

  test("falha inesperada mostra o erro inesperado", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(500));
    renderPage();

    await submit("ana@example.com");

    const message = await screen.findByText(
      "Não foi possível enviar agora. Tente novamente em instantes.",
    );
    expect(message).toHaveAttribute("role", "alert");
  });

  test("#42: 429 mostra o aviso de muitas tentativas e não o erro genérico", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(429));
    renderPage();

    await submit("ana@example.com");

    expect(
      await screen.findByText(
        "Muitas tentativas. Aguarde alguns minutos e tente de novo.",
      ),
    ).toHaveAttribute("role", "alert");
    expect(screen.queryByText(/Não foi possível enviar agora/)).toBeNull();
  });

  test('desabilita o botão e mostra "Enviando…" enquanto envia', async () => {
    vi.spyOn(globalThis, "fetch").mockReturnValue(new Promise(() => {}));
    renderPage();

    await submit("ana@example.com");

    const button = await screen.findByRole("button", { name: "Enviando…" });
    await waitFor(() => expect(button).toBeDisabled());
  });
});
