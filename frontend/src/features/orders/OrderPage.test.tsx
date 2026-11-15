import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "@/features/products/testing";
import OrderPage from "./OrderPage";

const ORDER_ID = "9a8b7c6d-1111-4222-8333-444455556666";
const PRODUCT_ID = "3f2b8c1e-4d5a-4b6c-9d7e-1a2b3c4d5e6f";
const PIX_CODE = `00020126${"x".repeat(60)}6304ABCD`;

function order(overrides: Record<string, unknown> = {}) {
  return {
    id: ORDER_ID,
    productId: PRODUCT_ID,
    productTitle: "Guia de saúde",
    priceCents: 1990,
    status: "PENDING",
    expiresAt: "2026-11-13T12:30:00Z",
    secondsToExpire: 1800,
    pixCopyPaste: PIX_CODE,
    ...overrides,
  };
}

function renderOrder(path = `/pedidos/${ORDER_ID}`) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/pedidos/:id" element={<OrderPage />} />
          <Route path="/entrar" element={<p>tela de entrada</p>} />
          <Route path="/catalogo/:id" element={<p>tela do produto</p>} />
          <Route path="/catalogo" element={<p>tela do catálogo</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function mockFetch(...responses: Array<Response | Error>) {
  // The queue serves the full Order calls; the polling route has its own tests and is ignored here.
  const queue = [...responses];
  return vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    if (String(input).endsWith("/status"))
      throw new TypeError("not under test");
    const next = queue.shift();
    if (next === undefined) throw new TypeError("no more responses");
    if (next instanceof Error) throw next;
    return next;
  });
}

// Answers by URL: the full Order from `current.order`, the light route from `current.status`.
function routeFetch(current: {
  order: Record<string, unknown>;
  status: string | Error;
}) {
  return vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const url = String(input);
    if (url.endsWith("/status")) {
      if (current.status instanceof Error) throw current.status;
      return jsonResponse(200, { status: current.status });
    }
    return jsonResponse(200, current.order);
  });
}

function statusCalls(spy: ReturnType<typeof mockFetch>) {
  return spy.mock.calls.filter(([url]) => String(url).endsWith("/status"));
}

function fullOrderCalls(spy: ReturnType<typeof mockFetch>) {
  return spy.mock.calls.filter(
    ([url]) => String(url) === `/api/orders/${ORDER_ID}`,
  );
}

const PAID = { status: "PAID", secondsToExpire: 0, pixCopyPaste: null };
const EXPIRED = { status: "EXPIRED", secondsToExpire: 0, pixCopyPaste: null };

function orderCalls(spy: ReturnType<typeof mockFetch>) {
  return spy.mock.calls.filter(
    ([url]) =>
      String(url).startsWith("/api/orders/") &&
      !String(url).endsWith("/status"),
  );
}

// One second at a time, so React renders between the chained timeouts of the countdown.
async function tick(ms: number) {
  for (let elapsed = 0; elapsed < ms; elapsed += 1000) {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
  }
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  localStorage.clear();
  sessionStorage.clear();
});

