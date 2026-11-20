import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import {
  afterEach,
  describe,
  expect,
  type MockInstance,
  test,
  vi,
} from "vitest";
import { jsonResponse } from "@/features/products/testing";
import BuyWithPix from "./BuyWithPix";

const PRODUCT_ID = "3f2b8c1e-4d5a-4b6c-9d7e-1a2b3c4d5e6f";
const ORDER_ID = "9a8b7c6d-1111-4222-8333-444455556666";
const VALID_CPF = "529.982.247-25";

function renderBuy() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/catalogo/${PRODUCT_ID}`]}>
        <Routes>
          <Route
            path="/catalogo/:id"
            element={<BuyWithPix productId={PRODUCT_ID} />}
          />
          <Route path="/pedidos/:id" element={<p>tela do pedido</p>} />
          <Route path="/entrar" element={<p>tela de entrada</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function setCsrfCookie(value: string) {
  // biome-ignore lint/suspicious/noDocumentCookie: apiFetch reads the CSRF token from document.cookie
  document.cookie = `XSRF-TOKEN=${value}`;
}

function postBodies(spy: MockInstance<typeof fetch>) {
  return spy.mock.calls
    .filter(([url]) => String(url) === "/api/orders")
    .map(([, init]) => JSON.parse(String(init?.body)));
}

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
  sessionStorage.clear();
  setCsrfCookie("; max-age=0");
});

async function click(name: string) {
  await userEvent.click(screen.getByRole("button", { name }));
}

describe("BuyWithPix", () => {
  test("FR-019: Cliente com CPF já conhecido vai direto à tela do Pedido", async () => {
    setCsrfCookie("t");
    const spy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(jsonResponse(201, { id: ORDER_ID }));
    renderBuy();

    await click("Comprar com Pix");

    expect(await screen.findByText("tela do pedido")).toBeInTheDocument();
    expect(postBodies(spy)).toEqual([{ productId: PRODUCT_ID }]);
  });

  test("SEC-033: na primeira compra mostra o bloco do CPF com a explicação e envia o CPF só depois", async () => {
    setCsrfCookie("t");
    const spy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(
        jsonResponse(400, { errors: { cpf: "CPF_REQUIRED" } }),
      )
      .mockResolvedValue(jsonResponse(201, { id: ORDER_ID }));
    renderBuy();

    await click("Comprar com Pix");

    expect(
      await screen.findByRole("heading", { name: "Informe seu CPF" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "O CPF é exigido para gerar a cobrança por Pix. Ele é enviado ao Asaas, que processa o pagamento, e não fica guardado na SapiensIA.",
      ),
    ).toBeInTheDocument();
    expect(screen.getByText("Digite os 11 números.")).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText("CPF"), VALID_CPF);
    await click("Gerar Pix");

    expect(await screen.findByText("tela do pedido")).toBeInTheDocument();
    expect(postBodies(spy)).toEqual([
      { productId: PRODUCT_ID },
      { productId: PRODUCT_ID, cpf: "52998224725" },
    ]);
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  test("SEC-033: CPF inválido mostra o erro no campo e não chama a API", async () => {
    setCsrfCookie("t");
    const spy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(
        jsonResponse(400, { errors: { cpf: "CPF_REQUIRED" } }),
      );
    renderBuy();
    await click("Comprar com Pix");
    await screen.findByLabelText("CPF");

    await click("Gerar Pix");
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Informe o CPF.",
    );

    await userEvent.type(screen.getByLabelText("CPF"), "123");
    await click("Gerar Pix");
    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent(
        "CPF inválido. Confira os números.",
      ),
    );
    expect(screen.getByLabelText("CPF")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
    expect(spy).toHaveBeenCalledTimes(1);
  });

  test("CPF recusado pelo servidor mostra o mesmo erro", async () => {
    setCsrfCookie("t");
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(
        jsonResponse(400, { errors: { cpf: "CPF_REQUIRED" } }),
      )
      .mockResolvedValue(jsonResponse(400, { errors: { cpf: "CPF_INVALID" } }));
    renderBuy();
    await click("Comprar com Pix");
    await userEvent.type(await screen.findByLabelText("CPF"), VALID_CPF);
    await click("Gerar Pix");

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "CPF inválido. Confira os números.",
    );
  });

  test("Visitante é levado a /entrar e o caminho do Produto fica guardado", async () => {
    setCsrfCookie("t");
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(401));
    renderBuy();

    await click("Comprar com Pix");

    expect(await screen.findByText("tela de entrada")).toBeInTheDocument();
    const stored = JSON.parse(
      localStorage.getItem("sapiensia.returnTo") as string,
    );
    expect(stored.path).toBe(`/catalogo/${PRODUCT_ID}`);
    expect(typeof stored.at).toBe("number");
  });

  test.each([
    [
      502,
      { errors: { payment: "PAYMENT_PROVIDER_UNAVAILABLE" } },
      "Não foi possível gerar o Pix agora. Tente novamente em alguns minutos.",
    ],
    [
      409,
      { errors: { order: "ORDER_IN_PROGRESS" } },
      "Já existe um Pix sendo gerado para você. Aguarde alguns segundos e tente novamente.",
    ],
    [
      409,
      { errors: { order: "ALREADY_OWNED" } },
      "Você já tem Acesso a este Produto.",
    ],
    [
      409,
      { errors: { order: "REFUND_IN_PROGRESS" } },
      "Você pediu o Reembolso deste Produto. Depois que o valor for devolvido, você pode comprar de novo.",
    ],
    [404, undefined, "Este Produto não está mais disponível."],
    [500, {}, "Não foi possível concluir agora. Tente novamente."],
  ])(
    "FR-024: mostra a mensagem de cada erro (%i)",
    async (status, body, message) => {
      setCsrfCookie("t");
      vi.spyOn(globalThis, "fetch").mockResolvedValue(
        jsonResponse(status, body),
      );
      renderBuy();

      await click("Comprar com Pix");

      expect(await screen.findByRole("alert")).toHaveTextContent(message);
    },
  );

  test("#42: 429 mostra o aviso de muitas tentativas e não o erro genérico", async () => {
    setCsrfCookie("t");
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(429));
    renderBuy();

    await click("Comprar com Pix");

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Muitas tentativas. Aguarde alguns minutos e tente de novo.",
    );
  });

  test('o botão fica desabilitado e diz "Gerando o Pix…" enquanto envia', async () => {
    setCsrfCookie("t");
    vi.spyOn(globalThis, "fetch").mockReturnValue(new Promise(() => {}));
    renderBuy();

    await click("Comprar com Pix");

    const button = await screen.findByRole("button", {
      name: "Gerando o Pix…",
    });
    expect(button).toBeDisabled();
  });

  test("o foco vai para o campo do CPF quando o bloco abre", async () => {
    setCsrfCookie("t");
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(400, { errors: { cpf: "CPF_REQUIRED" } }),
    );
    renderBuy();

    await click("Comprar com Pix");

    await waitFor(() => expect(screen.getByLabelText("CPF")).toHaveFocus());
  });
});
