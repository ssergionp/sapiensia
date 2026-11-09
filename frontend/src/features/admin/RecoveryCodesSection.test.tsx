import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, test, vi } from "vitest";
import { adminSessionQueryKey } from "./hooks";
import RecoveryCodesSection from "./RecoveryCodesSection";

const REGENERATE = "POST /api/admin/recovery-codes";
const CODES = Array.from({ length: 10 }, (_, i) =>
  String.fromCharCode(65 + i).repeat(24),
);
const WRONG_CODE =
  "Código incorreto ou vencido. Aguarde o próximo código do aplicativo e tente de novo.";
const GENERAL_ERROR =
  "Não foi possível gerar novos códigos agora. Tente novamente.";
const BLOCKED =
  "Muitas tentativas de entrada. Aguarde alguns minutos e tente de novo.";

function json(status: number, body?: unknown) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
  });
}

function mockApi(handler: () => Response | Promise<Response>) {
  return vi
    .spyOn(globalThis, "fetch")
    .mockImplementation(async (input, init) => {
      const key = `${init?.method ?? "GET"} ${String(input)}`;
      if (key === REGENERATE) return handler();
      return new Response(null, { status: 200 });
    });
}

function renderSection() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <RecoveryCodesSection />
    </QueryClientProvider>,
  );
  return client;
}

async function openForm() {
  const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "Gerar novos códigos" }));
  return user;
}

