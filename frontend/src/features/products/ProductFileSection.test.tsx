import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, test, vi } from "vitest";
import { jsonResponse, mockApi, product, renderProducts } from "./testing";

const PRODUCT_URL = "/api/admin/products/p1";
const FILE_URL = "/api/admin/products/p1/file";
const FILE_INFO = {
  sizeBytes: 3_565_158,
  pageCount: 120,
  uploadedAt: "2026-11-09T17:32:00Z",
};

function pdf(size = 10, name = "guia.pdf") {
  const file = new File(["x"], name, { type: "application/pdf" });
  Object.defineProperty(file, "size", { value: size });
  return file;
}

function setup() {
  // applyAccept false: the section itself must check the extension, not only the picker.
  return userEvent.setup({ applyAccept: false });
}

function fileCalls(spy: ReturnType<typeof mockApi>) {
  return spy.mock.calls.filter(([url]) => String(url) === FILE_URL);
}

async function open() {
  renderProducts("/painel/produtos/p1");
  await screen.findByRole("heading", { name: "Arquivo do produto" });
}

function apiWith(
  upload?: (init: RequestInit | undefined) => Response | Promise<Response>,
  loaded = product(),
) {
  return mockApi((url, init) => {
    if (url === PRODUCT_URL) return jsonResponse(200, loaded);
    if (url === FILE_URL && upload) return upload(init);
  });
}

async function chooseAndSend(file: File) {
  const user = setup();
  await user.upload(screen.getByLabelText("Arquivo PDF"), file);
  await user.click(screen.getByRole("button", { name: "Enviar arquivo" }));
}

