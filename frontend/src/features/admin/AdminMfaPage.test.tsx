import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { StrictMode } from "react";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, describe, expect, test, vi } from "vitest";
import { sessionQueryKey } from "../session/hooks";
import AdminMfaPage from "./AdminMfaPage";
import { fetchAdminSession } from "./api";
import { adminSessionQueryKey } from "./hooks";

const STATUS = "GET /api/admin/sessions/mfa";
const ENROLL = "POST /api/admin/sessions/mfa/enrollment";
const CONFIRM = "POST /api/admin/sessions/mfa/enrollment/confirmation";
const VERIFY = "POST /api/admin/sessions/mfa";
const RECOVERY = "POST /api/admin/sessions/mfa/recovery-code";

const CODES = Array.from({ length: 10 }, (_, i) =>
  String.fromCharCode(65 + i).repeat(24),
);
const CONFIRMED = () => json(200, { codes: CODES });
const CODES_WARNING =
  "Estes códigos aparecem só agora. Guarde-os no seu gerenciador de senhas. Quem tiver um código e a sua senha entra no painel.";
const CODES_TITLE = "Guarde seus códigos de recuperação";
const SAVED_BOX = "Guardei meus códigos em um lugar seguro";
const CONTINUE = "Continuar para o painel";
const RECOVERY_TITLE = "Entrar com código de recuperação";
const RECOVERY_LABEL = "Código de recuperação";
const RECOVERY_WRONG =
  "Código de recuperação incorreto ou já usado. Confira e tente de novo.";
const RECOVERY_INVALID =
  "Digite o código de recuperação completo, com 24 letras e números.";

// nosemgrep: generic.secrets.security.detected-generic-secret.detected-generic-secret -- fake TOTP test key, not a secret
const SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";
const SECRET_GROUPED = "JBSW Y3DP EHPK 3PXP JBSW Y3DP EHPK 3PXP";
const OTPAUTH = `otpauth://totp/SapiensIA:admin%40example.com?secret=${SECRET}&issuer=SapiensIA`;

const LOAD_ERROR =
  "Não foi possível carregar esta página agora. Tente novamente.";
const EXPIRED =
  "O tempo para digitar o código acabou. Entre de novo com a sua senha.";
const INVALID_CODE = "Digite os 6 dígitos do código.";
const WRONG_CODE =
  "Código incorreto ou vencido. Confira o aplicativo e tente de novo.";
const BLOCKED =
  "Muitas tentativas de entrada. Aguarde alguns minutos e tente de novo.";
const UNEXPECTED =
  "Não foi possível entrar agora. Tente novamente em instantes.";
const INTERRUPTED =
  "A ativação foi interrompida. Leia o novo QR code e digite o código de novo.";
const QR_NAME = "QR code para o aplicativo autenticador";

type ApiRoutes = Record<string, () => Response | Promise<Response>>;

function json(status: number, body?: unknown) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
  });
}

function mockApi(routes: ApiRoutes) {
  return vi
    .spyOn(globalThis, "fetch")
    .mockImplementation(async (input, init) => {
      const key = `${init?.method ?? "GET"} ${String(input)}`;
      const handler = routes[key];
      if (handler) return handler();
      return new Response(null, { status: 200 });
    });
}

function callsTo(spy: ReturnType<typeof mockApi>, key: string) {
  return spy.mock.calls.filter(
    ([url, init]) => `${init?.method ?? "GET"} ${String(url)}` === key,
  );
}

function renderPage(options: { strict?: boolean; client?: QueryClient } = {}) {
  const client =
    options.client ??
    new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });
  const page = (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/painel/verificacao"]}>
        <Routes>
          <Route path="/painel/verificacao" element={<AdminMfaPage />} />
          <Route path="/painel" element={<p>painel</p>} />
          <Route path="/painel/entrar" element={<p>entrar</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
  return {
    client,
    ...render(options.strict ? <StrictMode>{page}</StrictMode> : page),
  };
}

const enrollRoutes: ApiRoutes = {
  [STATUS]: () => json(200, { enrolled: false }),
  [ENROLL]: () => json(200, { secret: SECRET, otpauthUri: OTPAUTH }),
};
const verifyRoutes: ApiRoutes = {
  [STATUS]: () => json(200, { enrolled: true }),
};

async function typeCode(code: string, button: string) {
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText("Código"), code);
  await user.click(screen.getByRole("button", { name: button }));
  return user;
}

