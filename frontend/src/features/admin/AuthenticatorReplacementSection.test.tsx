import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, test, vi } from "vitest";
import AuthenticatorReplacementSection from "./AuthenticatorReplacementSection";
import { adminSessionQueryKey } from "./hooks";

const START = "/api/admin/authenticator-replacement";
const CONFIRM = "/api/admin/authenticator-replacement/confirmation";
const SECRET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
const GROUPED_SECRET = "ABCD EFGH IJKL MNOP QRST UVWX YZ23 4567";
const ENROLLMENT = {
  secret: SECRET,
  otpauthUri: "otpauth://totp/Sapiensia:admin?secret=ABC",
};
const CODES = Array.from({ length: 10 }, (_, i) =>
  String.fromCharCode(65 + i).repeat(24),
);
const WRONG_OLD =
  "Código incorreto ou vencido. Aguarde o próximo código do aplicativo e tente de novo.";
const WRONG_NEW =
  "Código incorreto ou vencido. Aguarde o próximo código do aplicativo novo e tente de novo.";
const BLOCKED =
  "Muitas tentativas de entrada. Aguarde alguns minutos e tente de novo.";
const INTERRUPTED = "A troca foi interrompida. Comece de novo.";
const GENERAL = "Não foi possível trocar o autenticador agora. Tente novamente.";
const OLD_LABEL = "Código do autenticador atual";
const NEW_LABEL = "Código do aplicativo novo";
const RESULT_TITLE = "Autenticador trocado. Seus novos códigos de recuperação";

type Handlers = {
  start?: () => Response | Promise<Response>;
  confirm?: () => Response | Promise<Response>;
};

function json(status: number, body?: unknown) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
  });
}

function mockApi({ start, confirm }: Handlers = {}) {
  return vi
    .spyOn(globalThis, "fetch")
    .mockImplementation(async (input, init) => {
      if (init?.method === "POST" && String(input) === START) {
        return start ? start() : json(200, ENROLLMENT);
      }
      if (init?.method === "POST" && String(input) === CONFIRM) {
        return confirm ? confirm() : json(200, { codes: CODES });
      }
      return new Response(null, { status: 200 });
    });
}

function renderSection(oldCodeRequired = true) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <AuthenticatorReplacementSection oldCodeRequired={oldCodeRequired} />
    </QueryClientProvider>,
  );
  return client;
}

function callsTo(spy: ReturnType<typeof mockApi>, url: string) {
  return spy.mock.calls.filter(([u]) => String(u) === url);
}

type User = ReturnType<typeof userEvent.setup>;

async function begin(user: User) {
  await user.click(
    screen.getByRole("button", { name: "Trocar o autenticador" }),
  );
}

async function passOldCode(user: User) {
  await begin(user);
  await user.type(screen.getByLabelText(OLD_LABEL), "123456");
  await user.click(screen.getByRole("button", { name: "Continuar" }));
}

