import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, test, vi } from "vitest";
import {
  EDIT_ONLY,
  jsonResponse,
  mockApi,
  normalizeSpaces,
  product,
  renderProducts,
} from "./testing";

const BASE = "/api/admin/products";
const CONFLICT =
  "Este Produto foi alterado em outra janela. O que você digitou não foi salvo. Use Recarregar para ver a versão atual.";
const FORBIDDEN = "Você não tem permissão para fazer esta alteração.";
const UNAVAILABLE =
  "O sistema está indisponível no momento e nada foi salvo. Tente novamente em alguns instantes.";
const UNEXPECTED = "Não foi possível salvar agora. Tente novamente.";

afterEach(() => {
  vi.restoreAllMocks();
});

function bodyOf(init?: RequestInit) {
  return JSON.parse(String(init?.body));
}

function dataBlock() {
  return screen.getByRole("region", { name: "Dados do produto" });
}

function priceBlock() {
  return screen.getByRole("region", { name: "Preço" });
}

describe("ProductFormPage, create", () => {
  async function fillValid(user: ReturnType<typeof userEvent.setup>) {
    await screen.findByRole("heading", { name: "Novo Produto" });
    await user.type(screen.getByLabelText("Título"), "Guia novo");
    await user.type(screen.getByLabelText("Descrição"), "Texto longo");
    await user.selectOptions(screen.getByLabelText("Categoria"), "Finanças");
    await user.type(screen.getByLabelText("Valor (R$)"), "19,90");
  }

  test("renders the two blocks, texts and help", async () => {
    mockApi(() => undefined);
    renderProducts("/painel/produtos/novo");

    expect(
      await screen.findByRole("heading", { name: "Novo Produto" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "Este Produto começa como rascunho: os clientes ainda não conseguem vê-lo nem comprá-lo.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Dados do produto" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Preço" })).toBeInTheDocument();
    expect(screen.getByLabelText("Título")).toHaveAccessibleDescription(
      "Até 120 caracteres.",
    );
    expect(screen.getByLabelText("Descrição")).toHaveAccessibleDescription(
      "Até 5.000 caracteres.",
    );
    expect(screen.getByLabelText("Valor (R$)")).toHaveAccessibleDescription(
      "Use vírgula nos centavos. Exemplo: 19,90. O mínimo é R$ 5,00 e o máximo é R$ 9.999,99.",
    );
    const options = within(screen.getByLabelText("Categoria"))
      .getAllByRole("option")
      .map((o) => o.textContent);
    expect(options).toEqual([
      "Selecione uma categoria",
      "Saúde",
      "Direito",
      "Finanças",
      "Segurança",
      "Educação",
      "Tecnologia",
      "Negócios",
      "Outros",
    ]);
    expect(screen.getByRole("link", { name: "Cancelar" })).toHaveAttribute(
      "href",
      "/painel/produtos",
    );
    expect(
      screen.queryByText(/Mudar o preço vale só para Pedidos novos/),
    ).not.toBeInTheDocument();
  });

  test("empty submit shows a message per field and sends nothing", async () => {
    const spy = mockApi(() => undefined);
    renderProducts("/painel/produtos/novo");
    const user = userEvent.setup();

    await user.click(
      await screen.findByRole("button", { name: "Criar Produto" }),
    );

    const title = screen.getByLabelText("Título");
    expect(await screen.findByText("Digite o título.")).toHaveAttribute(
      "role",
      "alert",
    );
    expect(title).toBeInvalid();
    expect(title).toHaveAccessibleDescription(
      expect.stringContaining("Digite o título."),
    );
    expect(screen.getByText("Digite a descrição.")).toBeInTheDocument();
    expect(screen.getByText("Selecione uma categoria.")).toBeInTheDocument();
    expect(screen.getByText("Digite o preço.")).toBeInTheDocument();
    expect(spy.mock.calls.some(([, init]) => init?.method === "POST")).toBe(
      false,
    );
  });

  test.each([
    ["49.90", "Digite um preço válido, como 19,90."],
    ["4,99", "O preço mínimo é R$ 5,00."],
    ["10000", "O preço máximo é R$ 9.999,99."],
  ])(
    "price %j is refused with its message and nothing is sent",
    async (typed, message) => {
      const spy = mockApi(() => undefined);
      renderProducts("/painel/produtos/novo");
      const user = userEvent.setup();

      await fillValid(user);
      const price = screen.getByLabelText("Valor (R$)");
      await user.clear(price);
      await user.type(price, typed);
      await user.click(screen.getByRole("button", { name: "Criar Produto" }));

      expect(await screen.findByText(message)).toHaveAttribute("role", "alert");
      expect(spy.mock.calls.some(([, init]) => init?.method === "POST")).toBe(
        false,
      );
    },
  );

  test("creates with integer cents, then shows the list with the notice", async () => {
    const posts: unknown[] = [];
    mockApi((url, init) => {
      if (url === BASE && init?.method === "POST") {
        posts.push(init.body);
        return jsonResponse(201, product({ id: "new" }));
      }
      if (url === `${BASE}?page=0`)
        return jsonResponse(200, { items: [], page: 0, totalPages: 0 });
      return undefined;
    });
    renderProducts("/painel/produtos/novo");
    const user = userEvent.setup();

    await fillValid(user);
    await user.click(screen.getByRole("button", { name: "Criar Produto" }));

    expect(await screen.findByText("Produto criado.")).toBeInTheDocument();
    expect(posts).toHaveLength(1);
    const body = JSON.parse(String(posts[0]));
    expect(body).toEqual({
      title: "Guia novo",
      description: "Texto longo",
      category: "FINANCE",
      priceCents: 1990,
    });
    expect(String(posts[0])).toContain('"priceCents":1990');
  });

  test("a double click creates only one Product and shows Criando…", async () => {
    let posts = 0;
    mockApi((url, init) => {
      if (url === BASE && init?.method === "POST") {
        posts += 1;
        return new Promise<Response>(() => {});
      }
      return undefined;
    });
    renderProducts("/painel/produtos/novo");
    const user = userEvent.setup();

    await fillValid(user);
    await user.dblClick(screen.getByRole("button", { name: "Criar Produto" }));

    const sending = await screen.findByRole("button", { name: "Criando…" });
    expect(sending).toBeDisabled();
    expect(posts).toBe(1);
  });

  test("server validation codes become the same field messages", async () => {
    mockApi((url, init) =>
      url === BASE && init?.method === "POST"
        ? jsonResponse(400, {
            errors: {
              title: "TITLE_TOO_LONG",
              description: "DESCRIPTION_INVALID",
              category: "CATEGORY_REQUIRED",
              priceCents: "PRICE_TOO_HIGH",
            },
          })
        : undefined,
    );
    renderProducts("/painel/produtos/novo");
    const user = userEvent.setup();

    await fillValid(user);
    await user.click(screen.getByRole("button", { name: "Criar Produto" }));

    expect(
      await screen.findByText("O título pode ter até 120 caracteres."),
    ).toBeInTheDocument();
    expect(
      screen.getByText("A descrição tem caracteres que não podem ser usados."),
    ).toBeInTheDocument();
    expect(screen.getByText("Selecione uma categoria.")).toBeInTheDocument();
    expect(
      screen.getByText("O preço máximo é R$ 9.999,99."),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Título")).toHaveValue("Guia novo");
  });

  test.each([
    [403, FORBIDDEN],
    [503, UNAVAILABLE],
    [500, UNEXPECTED],
  ])(
    "status %i shows its text and keeps what was typed",
    async (status, text) => {
      mockApi((url, init) =>
        url === BASE && init?.method === "POST"
          ? jsonResponse(status)
          : undefined,
      );
      renderProducts("/painel/produtos/novo");
      const user = userEvent.setup();

      await fillValid(user);
      await user.click(screen.getByRole("button", { name: "Criar Produto" }));

      expect(await screen.findByText(text)).toHaveAttribute("role", "alert");
      expect(screen.getByLabelText("Título")).toHaveValue("Guia novo");
      expect(screen.getByLabelText("Valor (R$)")).toHaveValue("19,90");
      expect(
        screen.getByRole("button", { name: "Criar Produto" }),
      ).toBeEnabled();
    },
  );

  test("401 marks the session as ended and goes to the login", async () => {
    mockApi((url, init) =>
      url === BASE && init?.method === "POST" ? jsonResponse(401) : undefined,
    );
    const { client, sessionKey } = renderProducts("/painel/produtos/novo");
    const user = userEvent.setup();

    await fillValid(user);
    await user.click(screen.getByRole("button", { name: "Criar Produto" }));

    expect(await screen.findByText("tela de entrada")).toBeInTheDocument();
    expect(client.getQueryData(sessionKey)).toBeNull();
  });

  test("without PRODUCTS_EDIT it shows no permission", async () => {
    mockApi(() => undefined, {
      email: "a@b.c",
      capabilities: ["PANEL_ACCESS"],
    });
    renderProducts("/painel/produtos/novo");

    expect(
      await screen.findByText(
        "Você não tem permissão para gerenciar Produtos.",
      ),
    ).toBeInTheDocument();
  });
});

describe("ProductFormPage, edit", () => {
  function api(overrides?: {
    put?: (init?: RequestInit) => Response | Promise<Response>;
    price?: (init?: RequestInit) => Response | Promise<Response>;
    get?: () => Response;
  }) {
    const calls: { url: string; method: string; body: unknown }[] = [];
    mockApi((url, init) => {
      const method = init?.method ?? "GET";
      if (url.startsWith(`${BASE}/p1`)) {
        calls.push({
          url,
          method,
          body: init?.body ? bodyOf(init) : undefined,
        });
      }
      if (url === `${BASE}/p1` && method === "GET")
        return overrides?.get ? overrides.get() : jsonResponse(200, product());
      if (url === `${BASE}/p1` && method === "PUT")
        return overrides?.put
          ? overrides.put(init)
          : jsonResponse(200, product({ version: 4, title: "Título salvo" }));
      if (url === `${BASE}/p1/price` && method === "PUT")
        return overrides?.price
          ? overrides.price(init)
          : jsonResponse(200, product({ version: 5, priceCents: 5990 }));
      return undefined;
    });
    return calls;
  }

  async function open() {
    renderProducts("/painel/produtos/p1");
    await screen.findByRole("heading", { name: "Editar Produto" });
    await screen.findByDisplayValue("Guia de saúde");
  }

  test("loads the Product into both blocks with the help texts", async () => {
    api();
    await open();

    expect(
      screen.getByText(
        "Este Produto está em rascunho: os clientes ainda não conseguem vê-lo nem comprá-lo.",
      ),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Descrição")).toHaveValue("Texto do guia");
    expect(screen.getByLabelText("Categoria")).toHaveValue("HEALTH");
    expect(screen.getByLabelText("Valor (R$)")).toHaveValue("19,90");
    expect(screen.getByLabelText("Valor (R$)")).toHaveAccessibleDescription(
      expect.stringContaining(
        "Mudar o preço vale só para Pedidos novos; Pedidos já feitos mantêm o valor.",
      ),
    );
    expect(screen.getByLabelText("Valor (R$)")).toHaveAccessibleDescription(
      expect.stringContaining("Use vírgula nos centavos."),
    );
    expect(
      screen.getByRole("link", { name: "Voltar para Produtos" }),
    ).toHaveAttribute("href", "/painel/produtos");
    expect(
      screen.getByRole("button", { name: "Salvar dados" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Salvar preço" }),
    ).toBeInTheDocument();
  });

  test("saving the data block sends the version and keeps the typed price; the price then uses the new version", async () => {
    const calls = api();
    await open();
    const user = userEvent.setup();

    const price = screen.getByLabelText("Valor (R$)");
    await user.clear(price);
    await user.type(price, "59,90");
    await user.clear(screen.getByLabelText("Título"));
    await user.type(screen.getByLabelText("Título"), "Título salvo");
    await user.click(screen.getByRole("button", { name: "Salvar dados" }));

    expect(await screen.findByText("Dados salvos.")).toBeInTheDocument();
    expect(calls.find((c) => c.method === "PUT")?.body).toEqual({
      title: "Título salvo",
      description: "Texto do guia",
      category: "HEALTH",
      version: 3,
    });
    expect(screen.getByLabelText("Valor (R$)")).toHaveValue("59,90");

    await user.click(screen.getByRole("button", { name: "Salvar preço" }));

    expect(await screen.findByText("Preço salvo.")).toBeInTheDocument();
    expect(calls.find((c) => c.url.endsWith("/price"))?.body).toEqual({
      priceCents: 5990,
      version: 4,
    });
    expect(screen.getByLabelText("Título")).toHaveValue("Título salvo");
  });

  test("saving the price first keeps typed data and gives the data block the new version", async () => {
    const calls = api();
    await open();
    const user = userEvent.setup();

    const price = screen.getByLabelText("Valor (R$)");
    await user.clear(price);
    await user.type(price, "59,90");
    await user.type(screen.getByLabelText("Título"), " editado");
    await user.click(screen.getByRole("button", { name: "Salvar preço" }));
    await screen.findByText("Preço salvo.");
    expect(screen.getByLabelText("Título")).toHaveValue(
      "Guia de saúde editado",
    );

    await user.click(screen.getByRole("button", { name: "Salvar dados" }));
    await screen.findByText("Dados salvos.");
    expect(
      calls.find((c) => c.method === "PUT" && !c.url.endsWith("/price"))?.body,
    ).toMatchObject({
      title: "Guia de saúde editado",
      version: 5,
    });
  });

  test("shows Salvando… and disables both buttons while a block saves", async () => {
    api({ put: () => new Promise<Response>(() => {}) });
    await open();
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Salvar dados" }));

    expect(
      await within(dataBlock()).findByRole("button", { name: "Salvando…" }),
    ).toBeDisabled();
    expect(
      within(priceBlock()).getByRole("button", { name: "Salvar preço" }),
    ).toBeDisabled();
  });

  test("409 appears in the failed block only, keeps the text and Recarregar fetches the fresh Product", async () => {
    let gets = 0;
    api({
      get: () => {
        gets += 1;
        return jsonResponse(
          200,
          gets === 1
            ? product()
            : product({ version: 9, title: "Versão do servidor" }),
        );
      },
      put: () => jsonResponse(409),
    });
    await open();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText("Título"), " digitado");
    await user.click(screen.getByRole("button", { name: "Salvar dados" }));

    expect(await within(dataBlock()).findByText(CONFLICT)).toHaveAttribute(
      "role",
      "alert",
    );
    expect(within(priceBlock()).queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Título")).toHaveValue(
      "Guia de saúde digitado",
    );

    await user.click(
      within(dataBlock()).getByRole("button", { name: "Recarregar" }),
    );

    await waitFor(() =>
      expect(screen.getByLabelText("Título")).toHaveValue("Versão do servidor"),
    );
    expect(screen.queryByText(CONFLICT)).not.toBeInTheDocument();
  });

  test("a failed Recarregar keeps the conflict warning, the typed text and the form", async () => {
    let gets = 0;
    api({
      get: () => {
        gets += 1;
        return gets === 1 ? jsonResponse(200, product()) : jsonResponse(500);
      },
      put: () => jsonResponse(409),
    });
    await open();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText("Título"), " digitado");
    await user.click(screen.getByRole("button", { name: "Salvar dados" }));
    await within(dataBlock()).findByText(CONFLICT);

    await user.click(
      within(dataBlock()).getByRole("button", { name: "Recarregar" }),
    );

    await waitFor(() => expect(gets).toBe(2));
    expect(await within(dataBlock()).findByText(CONFLICT)).toBeInTheDocument();
    expect(screen.getByLabelText("Título")).toHaveValue(
      "Guia de saúde digitado",
    );
    expect(
      screen.queryByText(
        "Não foi possível carregar o Produto agora. Tente novamente.",
      ),
    ).not.toBeInTheDocument();
  });

  test.each([
    [403, FORBIDDEN],
    [503, UNAVAILABLE],
    [500, UNEXPECTED],
  ])(
    "price block status %i shows inside the price block without clearing it",
    async (status, text) => {
      api({ price: () => jsonResponse(status) });
      await open();
      const user = userEvent.setup();

      const price = screen.getByLabelText("Valor (R$)");
      await user.clear(price);
      await user.type(price, "59,90");
      await user.click(screen.getByRole("button", { name: "Salvar preço" }));

      expect(await within(priceBlock()).findByText(text)).toHaveAttribute(
        "role",
        "alert",
      );
      expect(within(dataBlock()).queryByRole("alert")).not.toBeInTheDocument();
      expect(screen.getByLabelText("Valor (R$)")).toHaveValue("59,90");
    },
  );

  test.each([
    [403, FORBIDDEN],
    [503, UNAVAILABLE],
  ])(
    "data block status %i shows inside the data block without clearing it",
    async (status, text) => {
      api({ put: () => jsonResponse(status) });
      await open();
      const user = userEvent.setup();

      await user.type(screen.getByLabelText("Título"), "!");
      await user.click(screen.getByRole("button", { name: "Salvar dados" }));

      expect(await within(dataBlock()).findByText(text)).toHaveAttribute(
        "role",
        "alert",
      );
      expect(within(priceBlock()).queryByRole("alert")).not.toBeInTheDocument();
      expect(screen.getByLabelText("Título")).toHaveValue("Guia de saúde!");
    },
  );

  test("409 on the price block shows the conflict text with Recarregar there", async () => {
    api({ price: () => jsonResponse(409) });
    await open();
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Salvar preço" }));

    expect(await within(priceBlock()).findByText(CONFLICT)).toBeInTheDocument();
    expect(
      within(priceBlock()).getByRole("button", { name: "Recarregar" }),
    ).toBeInTheDocument();
  });

  test("a price below the minimum is refused client side", async () => {
    const calls = api();
    await open();
    const user = userEvent.setup();

    const price = screen.getByLabelText("Valor (R$)");
    await user.clear(price);
    await user.type(price, "49.90");
    await user.click(screen.getByRole("button", { name: "Salvar preço" }));

    expect(
      await screen.findByText("Digite um preço válido, como 19,90."),
    ).toBeInTheDocument();
    expect(calls.some((c) => c.url.endsWith("/price"))).toBe(false);
  });

  test("401 on a save marks the session as ended", async () => {
    api({ put: () => jsonResponse(401) });
    const { client, sessionKey } = renderProducts("/painel/produtos/p1");
    await screen.findByDisplayValue("Guia de saúde");
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Salvar dados" }));

    await waitFor(() => expect(client.getQueryData(sessionKey)).toBeNull());
  });

  test("without PRODUCTS_PRICE the price is read-only with the notice", async () => {
    mockApi(
      (url) =>
        url === `${BASE}/p1` ? jsonResponse(200, product()) : undefined,
      EDIT_ONLY,
    );
    renderProducts("/painel/produtos/p1");
    await screen.findByDisplayValue("Guia de saúde");

    const block = priceBlock();
    expect(within(block).queryByRole("textbox")).not.toBeInTheDocument();
    expect(within(block).queryByRole("button")).not.toBeInTheDocument();
    expect(normalizeSpaces(block.textContent)).toContain("R$ 19,90");
    expect(
      within(block).getByText("Você não tem permissão para alterar o preço."),
    ).toBeInTheDocument();
  });

  test("404 shows Produto não encontrado.", async () => {
    mockApi((url) => (url === `${BASE}/p1` ? jsonResponse(404) : undefined));
    renderProducts("/painel/produtos/p1");

    expect(
      await screen.findByText("Produto não encontrado."),
    ).toBeInTheDocument();
  });

  test("other load errors show the load message", async () => {
    mockApi((url) => (url === `${BASE}/p1` ? jsonResponse(500) : undefined));
    renderProducts("/painel/produtos/p1");

    expect(
      await screen.findByText(
        "Não foi possível carregar o Produto agora. Tente novamente.",
      ),
    ).toHaveAttribute("role", "alert");
  });

  test("401 loading sends the user to the login", async () => {
    mockApi((url) => (url === `${BASE}/p1` ? jsonResponse(401) : undefined));
    renderProducts("/painel/produtos/p1");

    expect(await screen.findByText("tela de entrada")).toBeInTheDocument();
  });

  test("opening the edit page again refetches even with fresh cached data", async () => {
    const calls = api();
    const first = renderProducts("/painel/produtos/p1");
    await screen.findByDisplayValue("Guia de saúde");
    first.unmount();

    renderProducts("/painel/produtos/p1", first.client);
    await screen.findByDisplayValue("Guia de saúde");

    await waitFor(() =>
      expect(
        calls.filter((c) => c.url === `${BASE}/p1` && c.method === "GET"),
      ).toHaveLength(2),
    );
  });

  test("shows Carregando… while the Product loads", () => {
    vi.spyOn(globalThis, "fetch").mockReturnValue(new Promise(() => {}));
    renderProducts("/painel/produtos/p1");

    expect(screen.getByText("Carregando…")).toBeInTheDocument();
  });
});
