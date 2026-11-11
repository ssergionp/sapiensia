import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, test, vi } from "vitest";
import type { Product } from "./schemas";
import {
  adminBody,
  jsonResponse,
  mockApi,
  product,
  renderProducts,
} from "./testing";

const PRODUCT_URL = "/api/admin/products/p1";
const PUBLICATION_URL = "/api/admin/products/p1/publication";
const FILE = {
  sizeBytes: 3_565_158,
  pageCount: 120,
  uploadedAt: "2026-11-09T17:32:00Z",
};
const PUBLISHED_AT = "2026-11-10T12:00:00Z";

const draftWithFile = () => product({ file: FILE });
const published = () =>
  product({ file: FILE, status: "PUBLISHED", firstPublishedAt: PUBLISHED_AT });
const unpublished = () =>
  product({
    file: FILE,
    status: "UNPUBLISHED",
    firstPublishedAt: PUBLISHED_AT,
  });

function apiWith(
  loaded: Product,
  publication?: (init: RequestInit | undefined) => Response | Promise<Response>,
  session?: unknown,
) {
  return mockApi((url, init) => {
    if (url === PRODUCT_URL) return jsonResponse(200, loaded);
    if (url === PUBLICATION_URL && publication) return publication(init);
  }, session);
}

function publicationCalls(spy: ReturnType<typeof mockApi>) {
  return spy.mock.calls.filter(([url]) => String(url) === PUBLICATION_URL);
}

// The router test helper renders an <output> (role status) too, so the result is found by its text.
async function result(text: string) {
  const element = await screen.findByText(text);
  expect(element).toHaveAttribute("role", "status");
  return element;
}

async function open() {
  renderProducts("/painel/produtos/p1");
  await screen.findByRole("heading", { name: "Publicação" });
}