describe("AuthenticatorReplacementSection", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  test("shows the title, the explanation and the button", () => {
    renderSection();

    expect(
      screen.getByRole("heading", { name: "Trocar o autenticador" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "Use isto se trocou de celular ou quer um aplicativo novo. O autenticador atual continua valendo até você confirmar o novo, e as outras sessões do painel são encerradas.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Trocar o autenticador" }),
    ).toBeInTheDocument();
  });

  test("SEC-008: sessão TOTP pede o código atual antes de mostrar a chave", async () => {
    const spy = mockApi();
    renderSection(true);
    const user = userEvent.setup();

    await begin(user);

    expect(
      screen.getByText(
        "Para começar, digite o código de 6 dígitos do autenticador atual.",
      ),
    ).toBeInTheDocument();
    const field = screen.getByLabelText(OLD_LABEL);
    expect(field).toHaveFocus();
    expect(spy).not.toHaveBeenCalled();
    expect(screen.queryByText(GROUPED_SECRET)).not.toBeInTheDocument();

    await user.type(field, "123 456");
    await user.click(screen.getByRole("button", { name: "Continuar" }));

    expect(await screen.findByLabelText(NEW_LABEL)).toBeInTheDocument();
    const [, init] = callsTo(spy, START)[0];
    expect(JSON.parse(String(init?.body))).toEqual({ code: "123456" });
  });

  test("a code with fewer than 6 digits is not sent", async () => {
    const spy = mockApi();
    renderSection(true);
    const user = userEvent.setup();

    await begin(user);
    await user.type(screen.getByLabelText(OLD_LABEL), "123");
    await user.click(screen.getByRole("button", { name: "Continuar" }));

    expect(
      await screen.findByText("Digite os 6 dígitos do código."),
    ).toHaveAttribute("role", "alert");
    expect(spy).not.toHaveBeenCalled();
  });

  test("SEC-065: sessão por recuperação começa sem pedir o código atual", async () => {
    const spy = mockApi();
    renderSection(false);
    const user = userEvent.setup();

    await begin(user);

    expect(await screen.findByLabelText(NEW_LABEL)).toBeInTheDocument();
    expect(screen.queryByLabelText(OLD_LABEL)).not.toBeInTheDocument();
    expect(
      screen.getByText(
        "Você entrou com um código de recuperação, então não precisa do código do autenticador atual.",
      ),
    ).toBeInTheDocument();
    const [, init] = callsTo(spy, START)[0];
    expect(init?.body).toBeUndefined();
  });

  test("mostra a chave e o QR depois do início e pede o código novo", async () => {
    mockApi();
    renderSection();
    const user = userEvent.setup();

    await passOldCode(user);

    const field = await screen.findByLabelText(NEW_LABEL);
    await waitFor(() => expect(field).toHaveFocus());
    expect(
      screen.getByText("1. No aplicativo autenticador, leia o QR code abaixo."),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("img", {
        name: "QR code para o aplicativo autenticador",
      }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "Não consegue ler o QR code? Digite esta chave no aplicativo:",
      ),
    ).toBeInTheDocument();
    expect(screen.getByText(GROUPED_SECRET)).toBeInTheDocument();
    expect(
      screen.getByText(
        "2. Digite o código de 6 dígitos que o aplicativo novo mostra.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Confirmar a troca" }),
    ).toBeInTheDocument();
  });

  test('código atual errado mostra "Código incorreto ou vencido..." e não mostra a chave', async () => {
    mockApi({ start: () => json(400, { errors: { code: "CODE_INVALID" } }) });
    renderSection();
    const user = userEvent.setup();

    await passOldCode(user);

    expect(await screen.findByText(WRONG_OLD)).toHaveAttribute("role", "alert");
    expect(screen.queryByLabelText(NEW_LABEL)).not.toBeInTheDocument();
    expect(screen.queryByText(GROUPED_SECRET)).not.toBeInTheDocument();
    expect(screen.getByLabelText(OLD_LABEL)).toBeInTheDocument();
  });

  test("código novo errado mostra a mensagem do código novo e mantém a tela", async () => {
    mockApi({ confirm: () => json(400, { errors: { code: "CODE_INVALID" } }) });
    renderSection();
    const user = userEvent.setup();
    await passOldCode(user);

    await user.type(await screen.findByLabelText(NEW_LABEL), "654321");
    await user.click(screen.getByRole("button", { name: "Confirmar a troca" }));

    expect(await screen.findByText(WRONG_NEW)).toHaveAttribute("role", "alert");
    expect(screen.getByLabelText(NEW_LABEL)).toBeInTheDocument();
    expect(screen.getByText(GROUPED_SECRET)).toBeInTheDocument();
  });

  test("429 mostra a mensagem de bloqueio", async () => {
    mockApi({ start: () => json(429) });
    renderSection();
    const user = userEvent.setup();

    await passOldCode(user);

    expect(await screen.findByText(BLOCKED)).toHaveAttribute("role", "alert");
  });

  test('409 mostra "A troca foi interrompida. Comece de novo."', async () => {
    mockApi({ confirm: () => json(409) });
    renderSection();
    const user = userEvent.setup();
    await passOldCode(user);

    await user.type(await screen.findByLabelText(NEW_LABEL), "654321");
    await user.click(screen.getByRole("button", { name: "Confirmar a troca" }));

    expect(await screen.findByText(INTERRUPTED)).toHaveAttribute(
      "role",
      "alert",
    );
    expect(screen.queryByLabelText(NEW_LABEL)).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Trocar o autenticador" }),
    ).toBeInTheDocument();
  });

  test("an unexpected failure shows the general message", async () => {
    mockApi({ start: () => json(500) });
    renderSection();
    const user = userEvent.setup();

    await passOldCode(user);

    expect(await screen.findByText(GENERAL)).toHaveAttribute("role", "alert");
  });

  test("sucesso mostra os 10 códigos e o aviso; Concluir volta ao início e limpa o estado", async () => {
    const spy = mockApi();
    const client = renderSection();
    const user = userEvent.setup();
    await passOldCode(user);

    await user.type(await screen.findByLabelText(NEW_LABEL), "654 321");
    await user.click(screen.getByRole("button", { name: "Confirmar a troca" }));

    expect(
      await screen.findByRole("heading", { name: RESULT_TITLE }),
    ).toBeInTheDocument();
    const [, init] = callsTo(spy, CONFIRM)[0];
    expect(JSON.parse(String(init?.body))).toEqual({ code: "654321" });
    expect(
      screen.getByText(
        "O autenticador anterior e os códigos anteriores não funcionam mais. Estes códigos aparecem só agora. Guarde-os no seu gerenciador de senhas. Quem tiver um código e a sua senha entra no painel.",
      ),
    ).toBeInTheDocument();
    const list = screen.getByRole("list", { name: "Códigos de recuperação" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(10);
    expect(client.getQueryState(adminSessionQueryKey)?.isInvalidated).toBe(
      true,
    );
    // The secret is not kept in the mutation cache.
    expect(
      JSON.stringify(
        client
          .getMutationCache()
          .getAll()
          .map((m) => m.state),
      ),
    ).not.toContain(SECRET);

    await user.click(
      screen.getByLabelText("Guardei meus códigos em um lugar seguro"),
    );
    await user.click(screen.getByRole("button", { name: "Concluir" }));

    expect(
      screen.getByRole("button", { name: "Trocar o autenticador" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("list", { name: "Códigos de recuperação" }),
    ).not.toBeInTheDocument();
    expect(screen.queryByText(GROUPED_SECRET)).not.toBeInTheDocument();
  });

  test("Cancelar descarta o estado local", async () => {
    mockApi();
    renderSection();
    const user = userEvent.setup();
    await passOldCode(user);
    await screen.findByLabelText(NEW_LABEL);

    await user.click(screen.getByRole("button", { name: "Cancelar" }));

    expect(screen.queryByLabelText(NEW_LABEL)).not.toBeInTheDocument();
    expect(screen.queryByText(GROUPED_SECRET)).not.toBeInTheDocument();
    await begin(user);
    expect(screen.getByLabelText(OLD_LABEL)).toHaveValue("");
  });

  test('o botão fica desabilitado e mostra "Continuando…"/"Confirmando…" enquanto envia', async () => {
    let release: (r: Response) => void = () => {};
    mockApi({
      confirm: () => new Promise<Response>((resolve) => (release = resolve)),
    });
    renderSection();
    const user = userEvent.setup();
    await passOldCode(user);
    await user.type(await screen.findByLabelText(NEW_LABEL), "654321");
    await user.click(screen.getByRole("button", { name: "Confirmar a troca" }));

    expect(
      await screen.findByRole("button", { name: "Confirmando…" }),
    ).toBeDisabled();
    release(json(200, { codes: CODES }));
    await screen.findByRole("heading", { name: RESULT_TITLE });
  });

  test("shows Continuando… while the start is pending", async () => {
    mockApi({ start: () => new Promise<Response>(() => {}) });
    renderSection();
    const user = userEvent.setup();

    await passOldCode(user);

    expect(
      await screen.findByRole("button", { name: "Continuando…" }),
    ).toBeDisabled();
  });
});
