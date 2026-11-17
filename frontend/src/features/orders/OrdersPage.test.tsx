import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  createMemoryRouter,
  MemoryRouter,
  Route,
  RouterProvider,
  Routes,
} from "react-router";
import { afterEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "@/features/products/testing";
import OrdersPage from "./OrdersPage";

const PENDING_ID = "3f2b8c1e-4d5a-4b6c-9d7e-1a2b3c4d5e6f";
const PAID_ID = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d";
const EXPIRED_ID = "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f";

function summary(overrides: Record<string, unknown> = {}) {
  return {
    id: PENDING_ID,
    productTitle: "Guia de saúde",
    priceCents: 2990,
    status: "PENDING",
    // 2026-11-14T17:35Z is 14:35 in São Paulo.
    createdAt: "2026-11-14T17:05:00Z",
    expiresAt: "2026-11-14T17:35:00Z",
    ...overrides,
  };
}

function renderOrders() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const view = render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/pedidos"]}>
        <Routes>
          <Route path="/pedidos" element={<OrdersPage />} />
          <Route path="/entrar" element={<p>tela de entrada</p>} />
          <Route path="/catalogo" element={<p>tela do catálogo</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...view, client };
}

function mockItems(items: unknown[]) {
  return vi
    .spyOn(globalThis, "fetch")
    .mockResolvedValue(jsonResponse(200, { items }));
}

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
});

