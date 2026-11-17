import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, describe, expect, test, vi } from "vitest";
import AdminOrdersPage from "./AdminOrdersPage";

const SESSION_URL = "/api/admin/sessions/current";
const LIST = "/api/admin/orders?page=0";

function jsonResponse(status: number, body?: unknown) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
  });
}

function order(overrides: Record<string, unknown> = {}) {
  return {
    id: "0a1b2c3d-1111-2222-3333-444455556666",
    productTitle: "Guia de saúde",
    priceCents: 2990,
    status: "PAID",
    customerEmail: "s***@gmail.com",
    createdAt: "2026-11-09T17:32:00Z",
    paidAt: "2026-11-09T17:40:00Z",
    ...overrides,
  };
}

function page(items: unknown[], pageNumber = 0, totalPages = 1) {
  return { items, page: pageNumber, totalPages };
}

function mockApi(
  handler: (url: string) => Response | Promise<Response> | undefined,
  capabilities = ["PANEL_ACCESS", "ORDERS_VIEW"],
) {
  return vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const url = String(input);
    if (url === SESSION_URL) {
      return jsonResponse(200, {
        email: "admin@example.com",
        capabilities,
        oldCodeRequired: true,
      });
    }
    return (await handler(url)) ?? jsonResponse(200, page([]));
  });
}

