import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, test, vi } from "vitest";
import { jsonResponse, mockApi, product, renderProducts } from "./testing";

const PRODUCT_URL = "/api/admin/products/p1";
const COVER_URL = "/api/admin/products/p1/cover";
const COVER = {
  mediaType: "image/jpeg",
  sizeBytes: 1_258_291,
  width: 1200,
  height: 1800,
  version: "v1",
  uploadedAt: "2026-11-09T17:32:00Z",
};

function image(size = 10, name = "capa.png", type = "image/png") {
  const file = new File(["x"], name, { type });
  Object.defineProperty(file, "size", { value: size });
  return file;
}

type Answer = (init: RequestInit | undefined) => Response | Promise<Response>;

function apiWith(cover?: Answer, loaded = product()) {
  return mockApi((url, init) => {
    if (url === PRODUCT_URL) return jsonResponse(200, loaded);
    if (url === COVER_URL && cover) return cover(init);
  });
}

function coverCalls(spy: ReturnType<typeof mockApi>) {
  return spy.mock.calls.filter(([url]) => String(url) === COVER_URL);
}

async function open() {
  renderProducts("/painel/produtos/p1");
  await screen.findByRole("heading", { name: "Capa do produto" });
}

function setup() {
  return userEvent.setup({ applyAccept: false });
}

async function choose(user: ReturnType<typeof setup>, file: File) {
  await user.upload(screen.getByLabelText("Imagem da capa"), file);
  await user.click(screen.getByRole("button", { name: "Enviar capa" }));
}

afterEach(() => vi.restoreAllMocks());

