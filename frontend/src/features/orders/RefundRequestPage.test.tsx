import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "@/features/products/testing";
import RefundRequestPage from "./RefundRequestPage";

const ORDER_ID = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d";
const OTHER_ID = "3f2b8c1e-4d5a-4b6c-9d7e-1a2b3c4d5e6f";
const SUPPORT = "sapiensia.operacao@gmail.com";

function item(overrides: Record<string, unknown> = {}) {
  return {
    id: ORDER_ID,
    productTitle: "Guia de saúde",
    priceCents: 2990,
    status: "PAID",
    createdAt: "2026-11-19T17:05:00Z",
    expiresAt: "2026-11-19T17:35:00Z",
    refundableUntil: "2026-11-26",
    refundRequestedAt: null,
    ...overrides,
  };
}

function setCsrfCookie(value: string) {
  // biome-ignore lint/suspicious/noDocumentCookie: apiFetch reads the CSRF token from document.cookie
  document.cookie = `XSRF-TOKEN=${value}`;
}

function renderPage(path = `/pedidos/${ORDER_ID}/reembolso`) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const view = render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route
            path="/pedidos/:id/reembolso"
            element={<RefundRequestPage />}
          />
          <Route path="/pedidos" element={<p>tela de Meus Pedidos</p>} />
          <Route path="/entrar" element={<p>tela de entrada</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...view, client };
}

// GET /api/orders answers the list; the POST answers `post`.
function mockApi(items: unknown[], post?: Response) {
  return vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    if (String(input) === "/api/orders") {
      return jsonResponse(200, { items });
    }
    if (post) return post.clone();
    throw new TypeError("unexpected call");
  });
}

function postCalls(spy: ReturnType<typeof mockApi>) {
  return spy.mock.calls.filter(([, init]) => init?.method === "POST");
}

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
  setCsrfCookie("; max-age=0");
});

async function chooseReason(name: string) {
  await userEvent.click(await screen.findByRole("radio", { name }));
}

async function submit() {
  await userEvent.click(
    screen.getByRole("button", { name: "Enviar solicitação" }),
  );
}