describe("OrdersPage", () => {
  test("FR-020: mostra cada Pedido com título, valor, data e estado", async () => {
    const spy = mockItems([
      summary(),
      summary({ id: PAID_ID, productTitle: "Guia pago", status: "PAID" }),
      summary({
        id: EXPIRED_ID,
        productTitle: "Guia vencido",
        status: "EXPIRED",
      }),
    ]);
    const { container } = renderOrders();

    expect(await screen.findByText("Guia de saúde")).toBeInTheDocument();
    expect(spy.mock.calls.map(([url]) => String(url))).toEqual(["/api/orders"]);
    expect(screen.getByRole("list")).toHaveAccessibleName("Meus Pedidos");
    const items = screen.getAllByRole("listitem");
    expect(items).toHaveLength(3);

    const [pending, paid, expired] = items;
    expect(within(pending).getByText("Aguardando pagamento")).toBeVisible();
    expect(within(pending).getByText("Pix válido até 14:35.")).toBeVisible();
    expect(within(pending).getByText("Pedido em 14/11/2026")).toBeVisible();
    expect(pending.textContent?.replace(/\s/g, " ")).toContain("R$ 29,90");
    expect(within(paid).getByText("Pago")).toBeVisible();
    expect(within(expired).getByText("Expirado")).toBeVisible();
    expect(container.querySelector("time")?.getAttribute("datetime")).toBe(
      "2026-11-14T17:05:00Z",
    );
  });

  test("FR-020: a data do Pedido usa o fuso de São Paulo", async () => {
    mockItems([summary({ createdAt: "2026-11-15T01:30:00Z" })]);
    const { container } = renderOrders();

    // 01:30Z on the 15th is still the 14th in São Paulo.
    expect(await screen.findByText("Pedido em 14/11/2026")).toBeVisible();
    expect(container.querySelector("time")).toHaveAttribute(
      "datetime",
      "2026-11-15T01:30:00Z",
    );
  });

  test("FR-020: Pedido pendente tem o link Ver o Pix para o detalhe", async () => {
    mockItems([summary()]);
    renderOrders();

    expect(
      await screen.findByRole("link", { name: "Ver o Pix de Guia de saúde" }),
    ).toHaveAttribute("href", `/pedidos/${PENDING_ID}`);
  });

  test("FR-020: Pedido pago tem o link Ir para a Biblioteca", async () => {
    mockItems([summary({ id: PAID_ID, status: "PAID" })]);
    renderOrders();

    expect(
      await screen.findByRole("link", { name: "Ir para a Biblioteca" }),
    ).toHaveAttribute("href", "/biblioteca");
    expect(screen.queryByText(/Pix válido até/)).toBeNull();
  });

  test("FR-020: Pedido expirado não tem link", async () => {
    mockItems([summary({ id: EXPIRED_ID, status: "EXPIRED" })]);
    renderOrders();

    expect(await screen.findByText("Expirado")).toBeVisible();
    expect(screen.queryByRole("link")).toBeNull();
    expect(screen.queryByText(/Pix válido até/)).toBeNull();
  });

  test('FR-020: Meus Pedidos mostra "Reembolsado" sem o link da Biblioteca', async () => {
    mockItems([summary({ id: PAID_ID, status: "REFUNDED" })]);
    renderOrders();

    expect(await screen.findByText("Reembolsado")).toBeVisible();
    expect(
      screen.queryByRole("link", { name: "Ir para a Biblioteca" }),
    ).toBeNull();
    expect(screen.queryByRole("link")).toBeNull();
    expect(screen.queryByText(/Pix válido até/)).toBeNull();
  });

  test("não calcula expiração no navegador", async () => {
    mockItems([summary({ expiresAt: "2020-01-01T00:00:00Z" })]);
    renderOrders();

    expect(await screen.findByText("Aguardando pagamento")).toBeVisible();
    expect(screen.queryByText("Expirado")).toBeNull();
  });

  test("lista vazia mostra o texto e o link para o Catálogo", async () => {
    mockItems([]);
    renderOrders();

    expect(
      await screen.findByText("Você ainda não fez nenhum Pedido."),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "Ver o Catálogo" }),
    ).toHaveAttribute("href", "/catalogo");
  });

  test("mostra Carregando… enquanto busca", () => {
    vi.spyOn(globalThis, "fetch").mockReturnValue(new Promise(() => {}));
    renderOrders();

    expect(screen.getByText("Carregando…")).toBeInTheDocument();
  });

  test("erro mostra a mensagem e Tentar novamente busca outra vez", async () => {
    const spy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse(500))
      .mockResolvedValueOnce(jsonResponse(200, { items: [summary()] }));
    renderOrders();

    expect(
      await screen.findByText(
        "Não foi possível carregar os seus Pedidos. Tente novamente.",
      ),
    ).toBeInTheDocument();
    await userEvent.click(
      screen.getByRole("button", { name: "Tentar novamente" }),
    );

    expect(await screen.findByText("Guia de saúde")).toBeInTheDocument();
    expect(spy).toHaveBeenCalledTimes(2);
  });

  test("403 mostra o erro e não navega", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(403));
    renderOrders();

    expect(
      await screen.findByText(
        "Não foi possível carregar os seus Pedidos. Tente novamente.",
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText("tela de entrada")).toBeNull();
    expect(localStorage.getItem("sapiensia.returnTo")).toBeNull();
  });

  test("401 ao abrir a página substitui a entrada do histórico: Voltar não retorna aos Pedidos", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(401));
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    const router = createMemoryRouter(
      [
        { path: "/", element: <p>tela inicial</p> },
        { path: "/pedidos", element: <OrdersPage /> },
        { path: "/entrar", element: <p>tela de entrada</p> },
      ],
      { initialEntries: ["/", "/pedidos"], initialIndex: 1 },
    );
    render(
      <QueryClientProvider client={client}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    expect(await screen.findByText("tela de entrada")).toBeInTheDocument();
    await act(() => router.navigate(-1));

    expect(await screen.findByText("tela inicial")).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/");
  });

  test("401 leva a /entrar e guarda /pedidos para depois do login", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(401));
    renderOrders();

    expect(await screen.findByText("tela de entrada")).toBeInTheDocument();
    const stored = JSON.parse(
      localStorage.getItem("sapiensia.returnTo") ?? "null",
    );
    expect(stored.path).toBe("/pedidos");
  });

  test("SEC-011: a lista de Pedidos não fica no cache depois de sair da página", async () => {
    mockItems([summary()]);
    const { client, unmount } = renderOrders();

    expect(await screen.findByText("Guia de saúde")).toBeInTheDocument();
    expect(client.getQueryData(["orders"])).toBeDefined();
    unmount();

    await vi.waitFor(() =>
      expect(
        client.getQueryCache().find({ queryKey: ["orders"] }),
      ).toBeUndefined(),
    );
  });

  test("o título da aba é Meus Pedidos | SapiensIA e o foco vai para o h1", async () => {
    mockItems([]);
    renderOrders();

    const heading = await screen.findByRole("heading", {
      name: "Meus Pedidos",
    });
    expect(document.title).toBe("Meus Pedidos | SapiensIA");
    await vi.waitFor(() => expect(document.activeElement).toBe(heading));
  });
});