describe("ProductFileSection", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  test("without a file it says so and omits the replace hint", async () => {
    apiWith();
    await open();
    expect(screen.getByText("Nenhum arquivo enviado.")).toBeInTheDocument();
    expect(
      screen.getByText("Somente PDF, até 50 MB e 1.000 páginas."),
    ).toBeInTheDocument();
    expect(
      screen.queryByText("Enviar outro arquivo substitui o atual."),
    ).not.toBeInTheDocument();
    expect(screen.getByLabelText("Arquivo PDF")).toBeInTheDocument();
  });

  test("with a file it shows pages, size and the Brasilia date and time", async () => {
    apiWith(
      undefined,
      product({ file: { ...FILE_INFO, uploadedAt: "2026-11-10T02:30:00Z" } }),
    );
    await open();
    expect(
      screen.getByText(
        "Arquivo atual: 120 páginas, 3,4 MB, enviado em 09/11/2026 às 23:30.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText("Enviar outro arquivo substitui o atual."),
    ).toBeInTheDocument();
  });

  test("one page is singular", async () => {
    apiWith(undefined, product({ file: { ...FILE_INFO, pageCount: 1 } }));
    await open();
    expect(screen.getByText(/Arquivo atual: 1 página,/)).toBeInTheDocument();
  });

  test("no file chosen shows FILE_REQUIRED without a request", async () => {
    const spy = apiWith();
    await open();
    await setup().click(screen.getByRole("button", { name: "Enviar arquivo" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Selecione um arquivo PDF.",
    );
    expect(fileCalls(spy)).toHaveLength(0);
  });

  test("a file over 52,428,800 bytes is refused in the browser", async () => {
    const spy = apiWith();
    await open();
    await chooseAndSend(pdf(52_428_801));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "O arquivo passa de 50 MB. Envie um PDF menor.",
    );
    expect(fileCalls(spy)).toHaveLength(0);
  });

  test("a file of exactly 52,428,800 bytes is sent", async () => {
    const spy = apiWith(() => jsonResponse(200, product({ file: FILE_INFO })));
    await open();
    await chooseAndSend(pdf(52_428_800));
    await screen.findByText("Arquivo enviado.");
    expect(fileCalls(spy)).toHaveLength(1);
  });

  test("an uppercase .PDF file name is accepted by the extension check", async () => {
    const spy = apiWith(() => jsonResponse(200, product({ file: FILE_INFO })));
    await open();
    await chooseAndSend(pdf(10, "GUIA.PDF"));
    await screen.findByText("Arquivo enviado.");
    expect(fileCalls(spy)).toHaveLength(1);
  });

  test("a .txt file is refused in the browser", async () => {
    const spy = apiWith();
    await open();
    await chooseAndSend(pdf(10, "a.txt"));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "O arquivo não é um PDF. Envie um arquivo PDF.",
    );
    expect(fileCalls(spy)).toHaveLength(0);
  });

  test("while sending the button is disabled; success updates the line", async () => {
    let finish: (r: Response) => void = () => {};
    const pending = new Promise<Response>((resolve) => {
      finish = resolve;
    });
    const spy = apiWith(() => pending);
    await open();
    await chooseAndSend(pdf());
    const busy = await screen.findByRole("button", {
      name: "Enviando arquivo…",
    });
    expect(busy).toBeDisabled();
    finish(jsonResponse(200, product({ file: FILE_INFO })));
    expect(await screen.findByText("Arquivo enviado.")).toHaveAttribute(
      "role",
      "status",
    );
    expect(
      screen.getByText(/Arquivo atual: 120 páginas, 3,4 MB, enviado em/),
    ).toBeInTheDocument();
    const init = fileCalls(spy)[0][1] as RequestInit;
    expect(init.method).toBe("POST");
    expect((init.body as FormData).get("file")).toBeInstanceOf(File);
    expect(new Headers(init.headers).has("Content-Type")).toBe(false);
  });

  test.each([
    ["FILE_EMPTY", "O arquivo está vazio."],
    ["FILE_TOO_LARGE", "O arquivo passa de 50 MB. Envie um PDF menor."],
    ["FILE_NOT_PDF", "O arquivo não é um PDF. Envie um arquivo PDF."],
    [
      "FILE_INVALID",
      "Não foi possível ler este PDF. Ele pode estar corrompido. Gere o PDF de novo e tente outra vez.",
    ],
    [
      "FILE_ENCRYPTED",
      "O PDF está protegido por senha. Envie uma versão sem proteção.",
    ],
    [
      "FILE_TOO_MANY_PAGES",
      "O PDF passa de 1.000 páginas. Envie um arquivo com até 1.000 páginas.",
    ],
  ])("server code %s shows its text", async (code, text) => {
    apiWith(() =>
      jsonResponse(code === "FILE_TOO_LARGE" ? 413 : 400, {
        errors: { file: code },
      }),
    );
    await open();
    await chooseAndSend(pdf());
    expect(await screen.findByRole("alert")).toHaveTextContent(text);
    expect(screen.queryByText("Arquivo enviado.")).not.toBeInTheDocument();
  });

  test.each([
    [403, "Você não tem permissão para enviar o arquivo do Produto."],
    [404, "Produto não encontrado."],
    [
      503,
      "O sistema está indisponível no momento e o arquivo não foi enviado. Tente novamente em alguns instantes.",
    ],
    [
      500,
      "Não foi possível enviar o arquivo. Verifique a conexão e tente novamente.",
    ],
  ])("status %i shows its text", async (status, text) => {
    apiWith(() => jsonResponse(status));
    await open();
    await chooseAndSend(pdf());
    expect(await screen.findByRole("alert")).toHaveTextContent(text);
  });

  test("a network failure shows the generic text", async () => {
    apiWith(() => {
      throw new TypeError("network");
    });
    await open();
    await chooseAndSend(pdf());
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Não foi possível enviar o arquivo. Verifique a conexão e tente novamente.",
    );
  });

  test("401 ends the session like the rest of the screen", async () => {
    apiWith(() => jsonResponse(401));
    await open();
    await chooseAndSend(pdf());
    await waitFor(() =>
      expect(screen.getByText("tela de entrada")).toBeInTheDocument(),
    );
  });
});