describe("RefundRequestPage", () => {
  test("mostra o Produto, o Valor, o prazo e o aviso do Acesso", async () => {
    mockApi([item()]);
    renderPage();

    const heading = await screen.findByRole("heading", {
      name: "Pedir Reembolso",
    });
    expect(document.title).toBe("Pedir Reembolso | SapiensIA");
    expect(screen.getByText("Guia de saúde")).toBeVisible();
    expect(screen.getByText(/29,90/)).toBeVisible();
    expect(
      screen.getByText(
        "Você pode pedir o Reembolso deste Pedido até 26/11/2026. O valor volta pelo Pix, para quem pagou.",
      ),
    ).toBeVisible();
    expect(
      screen.getByText(
        "Ao enviar, o seu Acesso a este Produto é suspenso na hora: ele sai da sua Biblioteca e você não consegue mais baixar o arquivo.",
      ),
    ).toBeVisible();
    expect(document.activeElement).toBe(heading);
  });

  test("mostra os 4 motivos, nesta ordem, num grupo com a pergunta", async () => {
    mockApi([item()]);
    renderPage();

    const group = await screen.findByRole("group", {
      name: "Qual é o motivo?",
    });
    expect(
      Array.from(group.querySelectorAll("input[type=radio]")).map(
        (radio) => (radio as HTMLInputElement).labels?.[0]?.textContent,
      ),
    ).toEqual([
      "Não atendeu ao que eu esperava",
      "Arquivo com defeito",
      "Comprei por engano",
      "Outro",
    ]);
    expect(screen.getByLabelText("Quer contar mais? (opcional)")).toBeVisible();
    expect(
      screen.getByText(
        "Até 500 caracteres. Não escreva senhas nem números de documentos.",
      ),
    ).toBeVisible();
    expect(
      screen.getByRole("link", { name: "Voltar a Meus Pedidos" }),
    ).toHaveAttribute("href", "/pedidos");
  });

  test("enviar sem motivo mostra o erro e não chama a API", async () => {
    setCsrfCookie("t");
    const spy = mockApi([item()]);
    renderPage();

    await screen.findByRole("heading", { name: "Pedir Reembolso" });
    await submit();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Escolha um motivo.",
    );
    expect(postCalls(spy)).toHaveLength(0);
    for (const radio of screen.getAllByRole("radio")) {
      expect(radio).toBeInvalid();
      expect(radio).toHaveAccessibleDescription("Escolha um motivo.");
    }
  });

  test("comentário com 501 caracteres mostra o erro e não chama a API", async () => {
    setCsrfCookie("t");
    const spy = mockApi([item()]);
    renderPage();

    await chooseReason("Outro");
    await userEvent.click(
      screen.getByLabelText("Quer contar mais? (opcional)"),
    );
    await userEvent.paste("x".repeat(501));
    await submit();

    expect(await screen.findByText("Use até 500 caracteres.")).toBeVisible();
    expect(screen.getByLabelText("Quer contar mais? (opcional)")).toBeInvalid();
    expect(postCalls(spy)).toHaveLength(0);
  });

  test("envia o motivo, mostra a confirmação com o valor e leva o foco ao título", async () => {
    setCsrfCookie("t");
    const spy = mockApi(
      [item()],
      jsonResponse(201, { requestedAt: "2026-11-19T18:00:00Z" }),
    );
    const { client } = renderPage();
    const invalidate = vi.spyOn(client, "invalidateQueries");

    await chooseReason("Arquivo com defeito");
    await submit();

    const heading = await screen.findByRole("heading", {
      name: "Reembolso solicitado",
    });
    const [url, init] = postCalls(spy)[0];
    expect(String(url)).toBe(`/api/orders/${ORDER_ID}/refund-request`);
    expect(JSON.parse(String(init?.body))).toEqual({
      reason: "DEFECTIVE_FILE",
    });
    expect(
      screen.getByText(
        /Recebemos a sua solicitação\. O valor de R\$\s29,90 será devolvido pelo Pix, para quem pagou, em até 2 dias úteis\. O seu Acesso a este Produto está suspenso e será cancelado quando o valor for devolvido\. Você recebe um e-mail quando isso acontecer\./,
      ),
    ).toBeVisible();
    expect(document.title).toBe("Reembolso solicitado | SapiensIA");
    expect(document.activeElement).toBe(heading);
    expect(
      screen.queryByRole("button", { name: "Enviar solicitação" }),
    ).toBeNull();
    expect(
      screen.getByRole("link", { name: "Voltar a Meus Pedidos" }),
    ).toBeVisible();
    const keys = invalidate.mock.calls.map(([filter]) => filter?.queryKey);
    expect(keys).toContainEqual(["orders"]);
    expect(keys).toContainEqual(["library"]);
  });

  test("a confirmação continua quando a lista falha ao recarregar depois do envio", async () => {
    setCsrfCookie("t");
    let listCalls = 0;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input) === "/api/orders") {
        listCalls += 1;
        return listCalls === 1
          ? jsonResponse(200, { items: [item()] })
          : jsonResponse(500);
      }
      return jsonResponse(201, { requestedAt: "2026-11-19T18:00:00Z" });
    });
    renderPage();

    await chooseReason("Outro");
    await submit();

    await screen.findByRole("heading", { name: "Reembolso solicitado" });
    await waitFor(() => expect(listCalls).toBeGreaterThan(1));
    expect(
      screen.getByRole("heading", { name: "Reembolso solicitado" }),
    ).toBeVisible();
    expect(screen.getByText(/R\$\s29,90/)).toBeVisible();
    expect(
      screen.queryByText(
        "Não foi possível carregar os seus Pedidos. Tente novamente.",
      ),
    ).toBeNull();
  });

  test("envia o comentário quando escrito", async () => {
    setCsrfCookie("t");
    const spy = mockApi(
      [item()],
      jsonResponse(201, { requestedAt: "2026-11-19T18:00:00Z" }),
    );
    renderPage();

    await chooseReason("Comprei por engano");
    await userEvent.click(
      screen.getByLabelText("Quer contar mais? (opcional)"),
    );
    await userEvent.paste("foi sem querer");
    await submit();

    await screen.findByRole("heading", { name: "Reembolso solicitado" });
    expect(JSON.parse(String(postCalls(spy)[0][1]?.body))).toEqual({
      reason: "BOUGHT_BY_MISTAKE",
      details: "foi sem querer",
    });
  });

  test.each([
    [
      "WINDOW_CLOSED",
      jsonResponse(409, { errors: { refund: "WINDOW_CLOSED" } }),
      `O prazo para pedir o Reembolso pelo site terminou. Se o arquivo tiver defeito, escreva para ${SUPPORT}.`,
    ],
    [
      "NOT_AVAILABLE",
      jsonResponse(409, { errors: { refund: "NOT_AVAILABLE" } }),
      `Este Pedido não pode ser reembolsado pelo site. Se precisar de ajuda, escreva para ${SUPPORT}.`,
    ],
    [
      "404",
      jsonResponse(404),
      `Este Pedido não pode ser reembolsado pelo site. Se precisar de ajuda, escreva para ${SUPPORT}.`,
    ],
    [
      "429",
      jsonResponse(429, { errors: { rate: "RATE_LIMITED" } }),
      "Muitas tentativas. Aguarde alguns minutos e tente de novo.",
    ],
    [
      "500",
      jsonResponse(500),
      "Não foi possível enviar a solicitação agora. Tente novamente.",
    ],
  ])("recusa %s mostra o texto da tabela", async (_name, post, text) => {
    setCsrfCookie("t");
    mockApi([item()], post);
    renderPage();

    await chooseReason("Outro");
    await submit();

    expect(await screen.findByRole("alert")).toHaveTextContent(text);
    expect(
      screen.queryByRole("heading", { name: "Reembolso solicitado" }),
    ).toBeNull();
  });

  test("DETAILS_TOO_LONG do servidor vira erro no comentário", async () => {
    setCsrfCookie("t");
    mockApi(
      [item()],
      jsonResponse(400, { errors: { details: "DETAILS_TOO_LONG" } }),
    );
    renderPage();

    await chooseReason("Outro");
    await submit();

    expect(await screen.findByText("Use até 500 caracteres.")).toBeVisible();
  });

  test("REASON_REQUIRED do servidor vira erro no motivo", async () => {
    setCsrfCookie("t");
    mockApi(
      [item()],
      jsonResponse(400, { errors: { reason: "REASON_REQUIRED" } }),
    );
    renderPage();

    await chooseReason("Outro");
    await submit();

    expect(await screen.findByText("Escolha um motivo.")).toBeVisible();
  });

  test("durante o envio o botão mostra Enviando… e fica desativado", async () => {
    setCsrfCookie("t");
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input) === "/api/orders") {
        return jsonResponse(200, { items: [item()] });
      }
      return new Promise<Response>(() => {});
    });
    renderPage();

    await chooseReason("Outro");
    await submit();

    expect(
      await screen.findByRole("button", { name: "Enviando…" }),
    ).toBeDisabled();
  });

  test("401 no envio leva a /entrar guardando o retorno", async () => {
    setCsrfCookie("t");
    mockApi([item()], jsonResponse(401));
    renderPage();

    await chooseReason("Outro");
    await submit();

    expect(await screen.findByText("tela de entrada")).toBeVisible();
    expect(localStorage.getItem("sapiensia.returnTo")).toContain(
      `/pedidos/${ORDER_ID}/reembolso`,
    );
  });

  test("401 na lista leva a /entrar guardando o retorno", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(401));
    renderPage();

    expect(await screen.findByText("tela de entrada")).toBeVisible();
    expect(localStorage.getItem("sapiensia.returnTo")).toContain(
      `/pedidos/${ORDER_ID}/reembolso`,
    );
  });

  test("Pedido já solicitado abre direto na confirmação", async () => {
    mockApi([
      item({
        refundableUntil: null,
        refundRequestedAt: "2026-11-19T18:00:00Z",
      }),
    ]);
    renderPage();

    const heading = await screen.findByRole("heading", {
      name: "Reembolso solicitado",
    });
    expect(screen.getByText(/29,90/)).toBeVisible();
    expect(screen.queryByRole("group")).toBeNull();
    expect(document.activeElement).toBe(heading);
  });

  test("Pedido pago com o prazo encerrado mostra o texto do prazo terminado", async () => {
    mockApi([item({ refundableUntil: null })]);
    renderPage();

    expect(
      await screen.findByText(
        `O prazo para pedir o Reembolso pelo site terminou. Se o arquivo tiver defeito, escreva para ${SUPPORT}.`,
      ),
    ).toBeVisible();
    expect(screen.queryByText(/não pode ser reembolsado pelo site/)).toBeNull();
    expect(screen.queryByRole("group")).toBeNull();
    expect(
      screen.getByRole("link", { name: "Voltar a Meus Pedidos" }),
    ).toBeVisible();
  });

  test.each(["PENDING", "EXPIRED", "REFUNDED"])(
    "Pedido %s não pode ser reembolsado pelo site",
    async (status) => {
      mockApi([item({ status, refundableUntil: null })]);
      renderPage();

      expect(
        await screen.findByText(
          `Este Pedido não pode ser reembolsado pelo site. Se precisar de ajuda, escreva para ${SUPPORT}.`,
        ),
      ).toBeVisible();
      expect(screen.queryByRole("group")).toBeNull();
      expect(
        screen.getByRole("link", { name: "Voltar a Meus Pedidos" }),
      ).toBeVisible();
    },
  );

  test("Pedido fora da lista mostra Pedido não encontrado", async () => {
    mockApi([item({ id: OTHER_ID })]);
    renderPage();

    expect(
      await screen.findByRole("heading", { name: "Pedido não encontrado" }),
    ).toBeVisible();
    expect(
      screen.getByRole("link", { name: "Voltar a Meus Pedidos" }),
    ).toBeVisible();
  });

  test("mostra Carregando… enquanto busca", () => {
    vi.spyOn(globalThis, "fetch").mockReturnValue(new Promise(() => {}));
    renderPage();

    expect(screen.getByText("Carregando…")).toBeVisible();
  });

  test("falha ao carregar mostra o erro e Tentar novamente busca outra vez", async () => {
    const spy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse(500))
      .mockResolvedValueOnce(jsonResponse(200, { items: [item()] }));
    renderPage();

    expect(
      await screen.findByText(
        "Não foi possível carregar os seus Pedidos. Tente novamente.",
      ),
    ).toBeVisible();
    await userEvent.click(
      screen.getByRole("button", { name: "Tentar novamente" }),
    );

    expect(
      await screen.findByRole("heading", { name: "Pedir Reembolso" }),
    ).toBeVisible();
    await waitFor(() => expect(spy).toHaveBeenCalledTimes(2));
  });
});