describe("RecoveryCodesSection", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  test("shows the title, the explanation and the button", () => {
    renderSection();

    expect(
      screen.getByRole("heading", { name: "Códigos de recuperação" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "Gere novos códigos se você perdeu os anteriores ou já usou alguns. Os códigos antigos param de funcionar na hora.",
      ),
    ).toBeInTheDocument();
  });

  test("asks for the authenticator code and can be cancelled", async () => {
    renderSection();

    const user = await openForm();

    expect(
      screen.getByText(
        "Para confirmar, digite o código de 6 dígitos do aplicativo autenticador.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByLabelText("Código do aplicativo autenticador"),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Cancelar" }));
    expect(
      screen.getByRole("button", { name: "Gerar novos códigos" }),
    ).toBeInTheDocument();
  });

  test("a code with fewer than 6 digits is not sent", async () => {
    const spy = mockApi(() => json(200, { codes: CODES }));
    renderSection();
    const user = await openForm();

    await user.type(
      screen.getByLabelText("Código do aplicativo autenticador"),
      "123",
    );
    await user.click(screen.getByRole("button", { name: "Confirmar e gerar" }));

    expect(
      await screen.findByText("Digite os 6 dígitos do código."),
    ).toHaveAttribute("role", "alert");
    expect(spy).not.toHaveBeenCalled();
  });

  test("generates, shows the new codes, and Concluir stays disabled until confirmed", async () => {
    const spy = mockApi(() => json(200, { codes: CODES }));
    const client = renderSection();
    const user = await openForm();

    await user.type(
      screen.getByLabelText("Código do aplicativo autenticador"),
      "123 456",
    );
    await user.click(screen.getByRole("button", { name: "Confirmar e gerar" }));

    expect(
      await screen.findByRole("heading", {
        name: "Seus novos códigos de recuperação",
      }),
    ).toBeInTheDocument();
    const call = spy.mock.calls.find(
      ([url]) => String(url) === "/api/admin/recovery-codes",
    );
    expect(JSON.parse(String(call?.[1]?.body))).toEqual({ code: "123456" });
    expect(
      screen.getByText(
        "Os códigos anteriores não funcionam mais. Estes aparecem só agora. Guarde-os no seu gerenciador de senhas. Quem tiver um código e a sua senha entra no painel.",
      ),
    ).toBeInTheDocument();
    const list = screen.getByRole("list", { name: "Códigos de recuperação" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(10);
    const done = screen.getByRole("button", { name: "Concluir" });
    expect(done).toBeDisabled();
    await user.click(
      screen.getByLabelText("Guardei meus códigos em um lugar seguro"),
    );
    expect(done).toBeEnabled();

    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(
      JSON.stringify(
        client
          .getMutationCache()
          .getAll()
          .map((m) => m.state.data),
      ),
    ).not.toContain(CODES[0]);

    await user.click(done);
    expect(screen.queryByText(CODES[0])).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Gerar novos códigos" }),
    ).toBeInTheDocument();
  });

  test('shows "Gerando…" and disables the button while sending', async () => {
    mockApi(() => new Promise<Response>(() => {}));
    renderSection();
    const user = await openForm();

    await user.type(
      screen.getByLabelText("Código do aplicativo autenticador"),
      "123456",
    );
    await user.click(screen.getByRole("button", { name: "Confirmar e gerar" }));

    const button = await screen.findByRole("button", { name: "Gerando…" });
    await waitFor(() => expect(button).toBeDisabled());
    expect(screen.getByRole("button", { name: "Cancelar" })).toBeDisabled();
  });

  test.each([
    { status: 400, message: WRONG_CODE },
    { status: 429, message: BLOCKED },
    { status: 500, message: GENERAL_ERROR },
  ])("$status shows the matching error", async ({ status, message }) => {
    mockApi(() => json(status));
    renderSection();
    const user = await openForm();

    await user.type(
      screen.getByLabelText("Código do aplicativo autenticador"),
      "123456",
    );
    await user.click(screen.getByRole("button", { name: "Confirmar e gerar" }));

    expect(await screen.findByText(message)).toHaveAttribute("role", "alert");
    expect(
      screen.getByRole("button", { name: "Confirmar e gerar" }),
    ).toBeEnabled();
  });

  test("401 marks the admin session as ended so the app redirects to the sign-in", async () => {
    mockApi(() => json(401));
    const client = renderSection();
    client.setQueryData(adminSessionQueryKey, {
      email: "admin@example.com",
      capabilities: [],
    });
    const user = await openForm();

    await user.type(
      screen.getByLabelText("Código do aplicativo autenticador"),
      "123456",
    );
    await user.click(screen.getByRole("button", { name: "Confirmar e gerar" }));

    await waitFor(() =>
      expect(client.getQueryData(adminSessionQueryKey)).toBeNull(),
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  test("shows each code in groups of 4 separated by hyphens", async () => {
    mockApi(() => json(200, { codes: CODES }));
    renderSection();
    const user = await openForm();

    await user.type(
      screen.getByLabelText("Código do aplicativo autenticador"),
      "123456",
    );
    await user.click(screen.getByRole("button", { name: "Confirmar e gerar" }));

    const list = await screen.findByRole("list", {
      name: "Códigos de recuperação",
    });
    const items = within(list).getAllByRole("listitem");
    expect(items[0]).toHaveTextContent("AAAA-AAAA-AAAA-AAAA-AAAA-AAAA");
    expect(items[9]).toHaveTextContent("JJJJ-JJJJ-JJJJ-JJJJ-JJJJ-JJJJ");
  });

  test("the typed authenticator code does not stay in the mutation cache", async () => {
    mockApi(() => json(200, { codes: CODES }));
    const client = renderSection();
    const user = await openForm();

    await user.type(
      screen.getByLabelText("Código do aplicativo autenticador"),
      "654321",
    );
    await user.click(screen.getByRole("button", { name: "Confirmar e gerar" }));
    await screen.findByRole("heading", {
      name: "Seus novos códigos de recuperação",
    });
    await user.click(
      screen.getByLabelText("Guardei meus códigos em um lugar seguro"),
    );
    await user.click(screen.getByRole("button", { name: "Concluir" }));
    await new Promise((resolve) => setTimeout(resolve, 20));

    expect(
      JSON.stringify(
        client
          .getMutationCache()
          .getAll()
          .map((m) => m.state.variables),
      ),
    ).not.toContain("654321");
  });

  test("a network failure shows the general error", async () => {
    mockApi(() => Promise.reject(new TypeError("network")));
    renderSection();
    const user = await openForm();

    await user.type(
      screen.getByLabelText("Código do aplicativo autenticador"),
      "123456",
    );
    await user.click(screen.getByRole("button", { name: "Confirmar e gerar" }));

    expect(await screen.findByText(GENERAL_ERROR)).toBeInTheDocument();
  });
});
