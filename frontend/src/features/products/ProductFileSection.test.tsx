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

const REAUTH_URL = "/api/admin/sessions/reauthentication";
const NOTICE =
  "Este Produto já foi publicado. Para trocar o arquivo, confirme sua senha e o código do aplicativo autenticador. Quem já comprou passa a baixar a nova versão.";
const TITLE = "Confirme sua identidade";
const CODE_LABEL = "Código do aplicativo autenticador";

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

function published() {
  return product({
    file: FILE_INFO,
    status: "UNPUBLISHED",
    firstPublishedAt: "2026-11-10T12:00:00Z",
  });
}

function reauthCalls(spy: ReturnType<typeof mockApi>) {
  return spy.mock.calls.filter(([url]) => String(url) === REAUTH_URL);
}

function chosenFile() {
  return (screen.getByLabelText("Arquivo PDF") as HTMLInputElement).files?.[0];
}

type Answer = (init: RequestInit | undefined) => Response | Promise<Response>;

// A published Product whose reauthentication and upload answers are given.
function apiWithReauth(
  reauth: Answer,
  upload: Answer = () => jsonResponse(200, product({ file: FILE_INFO })),
) {
  return mockApi((url, init) => {
    if (url === PRODUCT_URL) return jsonResponse(200, published());
    if (url === REAUTH_URL) return reauth(init);
    if (url === FILE_URL) return upload(init);
  });
}

// Picks a file and clicks Enviar arquivo, which opens the identity form.
async function openForm(file = pdf()) {
  await chooseAndSend(file);
  await screen.findByLabelText("Senha");
}