describe("OrderPage", () => {
  test("FR-019: mostra o QR Code, o código copia e cola, o Produto e o valor", async () => {
    mockFetch(jsonResponse(200, order()));
    renderOrder();
    expect(
      await screen.findByRole("heading", { name: "Pague com Pix" }),
    ).toBeTruthy();
    expect(screen.getByRole("img", { name: "QR Code do Pix" })).toBeTruthy();
    expect(
      (screen.getByLabelText("Pix copia e cola") as HTMLTextAreaElement).value,
    ).toBe(PIX_CODE);
    expect(screen.getByText("Guia de saúde")).toBeTruthy();
    expect(screen.getByText(/19,90/)).toBeTruthy();
    expect(document.title).toBe("Pagamento por Pix | SapiensIA");
    expect(document.activeElement).toBe(
      screen.getByRole("heading", { level: 1 }),
    );
  });

  test("a contagem começa em secondsToExpire e desce", async () => {
    mockFetch(jsonResponse(200, order()));
    renderOrder();
    expect((await screen.findByRole("timer")).textContent).toBe(
      "Este Pix expira em 30:00.",
    );
    await tick(1000);
    expect(screen.getByRole("timer").textContent).toBe(
      "Este Pix expira em 29:59.",
    );
  });

  test("anuncia 5 minutos e 1 minuto, e não anuncia cada segundo", async () => {
    mockFetch(jsonResponse(200, order({ secondsToExpire: 302 })));
    renderOrder();
    await screen.findByRole("timer");
    const live = document.querySelector('[aria-live="polite"]') as HTMLElement;
    expect(live.textContent).toBe("");
    await tick(1000);
    expect(live.textContent).toBe("");
    await tick(1000);
    expect(live.textContent).toBe("Faltam 5 minutos para o Pix expirar.");
    await tick(240_000);
    expect(live.textContent).toBe("Falta 1 minuto para o Pix expirar.");
    expect(screen.getByRole("timer").getAttribute("aria-live")).not.toBe(
      "polite",
    );
  });

  test('ao zerar, consulta o Pedido de novo e mostra "Este Pix expirou" sem o código', async () => {
    const spy = mockFetch(
      jsonResponse(200, order({ secondsToExpire: 2 })),
      jsonResponse(
        200,
        order({ status: "EXPIRED", secondsToExpire: 0, pixCopyPaste: null }),
      ),
    );
    renderOrder();
    await screen.findByRole("timer");
    await tick(2000);
    const heading = await screen.findByRole("heading", {
      name: "Este Pix expirou",
    });
    expect(orderCalls(spy)).toHaveLength(2);
    expect(screen.queryByLabelText("Pix copia e cola")).toBeNull();
    expect(screen.queryByRole("img", { name: "QR Code do Pix" })).toBeNull();
    expect(document.title).toBe("Pix expirado | SapiensIA");
    expect(document.activeElement).toBe(heading);
  });

  test("em zero, enquanto o servidor ainda diz PENDING, continua consultando até expirar", async () => {
    const spy = mockFetch(
      jsonResponse(200, order({ secondsToExpire: 1 })),
      jsonResponse(200, order({ secondsToExpire: 0 })),
      jsonResponse(
        200,
        order({ status: "EXPIRED", secondsToExpire: 0, pixCopyPaste: null }),
      ),
    );
    renderOrder();
    await screen.findByRole("timer");
    await tick(1000);
    await tick(2000);
    expect(
      await screen.findByRole("heading", { name: "Este Pix expirou" }),
    ).toBeTruthy();
    expect(orderCalls(spy)).toHaveLength(3);
  });

  test("Pedido já expirado abre direto na tela de expirado, com o link para o Produto", async () => {
    mockFetch(
      jsonResponse(
        200,
        order({ status: "EXPIRED", secondsToExpire: 0, pixCopyPaste: null }),
      ),
    );
    renderOrder();
    expect(
      await screen.findByRole("heading", { name: "Este Pix expirou" }),
    ).toBeTruthy();
    expect(
      screen.getByText(/O prazo de 30 minutos para pagar terminou/),
    ).toBeTruthy();
    const link = screen.getByRole("link", { name: "Voltar ao Produto" });
    expect(link.getAttribute("href")).toBe(`/catalogo/${PRODUCT_ID}`);
  });

  test("FR-020: a página de um Pedido reembolsado mostra o título e a frase, sem QR Code", async () => {
    const spy = mockFetch(
      jsonResponse(
        200,
        order({ status: "REFUNDED", secondsToExpire: 0, pixCopyPaste: null }),
      ),
    );
    renderOrder();
    const heading = await screen.findByRole("heading", {
      name: "Pedido reembolsado",
    });
    expect(
      screen.getByText(
        "O valor deste Pedido foi devolvido e o Acesso ao Produto foi cancelado.",
      ),
    ).toBeTruthy();
    expect(screen.queryByRole("img", { name: "QR Code do Pix" })).toBeNull();
    expect(screen.queryByLabelText("Pix copia e cola")).toBeNull();
    expect(screen.queryByRole("timer")).toBeNull();
    expect(
      screen
        .getByRole("link", { name: "Voltar ao Produto" })
        .getAttribute("href"),
    ).toBe(`/catalogo/${PRODUCT_ID}`);
    expect(document.activeElement).toBe(heading);
    await tick(5000);
    expect(statusCalls(spy)).toHaveLength(0);
    expect(orderCalls(spy)).toHaveLength(1);
  });

  test('Copiar código escreve na área de transferência e mostra "Código copiado."', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText },
      configurable: true,
    });
    mockFetch(jsonResponse(200, order()));
    renderOrder();
    fireEvent.click(
      await screen.findByRole("button", { name: "Copiar código" }),
    );
    expect((await screen.findByRole("status")).textContent).toBe(
      "Código copiado.",
    );
    expect(writeText).toHaveBeenCalledWith(PIX_CODE);
  });

  test("falha ao copiar mostra a instrução manual", async () => {
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText: vi.fn().mockRejectedValue(new Error("denied")) },
      configurable: true,
    });
    mockFetch(jsonResponse(200, order()));
    renderOrder();
    fireEvent.click(
      await screen.findByRole("button", { name: "Copiar código" }),
    );
    expect((await screen.findByRole("alert")).textContent).toBe(
      "Não foi possível copiar. Selecione o código e copie manualmente.",
    );
  });

  test('SEC-011: 404 mostra "Pedido não encontrado"', async () => {
    mockFetch(jsonResponse(404));
    renderOrder();
    expect(
      await screen.findByRole("heading", { name: "Pedido não encontrado" }),
    ).toBeTruthy();
    expect(
      screen.getByText(
        "Não encontramos este Pedido. Confira o endereço ou volte ao Catálogo.",
      ),
    ).toBeTruthy();
    expect(
      screen.getByRole("link", { name: "Voltar ao Catálogo" }),
    ).toBeTruthy();
    expect(document.title).toBe("Pedido não encontrado | SapiensIA");
  });

  test('502 no GET mostra a mensagem e o botão "Tentar novamente", que consulta de novo', async () => {
    const spy = mockFetch(
      jsonResponse(502, {
        errors: { payment: "PAYMENT_PROVIDER_UNAVAILABLE" },
      }),
      jsonResponse(200, order()),
    );
    renderOrder();
    expect((await screen.findByRole("alert")).textContent).toBe(
      "Não foi possível carregar o Pedido agora. Tente novamente.",
    );
    fireEvent.click(screen.getByRole("button", { name: "Tentar novamente" }));
    expect(
      await screen.findByRole("heading", { name: "Pague com Pix" }),
    ).toBeTruthy();
    expect(orderCalls(spy)).toHaveLength(2);
  });

  test("401 guarda o caminho do Pedido e leva a /entrar", async () => {
    mockFetch(jsonResponse(401));
    renderOrder();
    expect(await screen.findByText("tela de entrada")).toBeTruthy();
    const stored = JSON.parse(
      localStorage.getItem("sapiensia.returnTo") ?? "{}",
    );
    expect(stored.path).toBe(`/pedidos/${ORDER_ID}`);
  });

  test('erro de rede mostra a mensagem e o botão "Tentar novamente"', async () => {
    mockFetch(new TypeError("network"));
    renderOrder();
    expect((await screen.findByRole("alert")).textContent).toBe(
      "Não foi possível carregar o Pedido agora. Tente novamente.",
    );
    expect(
      screen.getByRole("button", { name: "Tentar novamente" }),
    ).toBeTruthy();
  });

  test("o título da aba muda em cada estado", async () => {
    mockFetch(jsonResponse(200, order()));
    renderOrder();
    expect(document.title).toBe("Carregando… | SapiensIA");
    await screen.findByRole("timer");
    expect(document.title).toBe("Pagamento por Pix | SapiensIA");
  });

  test("o código não vai para localStorage nem sessionStorage", async () => {
    mockFetch(jsonResponse(200, order()));
    renderOrder();
    await screen.findByRole("timer");
    expect(
      JSON.stringify({ ...localStorage, ...sessionStorage }),
    ).not.toContain(PIX_CODE);
  });

  test('FR-024: mostra "Pagamento confirmado", o Produto e o valor quando o Pedido está pago', async () => {
    mockFetch(jsonResponse(200, order(PAID)));
    renderOrder();
    const heading = await screen.findByRole("heading", {
      name: "Pagamento confirmado",
    });
    expect(
      screen.getByText(
        "Recebemos o seu Pix. Você já tem Acesso a este Produto.",
      ),
    ).toBeTruthy();
    expect(screen.getByText("Guia de saúde")).toBeTruthy();
    expect(screen.getByText(/19,90/)).toBeTruthy();
    expect(
      screen.getByRole("link", { name: "Voltar ao Catálogo" }),
    ).toBeTruthy();
    expect(document.title).toBe("Pagamento confirmado | SapiensIA");
    expect(document.activeElement).toBe(heading);
    expect(screen.queryByRole("img", { name: "QR Code do Pix" })).toBeNull();
    expect(screen.queryByLabelText("Pix copia e cola")).toBeNull();
    expect(screen.queryByRole("timer")).toBeNull();
  });

  test("FR-029: o Pedido pago leva à Biblioteca; o pendente não", async () => {
    mockFetch(jsonResponse(200, order(PAID)));
    const paid = renderOrder();
    expect(
      await screen.findByRole("link", { name: "Ir para a Biblioteca" }),
    ).toHaveAttribute("href", "/biblioteca");
    paid.unmount();

    mockFetch(jsonResponse(200, order()));
    renderOrder();
    await screen.findByRole("heading", { name: "Pague com Pix" });
    expect(
      screen.queryByRole("link", { name: "Ir para a Biblioteca" }),
    ).toBeNull();
  });

  test('FR-024: troca de "Pague com Pix" para "Pagamento confirmado" sem recarregar quando a consulta devolve PAID', async () => {
    const current = { order: order(), status: "PENDING" as string | Error };
    routeFetch(current);
    renderOrder();
    await screen.findByRole("heading", { name: "Pague com Pix" });
    current.order = order(PAID);
    current.status = "PAID";
    await tick(5000);
    const heading = await screen.findByRole("heading", {
      name: "Pagamento confirmado",
    });
    expect(document.activeElement).toBe(heading);
    expect(document.title).toBe("Pagamento confirmado | SapiensIA");
  });

  test('ADR-0003: troca de "Este Pix expirou" para "Pagamento confirmado" quando o pagamento chega depois', async () => {
    const current = {
      order: order(EXPIRED),
      status: "EXPIRED" as string | Error,
    };
    routeFetch(current);
    renderOrder();
    await screen.findByRole("heading", { name: "Este Pix expirou" });
    current.order = order(PAID);
    current.status = "PAID";
    await tick(5000);
    expect(
      await screen.findByRole("heading", { name: "Pagamento confirmado" }),
    ).toBeTruthy();
  });

  test("mostra o aviso para quem já pagou na tela de expirado", async () => {
    mockFetch(jsonResponse(200, order(EXPIRED)));
    renderOrder();
    await screen.findByRole("heading", { name: "Este Pix expirou" });
    expect(
      screen.getByText(
        "Se você já pagou, aguarde alguns minutos: esta página se atualiza sozinha quando o pagamento for confirmado.",
      ),
    ).toBeTruthy();
  });

  test("mostra a frase nova depois do pagamento na tela pendente", async () => {
    mockFetch(jsonResponse(200, order()));
    renderOrder();
    await screen.findByRole("timer");
    expect(
      screen.getByText(
        "Depois de pagar, aguarde nesta página: ela se atualiza sozinha quando o pagamento for confirmado e o Acesso ao Produto for liberado.",
      ),
    ).toBeTruthy();
  });

  test("para de consultar 10 minutos depois de expirar", async () => {
    const spy = routeFetch({ order: order(EXPIRED), status: "EXPIRED" });
    renderOrder();
    await screen.findByRole("heading", { name: "Este Pix expirou" });
    await tick(600_000);
    const after = statusCalls(spy).length;
    expect(after).toBeGreaterThanOrEqual(100);
    expect(after).toBeLessThanOrEqual(121);
    await tick(30_000);
    expect(statusCalls(spy)).toHaveLength(after);
  });

  test("a janela de 10 minutos não recomeça quando uma recarga do Pedido falha", async () => {
    let statusAnswer = "EXPIRED";
    let orderFails = false;
    const spy = vi
      .spyOn(globalThis, "fetch")
      .mockImplementation(async (input) => {
        const url = String(input);
        if (url.endsWith("/status")) {
          return jsonResponse(200, { status: statusAnswer });
        }
        if (orderFails) return jsonResponse(500);
        return jsonResponse(200, order(EXPIRED));
      });
    renderOrder();
    await screen.findByRole("heading", { name: "Este Pix expirou" });
    await tick(300_000);
    // The server says something else once, the reload fails, the Customer retries.
    statusAnswer = "PENDING";
    orderFails = true;
    await tick(5000);
    await screen.findByRole("alert");
    statusAnswer = "EXPIRED";
    orderFails = false;
    fireEvent.click(screen.getByRole("button", { name: "Tentar novamente" }));
    await screen.findByRole("heading", { name: "Este Pix expirou" });
    await tick(300_000);
    const after = statusCalls(spy).length;
    await tick(60_000);
    expect(statusCalls(spy)).toHaveLength(after);
  });

  test("não consulta o estado de um Pedido pago", async () => {
    const spy = routeFetch({ order: order(PAID), status: "PAID" });
    renderOrder();
    await screen.findByRole("heading", { name: "Pagamento confirmado" });
    await tick(20_000);
    expect(statusCalls(spy)).toHaveLength(0);
  });

  test("consulta só a rota do estado, e não o Pedido inteiro, enquanto nada muda", async () => {
    const spy = routeFetch({ order: order(), status: "PENDING" });
    renderOrder();
    await screen.findByRole("timer");
    await tick(20_000);
    expect(statusCalls(spy).length).toBeGreaterThanOrEqual(3);
    expect(fullOrderCalls(spy)).toHaveLength(1);
  });

  test("uma falha na consulta do estado não tira a tela do Pix", async () => {
    const current = {
      order: order(),
      status: new TypeError("network") as string | Error,
    };
    routeFetch(current);
    renderOrder();
    await screen.findByRole("timer");
    await tick(10_000);
    expect(screen.getByRole("heading", { name: "Pague com Pix" })).toBeTruthy();
    expect(screen.queryByRole("alert")).toBeNull();
    current.order = order(PAID);
    current.status = "PAID";
    await tick(5000);
    expect(
      await screen.findByRole("heading", { name: "Pagamento confirmado" }),
    ).toBeTruthy();
  });

  test("FR-024: a sessão que expira durante a espera leva ao login e guarda o caminho do Pedido", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input).endsWith("/status")) return jsonResponse(401);
      return jsonResponse(200, order());
    });
    renderOrder();
    expect(await screen.findByText("tela de entrada")).toBeTruthy();
    const stored = JSON.parse(
      localStorage.getItem("sapiensia.returnTo") ?? "{}",
    );
    expect(stored.path).toBe(`/pedidos/${ORDER_ID}`);
  });

  test("SEC-027: parâmetros na URL não mudam o que a tela mostra", async () => {
    routeFetch({ order: order(), status: "PENDING" });
    renderOrder(`/pedidos/${ORDER_ID}?status=PAID&paid=true`);
    expect(
      await screen.findByRole("heading", { name: "Pague com Pix" }),
    ).toBeTruthy();
    await tick(10_000);
    expect(
      screen.queryByRole("heading", { name: "Pagamento confirmado" }),
    ).toBeNull();
  });
});
