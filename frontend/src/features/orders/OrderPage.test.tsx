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

function renderOrder() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/pedidos/${ORDER_ID}`]}>
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
  const spy = vi.spyOn(globalThis, "fetch");
  for (const r of responses) {
    if (r instanceof Error) spy.mockRejectedValueOnce(r);
    else spy.mockResolvedValueOnce(r);
  }
  return spy;
}

function orderCalls(spy: ReturnType<typeof mockFetch>) {
  return spy.mock.calls.filter(([url]) =>
    String(url).startsWith("/api/orders/"),
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
});
