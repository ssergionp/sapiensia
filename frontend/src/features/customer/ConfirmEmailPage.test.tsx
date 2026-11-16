import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, test, vi } from "vitest";
import ConfirmEmailPage from "./ConfirmEmailPage";

const INVALID_LINK =
  "Este link venceu ou já foi usado. Se o seu e-mail ainda não foi confirmado, faça o cadastro de novo para receber outro link.";
const CONFIRM_URL = "/api/customers/email-confirmations";
// apiFetch may first prime the CSRF cookie with a GET, so only confirmation calls are counted.
function confirmCalls(spy: { mock: { calls: unknown[][] } }) {
  return spy.mock.calls.filter(([url]) => url === CONFIRM_URL);
}
const SUCCESS = "E-mail confirmado. Agora você já pode entrar na sua conta.";

function renderPage(hash = "#abc") {
  window.location.hash = hash;
  const client = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ConfirmEmailPage />
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
  if (password) await user.type(screen.getByLabelText("Senha"), password);
  await user.click(screen.getByRole("button", { name: "Confirmar e-mail" }));
}

describe("ConfirmEmailPage", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    window.location.hash = "";
  });

  test("não chama a API ao abrir e mostra o formulário", () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    renderPage();

    expect(fetchSpy).not.toHaveBeenCalled();
    expect(
      screen.getByRole("heading", { level: 1, name: "Confirmação de e-mail" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "Digite a senha que você escolheu no cadastro para confirmar seu e-mail.",
      ),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Senha")).toHaveAttribute("type", "password");
    expect(
      screen.getByRole("button", { name: "Confirmar e-mail" }),
    ).toBeInTheDocument();
  });

  test("não envia com a senha em branco", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    renderPage();

    await submit("");

    expect(await screen.findByText("Informe a senha.")).toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  test("confirma e mostra o sucesso", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(204));
    renderPage();

    await submit("minha senha 123");

    expect(await screen.findByText(SUCCESS)).toBeInTheDocument();
    expect(screen.queryByLabelText("Senha")).not.toBeInTheDocument();
    const [url, init] = fetchSpy.mock.calls.find(
      ([u]) => u === "/api/customers/email-confirmations",
    ) ?? ["", undefined];
    expect(url).toBe("/api/customers/email-confirmations");
    expect(init?.method).toBe("POST");
    expect(JSON.parse(init?.body as string)).toEqual({
      token: "abc",
      password: "minha senha 123",
    });
  });

  test("#49: mostra senha incorreta e deixa tentar de novo", async () => {
    const answers = [
      jsonResponse(400, { errors: { password: "PASSWORD_INCORRECT" } }),
      jsonResponse(204),
    ];
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockImplementation(async (url) =>
        url === CONFIRM_URL ? (answers.shift() as Response) : jsonResponse(200),
      );
    renderPage();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText("Senha"), "errada");
    await user.click(screen.getByRole("button", { name: "Confirmar e-mail" }));

    expect(
      await screen.findByText(
        "Senha incorreta. Use a senha do seu cadastro mais recente.",
      ),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Senha")).toBeInTheDocument();

    await user.clear(screen.getByLabelText("Senha"));
    await user.type(screen.getByLabelText("Senha"), "certa");
    await user.click(screen.getByRole("button", { name: "Confirmar e-mail" }));

    expect(await screen.findByText(SUCCESS)).toBeInTheDocument();
    expect(confirmCalls(fetchSpy)).toHaveLength(2);
  });

  test("SEC-007: mostra link inválido quando a API recusa o token", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(400, { errors: { token: "TOKEN_INVALID" } }),
    );
    renderPage();

    await submit("qualquer");

    expect(await screen.findByText(INVALID_LINK)).toBeInTheDocument();
    expect(screen.queryByLabelText("Senha")).not.toBeInTheDocument();
  });

  test("#42: 429 mostra o aviso de muitas tentativas e não o erro genérico", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(429));
    renderPage();

    await submit("qualquer");

    expect(
      await screen.findByText(
        "Muitas tentativas. Aguarde alguns minutos e tente de novo.",
      ),
    ).toHaveAttribute("role", "alert");
    expect(screen.queryByText(/Não foi possível confirmar agora/)).toBeNull();
    expect(screen.getByLabelText("Senha")).toBeInTheDocument();
  });

  test("#49: depois de 5 senhas erradas explica o cancelamento e leva ao cadastro", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(400, { errors: { token: "TOKEN_LOCKED" } }),
    );
    renderPage();

    await submit("qualquer");

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(
      "Você errou a senha 5 vezes e, por segurança, este link foi cancelado. Faça o cadastro de novo para receber outro link.",
    );
    expect(
      screen.getByRole("link", { name: "Faça o cadastro de novo" }),
    ).toHaveAttribute("href", "/cadastro");
    expect(screen.queryByText(INVALID_LINK)).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Senha")).not.toBeInTheDocument();
  });

  test("mostra link inválido sem formulário e sem chamar a API quando não há token na URL", () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    renderPage("");

    expect(screen.getByText(INVALID_LINK)).toBeInTheDocument();
    expect(screen.queryByLabelText("Senha")).not.toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  test("mostra erro amigável quando a API falha", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(500));
    renderPage();

    await submit("qualquer");

    expect(
      await screen.findByText(
        "Não foi possível confirmar agora. Tente novamente em instantes.",
      ),
    ).toBeInTheDocument();
  });

  test('desabilita o botão e mostra "Confirmando…" enquanto envia', async () => {
    let resolve: (r: Response) => void = () => {};
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockImplementation((url) =>
      url === CONFIRM_URL
        ? new Promise<Response>((r) => {
            resolve = r;
          })
        : Promise.resolve(jsonResponse(200)),
    );
    renderPage();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText("Senha"), "abc");
    await user.click(screen.getByRole("button", { name: "Confirmar e-mail" }));

    const pending = await screen.findByRole("button", { name: "Confirmando…" });
    expect(pending).toBeDisabled();
    await user.click(pending);
    expect(confirmCalls(fetchSpy)).toHaveLength(1);

    resolve(jsonResponse(204));
    await waitFor(() => expect(screen.getByText(SUCCESS)).toBeInTheDocument());
  });
});