async function fill() {
  const user = setup();
  await user.type(screen.getByLabelText("Senha"), "segredo");
  await user.type(screen.getByLabelText(CODE_LABEL), "123456");
  await user.click(screen.getByRole("button", { name: "Confirmar e enviar" }));
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

  test("FR-016 SEC-008 a published Product shows the notice and the form, and Send opens the identity form without uploading", async () => {
    const spy = apiWithReauth(() => jsonResponse(204));
    await open();
    expect(screen.getByText(NOTICE)).toBeInTheDocument();
    expect(screen.getByLabelText("Arquivo PDF")).toBeInTheDocument();
    expect(screen.queryByText(TITLE)).not.toBeInTheDocument();
    await chooseAndSend(pdf());
    expect(
      await screen.findByRole("heading", { name: TITLE }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Senha")).toHaveFocus();
    expect(screen.getByLabelText("Senha")).toHaveAttribute(
      "autocomplete",
      "current-password",
    );
    expect(screen.getByLabelText(CODE_LABEL)).toHaveAttribute(
      "autocomplete",
      "one-time-code",
    );
    expect(screen.getByLabelText(CODE_LABEL)).toHaveAttribute(
      "inputmode",
      "numeric",
    );
    expect(fileCalls(spy)).toHaveLength(0);
    expect(reauthCalls(spy)).toHaveLength(0);
  });

  test("a draft never published shows no notice and uploads straight away", async () => {
    const spy = apiWith(() => jsonResponse(200, product({ file: FILE_INFO })));
    await open();
    expect(screen.queryByText(NOTICE)).not.toBeInTheDocument();
    await chooseAndSend(pdf());
    await screen.findByText("Arquivo enviado.");
    expect(screen.queryByText(TITLE)).not.toBeInTheDocument();
    expect(reauthCalls(spy)).toHaveLength(0);
  });

  test("a published Product still validates the file in the browser before opening the form", async () => {
    apiWithReauth(() => jsonResponse(204));
    await open();
    await chooseAndSend(pdf(10, "a.txt"));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "O arquivo não é um PDF. Envie um arquivo PDF.",
    );
    expect(screen.queryByText(TITLE)).not.toBeInTheDocument();
  });

  test("SEC-008 Confirm reauthenticates and then uploads the same File; fields are cleared", async () => {
    const calls: string[] = [];
    const file = pdf();
    const spy = apiWithReauth(
      (init) => {
        calls.push("reauth");
        expect(JSON.parse(String(init?.body))).toEqual({
          password: "segredo",
          code: "123456",
        });
        return jsonResponse(204);
      },
      (init) => {
        calls.push("upload");
        const body = init?.body as FormData;
        expect(body.get("file")).toBe(file);
        return jsonResponse(200, product({ file: FILE_INFO }));
      },
    );
    await open();
    await openForm(file);
    await fill();
    await screen.findByText("Arquivo enviado.");
    expect(calls).toEqual(["reauth", "upload"]);
    expect(fileCalls(spy)).toHaveLength(1);
    expect(screen.queryByText(TITLE)).not.toBeInTheDocument();
  });

  test("while confirming the button says Confirmando and is disabled", async () => {
    let finish: (r: Response) => void = () => {};
    const pending = new Promise<Response>((resolve) => {
      finish = resolve;
    });
    apiWithReauth(() => pending);
    await open();
    await openForm();
    await fill();
    const busy = await screen.findByRole("button", { name: "Confirmando…" });
    expect(busy).toBeDisabled();
    finish(jsonResponse(400));
    await screen.findByRole("button", { name: "Confirmar e enviar" });
  });

  test("a wrong password or code shows the text, clears the fields and keeps the file", async () => {
    const file = pdf();
    const spy = apiWithReauth(() =>
      jsonResponse(400, { errors: { credentials: "CREDENTIALS_INVALID" } }),
    );
    await open();
    await openForm(file);
    await fill();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Senha ou código incorretos. Confira e tente de novo.",
    );
    expect(screen.getByLabelText("Senha")).toHaveValue("");
    expect(screen.getByLabelText(CODE_LABEL)).toHaveValue("");
    expect(fileCalls(spy)).toHaveLength(0);
    expect(chosenFile()).toBe(file);
  });

  test("Cancel closes the form, keeps the file and focuses Enviar arquivo", async () => {
    const file = pdf();
    const spy = apiWithReauth(() => jsonResponse(204));
    await open();
    await openForm(file);
    await setup().click(screen.getByRole("button", { name: "Cancelar" }));
    expect(screen.queryByText(TITLE)).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Enviar arquivo" }),
    ).toHaveFocus();
    expect(chosenFile()).toBe(file);
    expect(reauthCalls(spy)).toHaveLength(0);
  });

  test("a failure after the reauthentication shows the upload text and keeps the file", async () => {
    const file = pdf();
    apiWithReauth(
      () => jsonResponse(204),
      () => jsonResponse(400, { errors: { file: "FILE_INVALID" } }),
    );
    await open();
    await openForm(file);
    await fill();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Não foi possível ler este PDF.",
    );
    expect(chosenFile()).toBe(file);
  });

  test("SEC-008: 403 REAUTHENTICATION_REQUIRED reabre o formulário; 403 sem esse corpo mostra o texto de permissão", async () => {
    const file = pdf();
    let uploadAnswer = () =>
      jsonResponse(403, {
        errors: { reauthentication: "REAUTHENTICATION_REQUIRED" },
      });
    const spy = apiWithReauth(
      () => jsonResponse(204),
      () => uploadAnswer(),
    );
    await open();
    await openForm(file);
    await fill();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "A confirmação venceu. Digite sua senha e o código de novo para enviar o arquivo.",
    );
    expect(screen.getByText(TITLE)).toBeInTheDocument();
    expect(screen.getByLabelText("Senha")).toHaveFocus();
    expect(screen.getByLabelText("Senha")).toHaveValue("");
    expect(chosenFile()).toBe(file);

    uploadAnswer = () => jsonResponse(403);
    await fill();
    await waitFor(() => expect(fileCalls(spy)).toHaveLength(2));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Você não tem permissão para enviar o arquivo do Produto.",
    );
    expect(screen.queryByText(TITLE)).not.toBeInTheDocument();
  });

  test('SEC-008: 429 na reautenticação mostra "Muitas tentativas" e mantém o arquivo escolhido', async () => {
    const file = pdf();
    const spy = apiWithReauth(() => jsonResponse(429));
    await open();
    await openForm(file);
    for (let attempt = 0; attempt < 3; attempt++) {
      await fill();
      await waitFor(() => expect(reauthCalls(spy)).toHaveLength(attempt + 1));
      expect(await screen.findByRole("alert")).toHaveTextContent(
        "Muitas tentativas. Aguarde alguns minutos e tente de novo.",
      );
    }
    expect(fileCalls(spy)).toHaveLength(0);
    expect(chosenFile()).toBe(file);
  });

  test.each([
    [
      503,
      "O sistema está indisponível no momento e o arquivo não foi enviado. Tente novamente em alguns instantes.",
    ],
    [
      500,
      "Não foi possível enviar o arquivo. Verifique a conexão e tente novamente.",
    ],
  ])("status %i on the confirmation shows its text", async (status, text) => {
    apiWithReauth(() => jsonResponse(status));
    await open();
    await openForm();
    await fill();
    expect(await screen.findByRole("alert")).toHaveTextContent(text);
  });

  test("401 on the confirmation ends the session", async () => {
    apiWithReauth(() => jsonResponse(401));
    await open();
    await openForm();
    await fill();
    await waitFor(() =>
      expect(screen.getByText("tela de entrada")).toBeInTheDocument(),
    );
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