function renderPage() {
  return render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={["/painel/pedidos"]}>
        <Routes>
          <Route path="/painel/pedidos" element={<AdminOrdersPage />} />
          <Route path="/painel/entrar" element={<p>tela de entrada</p>} />
          <Route path="/painel" element={<p>painel</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function orderCalls(spy: { mock: { calls: unknown[][] } }) {
  return spy.mock.calls
    .map((call) => String(call[0]))
    .filter((url) => url.startsWith("/api/admin/orders"));
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe("AdminOrdersPage", () => {
  test("FR-020 shows the rows with id prefix, value, status, masked e-mail and dates", async () => {
    mockApi((url) =>
      url === LIST
        ? jsonResponse(
            200,
            page([
              order(),
              order({
                id: "ffffffff-1111-2222-3333-444455556666",
                productTitle: "Outro",
                status: "PENDING",
                paidAt: null,
              }),
            ]),
          )
        : undefined,
    );
    renderPage();

    await screen.findByRole("row", { name: /Guia de saúde/ });
    expect(
      screen.getByRole("heading", { name: "Pedidos" }),
    ).toBeInTheDocument();
    expect(
      screen.getAllByRole("columnheader").map((h) => h.textContent),
    ).toEqual([
      "Pedido",
      "Produto",
      "Valor",
      "Estado",
      "Cliente",
      "Criado em",
      "Pago em",
    ]);
    const paid = screen.getByRole("row", { name: /Guia de saúde/ });
    expect(within(paid).getByText("0a1b2c3d")).toBeInTheDocument();
    expect(within(paid).getByText("Pago")).toBeInTheDocument();
    expect(within(paid).getByText("s***@gmail.com")).toBeInTheDocument();
    expect(within(paid).getByText("09/11/2026 14:32")).toBeInTheDocument();
    expect(within(paid).getByText("09/11/2026 14:40")).toBeInTheDocument();
    expect(paid.textContent).toMatch(/R\$\s29,90/);
    const pending = screen.getByRole("row", { name: /Outro/ });
    expect(
      within(pending).getByText("Aguardando pagamento"),
    ).toBeInTheDocument();
    expect(within(pending).getByText("—")).toBeInTheDocument();
  });

  test('FR-020: the filter has "Reembolsado" and the row shows the status', async () => {
    const spy = mockApi(() =>
      jsonResponse(200, page([order({ status: "REFUNDED" })])),
    );
    renderPage();

    const row = await screen.findByRole("row", { name: /Guia de saúde/ });
    expect(within(row).getByText("Reembolsado")).toBeInTheDocument();
    await userEvent.selectOptions(
      screen.getByLabelText("Filtrar por estado"),
      "Reembolsado",
    );
    await waitFor(() =>
      expect(orderCalls(spy)).toContain(
        "/api/admin/orders?status=REFUNDED&page=0",
      ),
    );
  });

  test("shows Carregando… while loading", async () => {
    mockApi((url) =>
      url === LIST ? new Promise<Response>(() => {}) : undefined,
    );
    renderPage();

    expect(await screen.findByRole("status")).toHaveTextContent("Carregando…");
  });

  test("empty without filter and with filter keeps the filter", async () => {
    mockApi(() => undefined);
    renderPage();
    expect(await screen.findByText("Nenhum Pedido ainda.")).toBeInTheDocument();

    await userEvent.selectOptions(
      screen.getByLabelText("Filtrar por estado"),
      "Pago",
    );
    expect(
      await screen.findByText("Nenhum Pedido neste estado."),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Filtrar por estado")).toBeInTheDocument();
  });

  test("error shows the message and Tentar novamente fetches again", async () => {
    let calls = 0;
    mockApi((url) => {
      if (!url.startsWith("/api/admin/orders")) return undefined;
      calls += 1;
      return calls === 1
        ? jsonResponse(500)
        : jsonResponse(200, page([order()]));
    });
    renderPage();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Não foi possível carregar os Pedidos agora. Tente novamente.",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Tentar novamente" }),
    );
    expect(await screen.findByText("Guia de saúde")).toBeInTheDocument();
  });

  test("403 shows the no permission message", async () => {
    mockApi((url) => (url === LIST ? jsonResponse(403) : undefined));
    renderPage();

    expect(
      await screen.findByText("Você não tem permissão para ver os Pedidos."),
    ).toBeInTheDocument();
  });

  test("401 sends the user to the panel login", async () => {
    mockApi((url) => (url === LIST ? jsonResponse(401) : undefined));
    renderPage();

    expect(await screen.findByText("tela de entrada")).toBeInTheDocument();
  });

  test("Próxima asks for page=1", async () => {
    const spy = mockApi((url) =>
      url === LIST
        ? jsonResponse(200, page([order()], 0, 3))
        : jsonResponse(200, page([order()], 1, 3)),
    );
    renderPage();

    await userEvent.click(
      await screen.findByRole("button", { name: "Próxima" }),
    );
    await waitFor(() =>
      expect(orderCalls(spy)).toContain("/api/admin/orders?page=1"),
    );
    expect(await screen.findByText("Página 2 de 3")).toBeInTheDocument();
  });

  test("FR-020 a page past the end shows the empty message and keeps the way back", async () => {
    const spy = mockApi((url) =>
      url === LIST
        ? jsonResponse(200, page([order()], 0, 2))
        : jsonResponse(200, page([], 1, 1)),
    );
    renderPage();

    await userEvent.click(
      await screen.findByRole("button", { name: "Próxima" }),
    );
    expect(await screen.findByText("Nenhum Pedido ainda.")).toBeInTheDocument();
    const previous = screen.getByRole("button", { name: "Anterior" });
    expect(previous).toBeEnabled();
    await userEvent.click(previous);
    await waitFor(() => expect(orderCalls(spy).at(-1)).toBe(LIST));
  });

  test("changing the filter on page 2 asks for status=PAID&page=0", async () => {
    const spy = mockApi(() => jsonResponse(200, page([order()], 0, 3)));
    renderPage();

    await userEvent.click(
      await screen.findByRole("button", { name: "Próxima" }),
    );
    await screen.findByText("Página 2 de 3");
    await userEvent.selectOptions(
      screen.getByLabelText("Filtrar por estado"),
      "Pago",
    );
    await waitFor(() =>
      expect(orderCalls(spy)).toContain("/api/admin/orders?status=PAID&page=0"),
    );
  });

  test("without ORDERS_VIEW shows the message and never calls the Orders API", async () => {
    const spy = mockApi(() => undefined, ["PANEL_ACCESS"]);
    renderPage();

    expect(
      await screen.findByText("Você não tem permissão para ver os Pedidos."),
    ).toBeInTheDocument();
    expect(orderCalls(spy)).toEqual([]);
  });
});
