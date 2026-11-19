import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, describe, expect, test, vi } from "vitest";
import AdminRefundsPage from "./AdminRefundsPage";

const SESSION_URL = "/api/admin/sessions/current";
const OPEN = "/api/admin/refund-requests?status=OPEN&page=0";

function jsonResponse(status: number, body?: unknown) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
  });
}

function refund(overrides: Record<string, unknown> = {}) {
  return {
    orderId: "0a1b2c3d-1111-2222-3333-444455556666",
    productTitle: "Guia de saúde",
    priceCents: 2990,
    customerEmail: "s***@gmail.com",
    reason: "DEFECTIVE_FILE",
    details: null,
    requestedAt: "2026-11-09T17:32:00Z",
    paidAt: "2026-11-09T17:20:00Z",
    downloads: 2,
    otherRefunds: 1,
    chargeId: "pay_abc123",
    refundedAt: null,
    ...overrides,
  };
}

function page(items: unknown[], pageNumber = 0, totalPages = 1) {
  return { items, page: pageNumber, totalPages };
}

function mockApi(
  handler: (url: string) => Response | Promise<Response> | undefined,
  capabilities = ["PANEL_ACCESS", "REFUNDS_VIEW"],
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
      <MemoryRouter initialEntries={["/painel/reembolsos"]}>
        <Routes>
          <Route path="/painel/reembolsos" element={<AdminRefundsPage />} />
          <Route path="/painel/entrar" element={<p>tela de entrada</p>} />
          <Route path="/painel" element={<p>painel</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function refundCalls(spy: { mock: { calls: unknown[][] } }) {
  return spy.mock.calls
    .map((call) => String(call[0]))
    .filter((url) => url.startsWith("/api/admin/refund-requests"));
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe("AdminRefundsPage", () => {
  test("FR-023 shows the 11 columns with the values of the request", async () => {
    mockApi((url) =>
      url === OPEN
        ? jsonResponse(
            200,
            page([
              refund({ details: "<b>x</b> o arquivo não abre" }),
              refund({
                orderId: "ffffffff-1111-2222-3333-444455556666",
                productTitle: "Outro produto",
                reason: "OTHER",
                paidAt: null,
                chargeId: null,
                refundedAt: "2026-11-10T12:05:00Z",
              }),
            ]),
          )
        : undefined,
    );
    renderPage();

    const row = await screen.findByRole("row", { name: /Guia de saúde/ });
    expect(
      screen.getByRole("heading", { name: "Reembolsos solicitados" }),
    ).toBeInTheDocument();
    expect(
      screen.getAllByRole("columnheader").map((h) => h.textContent),
    ).toEqual([
      "Pedido",
      "Produto",
      "Valor",
      "Cliente",
      "Motivo",
      "Solicitado em",
      "Pago em",
      "Downloads do Produto",
      "Outros Reembolsos em 12 meses",
      "Cobrança no Asaas",
      "Situação",
    ]);
    expect(within(row).getByText("0a1b2c3d")).toBeInTheDocument();
    expect(row.textContent).toMatch(/R\$\s29,90/);
    expect(within(row).getByText("s***@gmail.com")).toBeInTheDocument();
    expect(within(row).getByText("Arquivo com defeito")).toBeInTheDocument();
    expect(
      within(row).getByText(
        "Comentário do Cliente: <b>x</b> o arquivo não abre",
      ),
    ).toBeInTheDocument();
    expect(within(row).getByText("09/11/2026 14:32")).toBeInTheDocument();
    expect(within(row).getByText("09/11/2026 14:20")).toBeInTheDocument();
    expect(within(row).getByText("2")).toBeInTheDocument();
    expect(within(row).getByText("1")).toBeInTheDocument();
    expect(within(row).getByText("pay_abc123")).toBeInTheDocument();
    expect(within(row).getByText("Aguardando devolução")).toBeInTheDocument();

    const refunded = screen.getByRole("row", { name: /Outro produto/ });
    expect(within(refunded).getByText("Outro")).toBeInTheDocument();
    expect(within(refunded).getAllByText("—")).toHaveLength(2);
    expect(
      within(refunded).getByText("Devolvido em 10/11/2026 09:05"),
    ).toBeInTheDocument();
    expect(refunded.textContent).not.toContain("Comentário do Cliente");
  });

  test("shows the orientation and starts the filter on Aguardando devolução", async () => {
    const spy = mockApi(() => undefined);
    renderPage();

    expect(
      await screen.findByText(/estorne o valor inteiro da cobrança/),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Filtrar por situação")).toHaveValue("OPEN");
    await waitFor(() => expect(refundCalls(spy)).toEqual([OPEN]));
  });

  test("changing the filter asks for status=REFUNDED and for no status", async () => {
    const spy = mockApi(() => undefined);
    renderPage();
    await screen.findByText("Nenhum Reembolso aguardando devolução.");

    await userEvent.selectOptions(
      screen.getByLabelText("Filtrar por situação"),
      "Devolvidos",
    );
    expect(
      await screen.findByText("Nenhum Reembolso devolvido."),
    ).toBeInTheDocument();
    await userEvent.selectOptions(
      screen.getByLabelText("Filtrar por situação"),
      "Todos",
    );
    expect(
      await screen.findByText("Nenhum Reembolso solicitado ainda."),
    ).toBeInTheDocument();
    expect(refundCalls(spy)).toEqual([
      OPEN,
      "/api/admin/refund-requests?status=REFUNDED&page=0",
      "/api/admin/refund-requests?page=0",
    ]);
    expect(screen.getByText(/estorne o valor inteiro/)).toBeInTheDocument();
  });

  test("shows Carregando… while loading", async () => {
    mockApi((url) =>
      url === OPEN ? new Promise<Response>(() => {}) : undefined,
    );
    renderPage();

    expect(await screen.findByRole("status")).toHaveTextContent("Carregando…");
  });

  test("error shows the message and Tentar novamente fetches again", async () => {
    let calls = 0;
    mockApi((url) => {
      if (!url.startsWith("/api/admin/refund-requests")) return undefined;
      calls += 1;
      return calls === 1
        ? jsonResponse(500)
        : jsonResponse(200, page([refund()]));
    });
    renderPage();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Não foi possível carregar os Reembolsos agora. Tente novamente.",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Tentar novamente" }),
    );
    expect(await screen.findByText("Guia de saúde")).toBeInTheDocument();
  });

  test("403 shows the no permission message", async () => {
    mockApi((url) => (url === OPEN ? jsonResponse(403) : undefined));
    renderPage();

    expect(
      await screen.findByText("Você não tem permissão para ver os Reembolsos."),
    ).toBeInTheDocument();
  });

  test("401 sends the user to the panel login", async () => {
    mockApi((url) => (url === OPEN ? jsonResponse(401) : undefined));
    renderPage();

    expect(await screen.findByText("tela de entrada")).toBeInTheDocument();
  });

  test("Próxima asks for page=1 and a filter change goes back to page 0", async () => {
    const spy = mockApi(() => jsonResponse(200, page([refund()], 0, 3)));
    renderPage();

    await userEvent.click(
      await screen.findByRole("button", { name: "Próxima" }),
    );
    await waitFor(() =>
      expect(refundCalls(spy)).toContain(
        "/api/admin/refund-requests?status=OPEN&page=1",
      ),
    );
    await screen.findByText("Página 2 de 3");
    await userEvent.selectOptions(
      screen.getByLabelText("Filtrar por situação"),
      "Todos",
    );
    await waitFor(() =>
      expect(refundCalls(spy)).toContain("/api/admin/refund-requests?page=0"),
    );
  });

  test("without REFUNDS_VIEW shows the message and never calls the API", async () => {
    const spy = mockApi(() => undefined, ["PANEL_ACCESS"]);
    renderPage();

    expect(
      await screen.findByText("Você não tem permissão para ver os Reembolsos."),
    ).toBeInTheDocument();
    expect(refundCalls(spy)).toEqual([]);
  });
});