describe("ProductPublicationSection", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  test("FR-011 draft without a file: Publicar is disabled and the hint shows", async () => {
    const spy = apiWith(product());
    await open();
    expect(screen.getByRole("button", { name: "Publicar" })).toBeDisabled();
    expect(
      screen.getByText("Envie o Arquivo do produto para poder publicar."),
    ).toBeInTheDocument();
    expect(publicationCalls(spy)).toHaveLength(0);
  });

  test("FR-011 draft with a file asks for confirmation; Cancelar does not call the API", async () => {
    const spy = apiWith(draftWithFile());
    await open();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Publicar" }));
    expect(
      screen.getByText(
        "Publicar este Produto? Ele vai aparecer no Catálogo. Depois da primeira publicação, o arquivo só poderá ser trocado com nova autenticação, que ainda não está disponível.",
      ),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Cancelar" }));
    expect(
      screen.queryByRole("button", { name: "Confirmar publicação" }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Publicar" })).toBeEnabled();
    expect(publicationCalls(spy)).toHaveLength(0);
  });

  test("the confirmation question takes focus and Tab reaches Confirmar publicação", async () => {
    apiWith(draftWithFile());
    await open();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Publicar" }));
    expect(
      screen.getByText(/^Publicar este Produto[?] Ele vai aparecer/),
    ).toHaveFocus();
    await user.tab();
    expect(
      screen.getByRole("button", { name: "Confirmar publicação" }),
    ).toHaveFocus();
  });

  test("Cancelar returns focus to Publicar", async () => {
    apiWith(draftWithFile());
    await open();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Publicar" }));
    await user.click(screen.getByRole("button", { name: "Cancelar" }));
    expect(screen.getByRole("button", { name: "Publicar" })).toHaveFocus();
  });

  test("FR-011 after a refused publication the Product is reloaded and the screen follows it", async () => {
    let current = draftWithFile();
    const spy = mockApi((url, init) => {
      if (url === PRODUCT_URL) return jsonResponse(200, current);
      if (url === PUBLICATION_URL && init?.method === "PUT") {
        current = product();
        return jsonResponse(409, { errors: { file: "FILE_REQUIRED" } });
      }
    });
    await open();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Publicar" }));
    await user.click(
      screen.getByRole("button", { name: "Confirmar publicação" }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Envie o Arquivo do produto antes de publicar.",
    );
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Publicar" })).toBeDisabled(),
    );
    expect(
      screen.getByText("Envie o Arquivo do produto para poder publicar."),
    ).toBeInTheDocument();
    expect(
      spy.mock.calls.filter(([url]) => String(url) === PRODUCT_URL).length,
    ).toBeGreaterThan(1);
  });

  test("focus goes to the Publicação heading while the request runs, then to the result", async () => {
    let release: (r: Response) => void = () => {};
    apiWith(
      draftWithFile(),
      () =>
        new Promise<Response>((resolve) => {
          release = resolve;
        }),
    );
    await open();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Publicar" }));
    await user.click(
      screen.getByRole("button", { name: "Confirmar publicação" }),
    );
    expect(screen.getByRole("heading", { name: "Publicação" })).toHaveFocus();
    release(jsonResponse(200, published()));
    expect(await result("Produto publicado.")).toHaveFocus();
  });

  test.each([
    ["Despublicar", published],
    ["Publicar de novo", unpublished],
  ])("focus stays in the section while %s runs", async (label, loaded) => {
    apiWith(loaded(), () => new Promise<Response>(() => {}));
    await open();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: label }));
    expect(screen.getByRole("heading", { name: "Publicação" })).toHaveFocus();
  });

  test("FR-011 Confirmar publicação sends PUT, shows the result and moves focus to it", async () => {
    const spy = apiWith(draftWithFile(), (init) =>
      init?.method === "PUT"
        ? jsonResponse(200, published())
        : jsonResponse(500),
    );
    await open();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Publicar" }));
    await user.click(
      screen.getByRole("button", { name: "Confirmar publicação" }),
    );
    const status = await result("Produto publicado.");
    await waitFor(() => expect(status).toHaveFocus());
    expect(publicationCalls(spy)).toHaveLength(1);
    expect(publicationCalls(spy)[0][1]?.method).toBe("PUT");
    expect(
      screen.getByRole("button", { name: "Despublicar" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "Este Produto está publicado: aparece no Catálogo e pode ser comprado.",
      ),
    ).toBeInTheDocument();
  });

  test("FR-016 published: Despublicar sends DELETE and shows the result", async () => {
    const spy = apiWith(published(), (init) =>
      init?.method === "DELETE"
        ? jsonResponse(200, unpublished())
        : jsonResponse(500),
    );
    await open();
    expect(
      screen.getByText("Quem já comprou continua com acesso."),
    ).toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Despublicar" }));
    const status = await result("Produto despublicado.");
    await waitFor(() => expect(status).toHaveFocus());
    expect(publicationCalls(spy)[0][1]?.method).toBe("DELETE");
    expect(
      screen.getByRole("button", { name: "Publicar de novo" }),
    ).toBeInTheDocument();
  });

  test("FR-016 unpublished: Publicar de novo sends PUT without confirmation", async () => {
    const spy = apiWith(unpublished(), () => jsonResponse(200, published()));
    await open();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Publicar de novo" }));
    await result("Produto publicado.");
    expect(publicationCalls(spy)).toHaveLength(1);
    expect(publicationCalls(spy)[0][1]?.method).toBe("PUT");
  });

  test("the button is disabled and says Publicando… while the request runs", async () => {
    let release: (r: Response) => void = () => {};
    apiWith(
      unpublished(),
      () =>
        new Promise<Response>((resolve) => {
          release = resolve;
        }),
    );
    await open();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Publicar de novo" }));
    expect(
      await screen.findByRole("button", { name: "Publicando…" }),
    ).toBeDisabled();
    release(jsonResponse(200, published()));
    await result("Produto publicado.");
  });

  test("the button is disabled and says Despublicando… while the request runs", async () => {
    let release: (r: Response) => void = () => {};
    apiWith(
      published(),
      () =>
        new Promise<Response>((resolve) => {
          release = resolve;
        }),
    );
    await open();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Despublicar" }));
    expect(
      await screen.findByRole("button", { name: "Despublicando…" }),
    ).toBeDisabled();
    release(jsonResponse(200, unpublished()));
    await result("Produto despublicado.");
  });

  test.each([
    [
      "409 FILE_REQUIRED",
      () => jsonResponse(409, { errors: { file: "FILE_REQUIRED" } }),
      "Envie o Arquivo do produto antes de publicar.",
    ],
    [
      "403",
      () => jsonResponse(403),
      "Você não tem permissão para publicar ou despublicar este Produto.",
    ],
    ["404", () => jsonResponse(404), "Produto não encontrado."],
    [
      "503",
      () => jsonResponse(503),
      "O sistema está indisponível no momento e nada foi alterado. Tente novamente em alguns instantes.",
    ],
    [
      "500",
      () => jsonResponse(500),
      "Não foi possível alterar a publicação agora. Tente novamente.",
    ],
  ])(
    "%s shows its text as an alert with focus",
    async (_name, answer, text) => {
      apiWith(unpublished(), answer);
      await open();
      const user = userEvent.setup();
      await user.click(
        screen.getByRole("button", { name: "Publicar de novo" }),
      );
      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent(text);
      await waitFor(() => expect(alert).toHaveFocus());
      expect(
        screen.getByRole("button", { name: "Publicar de novo" }),
      ).toBeEnabled();
    },
  );

  test("without PRODUCTS_PUBLISH the section does not show", async () => {
    apiWith(
      draftWithFile(),
      undefined,
      adminBody(["PANEL_ACCESS", "PRODUCTS_EDIT"]),
    );
    renderProducts("/painel/produtos/p1");
    await screen.findByRole("heading", { name: "Arquivo do produto" });
    expect(
      screen.queryByRole("heading", { name: "Publicação" }),
    ).not.toBeInTheDocument();
  });

  test.each([
    [
      "DRAFT",
      "Este Produto está em rascunho: os clientes ainda não conseguem vê-lo nem comprá-lo.",
    ],
    [
      "UNPUBLISHED",
      "Este Produto está despublicado: saiu do Catálogo, e quem já comprou continua com acesso.",
    ],
  ])("the notice for %s", async (status, text) => {
    apiWith(
      product({
        status: status as Product["status"],
        file: FILE,
        firstPublishedAt: status === "DRAFT" ? null : PUBLISHED_AT,
      }),
    );
    await open();
    expect(screen.getByText(text)).toBeInTheDocument();
  });
});