describe("ProductCoverSection", () => {
  test("sem capa mostra título, rótulo, ajudas e o aviso", async () => {
    apiWith();
    await open();
    expect(screen.getByLabelText("Imagem da capa")).toBeTruthy();
    expect(
      screen.getByText(
        "PNG, JPEG ou WebP, até 2 MB e até 4.000 pixels de largura e de altura. Recomendado: 1.200 × 1.800 pixels.",
      ),
    ).toBeTruthy();
    expect(
      screen.getByText(
        "Enviar outra imagem substitui a atual. Os dados da foto (como local e aparelho) são removidos. Se a foto aparecer girada, gire-a em um editor e envie de novo.",
      ),
    ).toBeTruthy();
    expect(screen.getByText("Nenhuma capa enviada.")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Enviar capa" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Remover capa" })).toBeNull();
    expect(screen.getByLabelText("Imagem da capa").getAttribute("accept")).toBe(
      "image/png,image/jpeg,image/webp",
    );
  });

  test("com capa mostra a linha e a prévia pela rota do painel", async () => {
    apiWith(undefined, product({ cover: COVER }));
    await open();
    expect(
      screen.getByText(
        "Capa atual: 1.200 × 1.800 pixels, 1,2 MB, enviada em 09/11/2026 às 14:32.",
      ),
    ).toBeTruthy();
    const preview = screen.getByRole("img", { name: "Capa de Guia de saúde" });
    expect(preview.getAttribute("src")).toBe(`${COVER_URL}?v=v1`);
    expect(screen.getByRole("button", { name: "Remover capa" })).toBeTruthy();
  });

  test("envio mostra o pendente e depois o sucesso", async () => {
    let finish: (r: Response) => void = () => {};
    const spy = apiWith(
      () => new Promise<Response>((resolve) => (finish = resolve)),
    );
    await open();
    await choose(setup(), image());
    expect(
      await screen.findByRole("button", { name: "Enviando capa…" }),
    ).toBeTruthy();
    const init = coverCalls(spy)[0][1] as RequestInit;
    expect(init.method).toBe("POST");
    expect((init.body as FormData).get("file")).toBeInstanceOf(File);
    finish(jsonResponse(200, product({ cover: COVER })));
    const status = await screen.findByText("Capa enviada.");
    expect(status.getAttribute("role")).toBe("status");
    expect(await screen.findByRole("img", { name: /Capa de/ })).toBeTruthy();
  });

  test.each([
    ["COVER_REQUIRED", "Selecione uma imagem."],
    ["COVER_EMPTY", "A imagem está vazia."],
    [
      "COVER_TOO_LARGE",
      "A imagem passa de 2 MB. Envie uma imagem menor, de preferência em JPEG ou WebP.",
    ],
    [
      "COVER_NOT_IMAGE",
      "A imagem precisa ser PNG, JPEG ou WebP. SVG e GIF não são aceitos.",
    ],
    [
      "COVER_INVALID",
      "Não foi possível ler esta imagem. Ela pode estar corrompida. Exporte a imagem de novo e tente outra vez.",
    ],
    [
      "COVER_TOO_MANY_PIXELS",
      "A imagem passa de 4.000 pixels de largura ou de altura. Reduza a imagem e envie de novo.",
    ],
    [
      "COVER_ANIMATED",
      "Imagens animadas não são aceitas. Envie uma imagem parada.",
    ],
  ])("servidor recusa com %s", async (code, text) => {
    apiWith(() => jsonResponse(400, { errors: { cover: code } }));
    await open();
    await choose(setup(), image());
    expect((await screen.findByRole("alert")).textContent).toBe(text);
  });

  test("COVER_TOO_LARGE vindo em 413 mostra o texto", async () => {
    apiWith(() => jsonResponse(413, { errors: { cover: "COVER_TOO_LARGE" } }));
    await open();
    await choose(setup(), image());
    expect((await screen.findByRole("alert")).textContent).toContain(
      "A imagem passa de 2 MB.",
    );
  });

  test("pré-checagens no cliente não chamam a API", async () => {
    const spy = apiWith();
    await open();
    const user = setup();
    await user.click(screen.getByRole("button", { name: "Enviar capa" }));
    expect((await screen.findByRole("alert")).textContent).toBe(
      "Selecione uma imagem.",
    );
    await choose(user, image(2_097_153));
    await waitFor(() =>
      expect(screen.getByRole("alert").textContent).toContain(
        "A imagem passa de 2 MB.",
      ),
    );
    await choose(user, image(10, "capa.gif", "image/gif"));
    await waitFor(() =>
      expect(screen.getByRole("alert").textContent).toContain(
        "A imagem precisa ser PNG, JPEG ou WebP.",
      ),
    );
    expect(coverCalls(spy)).toHaveLength(0);
  });

  test("arquivo com tipo vazio vai para a API (o servidor decide)", async () => {
    const spy = apiWith(() => jsonResponse(200, product({ cover: COVER })));
    await open();
    await choose(setup(), image(10, "capa", ""));
    await waitFor(() => expect(coverCalls(spy)).toHaveLength(1));
    expect(screen.queryByRole("alert")).toBeNull();
  });

  test.each([
    [403, "Você não tem permissão para alterar a capa do Produto."],
    [
      503,
      "O sistema está indisponível no momento e a capa não foi alterada. Tente novamente em alguns instantes.",
    ],
    [404, "Produto não encontrado."],
    [
      500,
      "Não foi possível alterar a capa. Verifique a conexão e tente novamente.",
    ],
  ])("status %i mostra o texto certo", async (status, text) => {
    apiWith(() => jsonResponse(status));
    await open();
    await choose(setup(), image());
    expect((await screen.findByRole("alert")).textContent).toBe(text);
  });

  test("erro de rede mostra o texto genérico", async () => {
    apiWith(() => {
      throw new TypeError("network");
    });
    await open();
    await choose(setup(), image());
    expect((await screen.findByRole("alert")).textContent).toBe(
      "Não foi possível alterar a capa. Verifique a conexão e tente novamente.",
    );
  });

  test("remover pede confirmação; Cancelar não chama a API", async () => {
    const spy = apiWith(undefined, product({ cover: COVER }));
    await open();
    const user = setup();
    await user.click(screen.getByRole("button", { name: "Remover capa" }));
    expect(
      screen.getByText(
        "Remover a capa deste Produto? O Catálogo passa a mostrar um quadro sem imagem.",
      ),
    ).toBeTruthy();
    await user.click(screen.getByRole("button", { name: "Cancelar" }));
    expect(coverCalls(spy)).toHaveLength(0);
    expect(screen.getByRole("button", { name: "Remover capa" })).toBeTruthy();
  });

  test("Confirmar remoção chama DELETE e mostra o sucesso", async () => {
    let removed = false;
    const spy = mockApi((url, init) => {
      if (url === PRODUCT_URL)
        return jsonResponse(200, product({ cover: removed ? null : COVER }));
      if (url === COVER_URL && init?.method === "DELETE") {
        removed = true;
        return new Response(null, { status: 204 });
      }
    });
    await open();
    const user = setup();
    await user.click(screen.getByRole("button", { name: "Remover capa" }));
    await user.click(screen.getByRole("button", { name: "Confirmar remoção" }));
    const status = await screen.findByText("Capa removida.");
    expect(status.getAttribute("role")).toBe("status");
    expect(coverCalls(spy).some(([, i]) => i?.method === "DELETE")).toBe(true);
    expect(await screen.findByText("Nenhuma capa enviada.")).toBeTruthy();
  });

  test("falha ao remover mostra o erro", async () => {
    apiWith(() => jsonResponse(503), product({ cover: COVER }));
    await open();
    const user = setup();
    await user.click(screen.getByRole("button", { name: "Remover capa" }));
    await user.click(screen.getByRole("button", { name: "Confirmar remoção" }));
    expect((await screen.findByRole("alert")).textContent).toContain(
      "O sistema está indisponível no momento e a capa não foi alterada.",
    );
  });
});