describe("AdminMfaPage", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  test("shows the enrollment screen with the QR and the grouped manual key", async () => {
    mockApi(enrollRoutes);
    renderPage();

    expect(screen.getByText("Carregando…")).toBeInTheDocument();
    expect(
      await screen.findByRole("heading", {
        name: "Ative a verificação em duas etapas",
      }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "Para proteger o painel, a entrada pede a senha e um código do aplicativo autenticador do seu celular.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText("1. No aplicativo autenticador, leia o QR code abaixo."),
    ).toBeInTheDocument();
    expect(screen.getByRole("img", { name: QR_NAME })).toBeInTheDocument();
    expect(
      screen.getByText(
        "Não consegue ler o QR code? Digite esta chave no aplicativo:",
      ),
    ).toBeInTheDocument();
    expect(screen.getByText(SECRET_GROUPED)).toBeVisible();
    expect(
      screen.getByText(
        "2. Digite o código de 6 dígitos que o aplicativo mostra.",
      ),
    ).toBeInTheDocument();
    const field = screen.getByLabelText("Código");
    expect(field).toHaveAttribute("inputmode", "numeric");
    expect(field).toHaveAttribute("autocomplete", "one-time-code");
    expect(
      screen.getByRole("button", { name: "Ativar e entrar" }),
    ).toBeInTheDocument();
  });

  test("starts the enrollment only once, even under StrictMode", async () => {
    const spy = mockApi(enrollRoutes);
    renderPage({ strict: true });

    await screen.findByRole("img", { name: QR_NAME });
    await new Promise((resolve) => setTimeout(resolve, 20));

    expect(callsTo(spy, ENROLL)).toHaveLength(1);
    expect(callsTo(spy, STATUS)).toHaveLength(1);
  });

  test("409 when starting the enrollment shows the verification without a message", async () => {
    mockApi({ ...enrollRoutes, [ENROLL]: () => json(409) });
    renderPage();

    expect(
      await screen.findByRole("heading", {
        name: "Verificação em duas etapas",
      }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("img", { name: QR_NAME }),
    ).not.toBeInTheDocument();
  });

  test("shows the verification screen when the MFA is already active", async () => {
    const spy = mockApi(verifyRoutes);
    renderPage();

    expect(
      await screen.findByRole("heading", {
        name: "Verificação em duas etapas",
      }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "Digite o código de 6 dígitos do aplicativo autenticador.",
      ),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Código")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Entrar" })).toBeInTheDocument();
    expect(callsTo(spy, ENROLL)).toHaveLength(0);
  });

  test("a code with fewer than 6 digits is not sent", async () => {
    const spy = mockApi(verifyRoutes);
    renderPage();

    await typeCode("12345", "Entrar");

    expect(await screen.findByText(INVALID_CODE)).toHaveAttribute(
      "role",
      "alert",
    );
    expect(callsTo(spy, VERIFY)).toHaveLength(0);
  });

  test("a code pasted with a space is sent without it and goes to /painel", async () => {
    const spy = mockApi({ ...verifyRoutes, [VERIFY]: () => json(204) });
    renderPage();

    await typeCode("123 456", "Entrar");

    expect(await screen.findByText("painel")).toBeInTheDocument();
    const calls = callsTo(spy, VERIFY);
    expect(calls).toHaveLength(1);
    expect(JSON.parse(String(calls[0][1]?.body))).toEqual({ code: "123456" });
  });

  test("confirming the enrollment shows the recovery codes and only then goes to /painel", async () => {
    const spy = mockApi({ ...enrollRoutes, [CONFIRM]: CONFIRMED });
    renderPage();

    const user = await typeCode("123456", "Ativar e entrar");

    expect(
      await screen.findByRole("heading", { name: CODES_TITLE }),
    ).toBeInTheDocument();
    expect(screen.queryByText("painel")).not.toBeInTheDocument();
    expect(screen.getByText(CODES_WARNING)).toBeInTheDocument();
    const list = screen.getByRole("list", { name: "Códigos de recuperação" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(10);
    for (const code of CODES)
      expect(
        within(list).getByText(code.match(/.{4}/g)?.join("-") ?? code),
      ).toBeVisible();
    const button = screen.getByRole("button", { name: CONTINUE });
    expect(button).toBeDisabled();
    await user.click(screen.getByLabelText(SAVED_BOX));
    expect(button).toBeEnabled();
    await user.click(button);

    expect(await screen.findByText("painel")).toBeInTheDocument();
    const calls = callsTo(spy, CONFIRM);
    expect(calls).toHaveLength(1);
    expect(JSON.parse(String(calls[0][1]?.body))).toEqual({ code: "123456" });
  });

  describe.each([
    {
      path: "confirmation",
      routes: enrollRoutes,
      key: CONFIRM,
      button: "Ativar e entrar",
    },
    {
      path: "verification",
      routes: verifyRoutes,
      key: VERIFY,
      button: "Entrar",
    },
  ])(
    "session cache after the $path succeeds",
    ({ path, routes, key, button }) => {
      test("refreshes the Administrator session and invalidates the Customer session before going to /painel", async () => {
        const spy = mockApi({
          ...routes,
          [key]: path === "confirmation" ? CONFIRMED : () => json(204),
          "GET /api/admin/sessions/current": () =>
            json(200, {
              email: "admin@example.com",
              capabilities: [],
              oldCodeRequired: true,
            }),
        });
        const client = new QueryClient({
          defaultOptions: {
            queries: { retry: false },
            mutations: { retry: false },
          },
        });
        client.setQueryData(sessionQueryKey, { email: "cliente@example.com" });
        // The cached "no Administrator session" entry has no observer here, so give it the fetcher the app registers.
        client.setQueryDefaults(adminSessionQueryKey, {
          queryFn: fetchAdminSession,
        });
        client.setQueryData(adminSessionQueryKey, null);
        renderPage({ client });

        const user = await typeCode("123456", button);
        if (path === "confirmation") {
          await user.click(await screen.findByLabelText(SAVED_BOX));
          await user.click(screen.getByRole("button", { name: CONTINUE }));
        }

        expect(await screen.findByText("painel")).toBeInTheDocument();
        expect(callsTo(spy, "GET /api/admin/sessions/current")).toHaveLength(1);
        expect(client.getQueryData(adminSessionQueryKey)).toEqual({
          email: "admin@example.com",
          capabilities: [],
          oldCodeRequired: true,
        });
        expect(client.getQueryState(sessionQueryKey)?.isInvalidated).toBe(true);
      });
    },
  );

  test('disables the button and shows "Entrando…" while sending', async () => {
    mockApi({
      ...verifyRoutes,
      [VERIFY]: () => new Promise<Response>(() => {}),
    });
    renderPage();

    await typeCode("123456", "Entrar");

    const button = await screen.findByRole("button", { name: "Entrando…" });
    await waitFor(() => expect(button).toBeDisabled());
  });

  test('disables the button and shows "Ativando…" while confirming', async () => {
    mockApi({
      ...enrollRoutes,
      [CONFIRM]: () => new Promise<Response>(() => {}),
    });
    renderPage();

    await typeCode("123456", "Ativar e entrar");

    const button = await screen.findByRole("button", { name: "Ativando…" });
    await waitFor(() => expect(button).toBeDisabled());
  });

  test("two quick clicks send a single request", async () => {
    const spy = mockApi({
      ...verifyRoutes,
      [VERIFY]: () => new Promise<Response>(() => {}),
    });
    renderPage();
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText("Código"), "123456");

    await user.dblClick(screen.getByRole("button", { name: "Entrar" }));
    await new Promise((resolve) => setTimeout(resolve, 20));

    expect(callsTo(spy, VERIFY)).toHaveLength(1);
  });

  test("400 shows the wrong code message", async () => {
    mockApi({ ...verifyRoutes, [VERIFY]: () => json(400) });
    renderPage();

    await typeCode("123456", "Entrar");

    expect(await screen.findByText(WRONG_CODE)).toHaveAttribute(
      "role",
      "alert",
    );
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Entrar" })).toBeEnabled(),
    );
  });

  test("429 shows the blocked message", async () => {
    mockApi({ ...verifyRoutes, [VERIFY]: () => json(429) });
    renderPage();

    await typeCode("123456", "Entrar");

    expect(await screen.findByText(BLOCKED)).toHaveAttribute("role", "alert");
  });

  test("500 shows the unexpected error", async () => {
    mockApi({ ...verifyRoutes, [VERIFY]: () => json(500) });
    renderPage();

    await typeCode("123456", "Entrar");

    expect(await screen.findByText(UNEXPECTED)).toBeInTheDocument();
  });

  test("401 when sending shows the timeout text and the link back", async () => {
    mockApi({ ...verifyRoutes, [VERIFY]: () => json(401) });
    renderPage();

    await typeCode("123456", "Entrar");

    expect(await screen.findByText(EXPIRED)).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "Voltar para a entrada" }),
    ).toHaveAttribute("href", "/painel/entrar");
  });

  test("401 when opening shows the timeout text and the link back", async () => {
    mockApi({ [STATUS]: () => json(401) });
    renderPage();

    expect(await screen.findByText(EXPIRED)).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "Voltar para a entrada" }),
    ).toHaveAttribute("href", "/painel/entrar");
  });

  test("a network failure when opening shows the load error", async () => {
    vi.spyOn(globalThis, "fetch").mockRejectedValue(new TypeError("network"));
    renderPage();

    expect(await screen.findByText(LOAD_ERROR)).toHaveAttribute(
      "role",
      "alert",
    );
  });

  test("an unexpected status when opening shows the load error", async () => {
    mockApi({ [STATUS]: () => json(500) });
    renderPage();

    expect(await screen.findByText(LOAD_ERROR)).toHaveAttribute(
      "role",
      "alert",
    );
  });

  test("403 when opening goes to /painel", async () => {
    mockApi({ [STATUS]: () => json(403) });
    renderPage();

    expect(await screen.findByText("painel")).toBeInTheDocument();
  });

  test("409 when confirming replaces the key and the QR, clears the code and lets the user confirm the new enrollment", async () => {
    // nosemgrep: generic.secrets.security.detected-generic-secret.detected-generic-secret -- fake TOTP test key, not a secret
    const newSecret = "MFRGGZDFMZTWQ2LKMFRGGZDFMZTWQ2LK";
    const newSecretGrouped = "MFRG GZDF MZTW Q2LK MFRG GZDF MZTW Q2LK";
    const newOtpauth = `otpauth://totp/SapiensIA:admin%40example.com?secret=${newSecret}&issuer=SapiensIA`;
    let enrollCalls = 0;
    let confirmCalls = 0;
    const spy = mockApi({
      ...enrollRoutes,
      [ENROLL]: () => {
        enrollCalls += 1;
        return enrollCalls === 1
          ? json(200, { secret: SECRET, otpauthUri: OTPAUTH })
          : json(200, { secret: newSecret, otpauthUri: newOtpauth });
      },
      [CONFIRM]: () => {
        confirmCalls += 1;
        return confirmCalls === 1 ? json(409) : CONFIRMED();
      },
    });
    renderPage();
    expect(await screen.findByText(SECRET_GROUPED)).toBeVisible();

    const user = await typeCode("123456", "Ativar e entrar");

    expect(await screen.findByText(INTERRUPTED)).toBeInTheDocument();
    expect(await screen.findByText(newSecretGrouped)).toBeVisible();
    expect(screen.queryByText(SECRET_GROUPED)).not.toBeInTheDocument();
    expect(screen.getByRole("img", { name: QR_NAME })).toBeInTheDocument();
    expect(callsTo(spy, ENROLL)).toHaveLength(2);
    expect(screen.getByLabelText("Código")).toHaveValue("");

    await user.type(screen.getByLabelText("Código"), "654321");
    await user.click(screen.getByRole("button", { name: "Ativar e entrar" }));
    await user.click(await screen.findByLabelText(SAVED_BOX));
    await user.click(screen.getByRole("button", { name: CONTINUE }));

    expect(await screen.findByText("painel")).toBeInTheDocument();
    const confirmations = callsTo(spy, CONFIRM);
    expect(confirmations).toHaveLength(2);
    expect(JSON.parse(String(confirmations[1][1]?.body))).toEqual({
      code: "654321",
    });
  });

  test("409 when confirming and then 409 on the new enrollment goes to the verification", async () => {
    let enrollCalls = 0;
    mockApi({
      ...enrollRoutes,
      [ENROLL]: () => {
        enrollCalls += 1;
        return enrollCalls === 1
          ? json(200, { secret: SECRET, otpauthUri: OTPAUTH })
          : json(409);
      },
      [CONFIRM]: () => json(409),
    });
    renderPage();

    await typeCode("123456", "Ativar e entrar");

    expect(
      await screen.findByRole("heading", {
        name: "Verificação em duas etapas",
      }),
    ).toBeInTheDocument();
    expect(screen.queryByText(INTERRUPTED)).not.toBeInTheDocument();
  });

  test("409 when verifying goes to the enrollment without a message", async () => {
    const spy = mockApi({
      ...verifyRoutes,
      [VERIFY]: () => json(409),
      [ENROLL]: () => json(200, { secret: SECRET, otpauthUri: OTPAUTH }),
    });
    renderPage();

    await typeCode("123456", "Entrar");

    expect(
      await screen.findByRole("heading", {
        name: "Ative a verificação em duas etapas",
      }),
    ).toBeInTheDocument();
    expect(
      await screen.findByRole("img", { name: QR_NAME }),
    ).toBeInTheDocument();
    expect(callsTo(spy, ENROLL)).toHaveLength(1);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByText(INTERRUPTED)).not.toBeInTheDocument();
  });

  test("never writes the secret or the code to the console or to attributes", async () => {
    const consoleSpies = (
      ["log", "info", "warn", "error", "debug"] as const
    ).map((method) => vi.spyOn(console, method).mockImplementation(() => {}));
    mockApi({ ...enrollRoutes, [CONFIRM]: () => json(400) });
    const { container } = renderPage();

    await typeCode("654321", "Ativar e entrar");
    await screen.findByText(WRONG_CODE);

    for (const spy of consoleSpies) expect(spy).not.toHaveBeenCalled();
    const sensitive = [SECRET, OTPAUTH, "654321"];
    for (const element of container.querySelectorAll("*")) {
      if (element.closest("svg")) continue;
      for (const attribute of Array.from(element.attributes)) {
        if (attribute.name === "value") continue;
        for (const value of sensitive) {
          expect(attribute.value.includes(value)).toBe(false);
        }
      }
    }
  });
  test("the codes are kept only in component state, not in the caches or the storages", async () => {
    mockApi({ ...enrollRoutes, [CONFIRM]: CONFIRMED });
    const { client } = renderPage();

    await typeCode("123456", "Ativar e entrar");
    await screen.findByRole("heading", { name: CODES_TITLE });
    await new Promise((resolve) => setTimeout(resolve, 20));

    const dump = JSON.stringify([
      client
        .getQueryCache()
        .getAll()
        .map((q) => q.state.data),
      client
        .getMutationCache()
        .getAll()
        .map((m) => m.state.data),
      { ...localStorage },
      { ...sessionStorage },
    ]);
    expect(dump).not.toContain(CODES[0]);
    expect(window.location.href).not.toContain(CODES[0]);
  });

  test("the codes screen survives the refresh of the Administrator session", async () => {
    mockApi({
      ...enrollRoutes,
      [CONFIRM]: CONFIRMED,
      "GET /api/admin/sessions/current": () =>
        json(200, {
          email: "admin@example.com",
          capabilities: [],
          oldCodeRequired: true,
        }),
    });
    renderPage();

    await typeCode("123456", "Ativar e entrar");

    expect(
      await screen.findByRole("heading", { name: CODES_TITLE }),
    ).toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(screen.getByRole("heading", { name: CODES_TITLE })).toBeVisible();
  });

  test("copies the codes and tells the user", async () => {
    mockApi({ ...enrollRoutes, [CONFIRM]: CONFIRMED });
    const user = userEvent.setup();
    const writeText = vi.spyOn(navigator.clipboard, "writeText");
    renderPage();
    await user.type(await screen.findByLabelText("Código"), "123456");
    await user.click(screen.getByRole("button", { name: "Ativar e entrar" }));

    await user.click(
      await screen.findByRole("button", { name: "Copiar códigos" }),
    );

    expect(writeText).toHaveBeenCalledWith(CODES.join("\n"));
    expect(
      await screen.findByText(
        "Códigos copiados. Cole-os no seu gerenciador de senhas agora.",
      ),
    ).toBeInTheDocument();
  });

  test("tells the user when copying fails", async () => {
    mockApi({ ...enrollRoutes, [CONFIRM]: CONFIRMED });
    const user = userEvent.setup();
    vi.spyOn(navigator.clipboard, "writeText").mockRejectedValue(
      new Error("denied"),
    );
    renderPage();
    await user.type(await screen.findByLabelText("Código"), "123456");
    await user.click(screen.getByRole("button", { name: "Ativar e entrar" }));

    await user.click(
      await screen.findByRole("button", { name: "Copiar códigos" }),
    );

    expect(
      await screen.findByText(
        "Não foi possível copiar. Selecione os códigos e copie manualmente.",
      ),
    ).toHaveAttribute("role", "alert");
  });

  describe("recovery code login", () => {
    async function openRecovery() {
      const user = userEvent.setup();
      await user.click(
        await screen.findByRole("button", {
          name: "Perdeu o celular? Use um código de recuperação",
        }),
      );
      return user;
    }

    test("switches to the recovery form and back", async () => {
      mockApi(verifyRoutes);
      renderPage();

      const user = await openRecovery();

      expect(
        screen.getByRole("heading", { name: RECOVERY_TITLE }),
      ).toBeInTheDocument();
      expect(
        screen.getByText(
          "Digite um dos seus códigos de recuperação. Cada código só funciona uma vez.",
        ),
      ).toBeInTheDocument();
      expect(screen.getByLabelText(RECOVERY_LABEL)).toBeInTheDocument();
      await user.click(
        screen.getByRole("button", { name: "Usar o código do aplicativo" }),
      );
      expect(
        screen.getByRole("heading", { name: "Verificação em duas etapas" }),
      ).toBeInTheDocument();
    });

    test("offers the recovery option only on the verification screen", async () => {
      mockApi(enrollRoutes);
      renderPage();

      await screen.findByRole("img", { name: QR_NAME });

      expect(
        screen.queryByRole("button", {
          name: "Perdeu o celular? Use um código de recuperação",
        }),
      ).not.toBeInTheDocument();
    });

    test("normalizes spaces, hyphens and lower case before sending, then goes to /painel", async () => {
      const spy = mockApi({ ...verifyRoutes, [RECOVERY]: () => json(204) });
      renderPage();
      const user = await openRecovery();

      await user.type(
        screen.getByLabelText(RECOVERY_LABEL),
        "abcd-efgh 2jkl-mnop qrst-uvwx",
      );
      await user.click(screen.getByRole("button", { name: "Entrar" }));

      expect(await screen.findByText("painel")).toBeInTheDocument();
      const calls = callsTo(spy, RECOVERY);
      expect(calls).toHaveLength(1);
      expect(JSON.parse(String(calls[0][1]?.body))).toEqual({
        code: "ABCDEFGH2JKLMNOPQRSTUVWX",
      });
    });

    test("an incomplete code is not sent", async () => {
      const spy = mockApi(verifyRoutes);
      renderPage();
      const user = await openRecovery();

      await user.type(screen.getByLabelText(RECOVERY_LABEL), "ABCD-EFGH");
      await user.click(screen.getByRole("button", { name: "Entrar" }));

      expect(await screen.findByText(RECOVERY_INVALID)).toHaveAttribute(
        "role",
        "alert",
      );
      expect(callsTo(spy, RECOVERY)).toHaveLength(0);
    });

    test("400 shows the wrong or used code message", async () => {
      mockApi({ ...verifyRoutes, [RECOVERY]: () => json(400) });
      renderPage();
      const user = await openRecovery();

      await user.type(
        screen.getByLabelText(RECOVERY_LABEL),
        "ABCDEFGHJKLMNOPQRSTUVWXY",
      );
      await user.click(screen.getByRole("button", { name: "Entrar" }));

      expect(await screen.findByText(RECOVERY_WRONG)).toHaveAttribute(
        "role",
        "alert",
      );
    });

    test("429 shows the blocked message", async () => {
      mockApi({ ...verifyRoutes, [RECOVERY]: () => json(429) });
      renderPage();
      const user = await openRecovery();

      await user.type(
        screen.getByLabelText(RECOVERY_LABEL),
        "ABCDEFGHJKLMNOPQRSTUVWXY",
      );
      await user.click(screen.getByRole("button", { name: "Entrar" }));

      expect(await screen.findByText(BLOCKED)).toHaveAttribute("role", "alert");
    });
  });
});
