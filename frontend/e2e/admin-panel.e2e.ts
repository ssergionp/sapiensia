import { randomBytes } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import {
  devices,
  expect,
  type Locator,
  type Page,
  test,
} from "@playwright/test";
import { buildPng, buildWebpWithExif } from "./image";
import {
  messagesFor,
  registerAndConfirm,
  strongPassword,
  uniqueEmail,
  waitForMessage,
} from "./outbox";
import { buildPdf } from "./pdf";
import { currentStep, totpCode } from "./totp";

// Real API, database and e-mail outbox (files): no page.route() and no mocks.
// Runs only in the desktop project (see playwright.config.ts): there is a single Administrator.
// The password, the TOTP secret and the codes never go into a title, a log, an expect or an error message,
// and the artifacts that could show them (trace, screenshot, video) are off.
test.use({ trace: "off", screenshot: "off", video: "off" });

const ADMIN_EMAIL = (
  process.env.E2E_ADMIN_EMAIL ?? "admin@example.com"
).toLowerCase();
// Repo-root .tmp/ (git-ignored); the tests run from frontend/.
const PASSWORD_FILE = resolve(
  process.env.E2E_ADMIN_PASSWORD_FILE ?? "../.tmp/e2e/admin-password.txt",
);
const TOTP_FILE = resolve(
  process.env.E2E_ADMIN_TOTP_FILE ?? "../.tmp/e2e/admin-totp-secret.json",
);

const FIRST_ACCESS_SUBJECT = "Defina sua senha de Administrador na SapiensIA";
const LOGIN_FAILED = "E-mail ou senha incorretos.";
const DEAD_LINK_START = "Este link venceu ou já foi usado.";
const PASSWORD_SET = "Senha definida. Entre no painel com a nova senha.";
const CUSTOMER_BLOCKED = "Esta área é só para o Administrador.";
const ENROLL_TITLE = "Ative a verificação em duas etapas";
const ENROLL_ALTERNATIVE =
  "Não consegue ler o QR code? Digite esta chave no aplicativo:";
const VERIFY_TITLE = "Verificação em duas etapas";
const WRONG_CODE =
  "Código incorreto ou vencido. Confira o aplicativo e tente de novo.";
const HIDDEN = "[oculto]";
const CODES_TITLE = "Guarde seus códigos de recuperação";
const CODES_CONTINUE = "Continuar para o painel";
const CODES_SAVED = "Guardei meus códigos em um lugar seguro";
const CODES_LIST = "Códigos de recuperação";
const USE_RECOVERY = "Perdeu o celular? Use um código de recuperação";
const RECOVERY_TITLE = "Entrar com código de recuperação";
const RECOVERY_LABEL = "Código de recuperação";
const RECOVERY_WRONG =
  "Código de recuperação incorreto ou já usado. Confira e tente de novo.";
const REGENERATE = "Gerar novos códigos";
const REGENERATE_LABEL = "Código do aplicativo autenticador";
const REGENERATE_SUBMIT = "Confirmar e gerar";
const REGENERATED_TITLE = "Seus novos códigos de recuperação";
const REGENERATED_DONE = "Concluir";

// Playwright records the value of locator.fill() in the step title, and the HTML report keeps steps even with
// trace off. Setting the value from the page keeps the password and the codes out of every step and call log.
async function fillSecret(field: Locator, value: string) {
  await field.evaluate((el, v) => {
    const setter = Object.getOwnPropertyDescriptor(
      HTMLInputElement.prototype,
      "value",
    )?.set;
    if (!setter) throw new Error("No value setter");
    setter.call(el, v);
    el.dispatchEvent(new Event("input", { bubbles: true }));
  }, value);
}

async function readStoredPassword(): Promise<string | null> {
  try {
    const text = (await readFile(PASSWORD_FILE, "utf8")).trim();
    return text.length > 0 ? text : null;
  } catch {
    return null;
  }
}

type StoredTotp = { secret: string; lastStep: number };

async function readStoredTotp(): Promise<StoredTotp | null> {
  try {
    const data: unknown = JSON.parse(await readFile(TOTP_FILE, "utf8"));
    if (
      typeof data === "object" &&
      data !== null &&
      "secret" in data &&
      "lastStep" in data &&
      typeof data.secret === "string" &&
      typeof data.lastStep === "number" &&
      /^[A-Z2-7]+$/.test(data.secret)
    ) {
      return { secret: data.secret, lastStep: data.lastStep };
    }
    return null;
  } catch {
    return null;
  }
}

// The mode 0o600 is ignored by the Windows ACLs; the file lives in the git-ignored .tmp folder.
async function writeStoredTotp(value: StoredTotp) {
  await mkdir(dirname(TOTP_FILE), { recursive: true });
  await writeFile(TOTP_FILE, JSON.stringify(value), {
    encoding: "utf8",
    mode: 0o600,
  });
}

// The server refuses a time step that was already used, so a new code needs a step after the last one.
// It accepts +-1 step, so a code generated just before a step rolls over is still valid when it arrives.
async function waitForStepAfter(lastStep: number) {
  while (currentStep() <= lastStep) {
    await new Promise((r) => setTimeout(r, 1_000));
  }
}

// Types the password and goes to the MFA page. Returns false when the password is refused.
async function tryPassword(page: Page, password: string): Promise<boolean> {
  await page.goto("/painel/entrar");
  await page.getByLabel("E-mail").fill(ADMIN_EMAIL);
  const field = page.getByLabel("Senha");
  try {
    await fillSecret(field, password);
    await page.getByRole("button", { name: "Entrar" }).click();
    const failed = page.getByText(LOGIN_FAILED);
    const mfa = page
      .getByRole("heading", { name: ENROLL_TITLE, exact: true })
      .or(page.getByRole("heading", { name: VERIFY_TITLE, exact: true }));
    await expect(failed.or(mfa)).toBeVisible();
    return !(await failed.isVisible());
  } finally {
    await clearSecret(field);
  }
}

async function setPasswordFromNewestEmail(page: Page): Promise<string> {
  // Wait (bounded) until a first-access e-mail exists, then pick the newest one.
  try {
    await waitForMessage(ADMIN_EMAIL, FIRST_ACCESS_SUBJECT);
  } catch {
    throw new Error(
      "Administrator password unknown and no first-access e-mail in the outbox: see Risco (g) of ticket #14 (restart the API with a clean Administrator, then run again).",
    );
  }
  const messages = await messagesFor(ADMIN_EMAIL, FIRST_ACCESS_SUBJECT);
  const link = [...messages]
    .reverse()
    .map((m) => m.body.match(/\/painel\/definir-senha#[A-Za-z0-9_-]{43}/))
    .find((m) => m !== null);
  if (!link) {
    throw new Error(
      "Administrator password unknown and no first-access e-mail in the outbox: see Risco (g) of ticket #14 (restart the API with a clean Administrator, then run again).",
    );
  }
  const password = strongPassword();
  // A fresh navigation, because changing only the fragment would not reload the page.
  await page.goto("/painel/entrar");
  await page.goto(link[0]);
  await fillSecret(page.getByLabel("Nova senha"), password);
  await page.getByRole("button", { name: "Salvar senha" }).click();
  const done = page.getByText(PASSWORD_SET);
  const dead = page.getByText(DEAD_LINK_START);
  await expect(done.or(dead)).toBeVisible();
  if (await dead.isVisible()) {
    throw new Error(
      "Administrator password unknown and the newest first-access link is already used or expired: see Risco (g) of ticket #14.",
    );
  }
  await mkdir(dirname(PASSWORD_FILE), { recursive: true });
  await writeFile(PASSWORD_FILE, password, "utf8");
  return password;
}

// Clears a field that holds a secret, if it is still on the page. A failure snapshot (error-context.md) lists
// the value of every textbox, so the secret must not be left in it when a later step fails.
async function clearSecret(field: Locator) {
  if ((await field.count()) > 0) await fillSecret(field, "");
}

// Types the code, submits and runs the checks of the outcome. Whatever happens, the code field is cleared at the end.
async function enterCode(
  page: Page,
  code: string,
  outcome: () => Promise<void>,
) {
  const field = page.getByLabel("Código", { exact: true });
  try {
    await fillSecret(field, code);
    await page
      .getByRole("button", { name: /^(Ativar e entrar|Entrar)$/ })
      .click();
    await outcome();
  } finally {
    await clearSecret(field);
  }
}

// Reads the manual key from the screen and hides it in the DOM in the same call (nothing runs in between), so that
// no failure snapshot has it. Fails without printing any content.
async function readAndHideKey(page: Page): Promise<string> {
  const text = await page
    .getByText(ENROLL_ALTERNATIVE)
    .locator("xpath=following-sibling::p[1]")
    .evaluate((el, hidden) => {
      const value = el.textContent;
      el.textContent = hidden;
      return value;
    }, HIDDEN);
  const secret = (text ?? "").replace(/\s/g, "");
  if (!/^[A-Z2-7]{16,}$/.test(secret)) {
    throw new Error("The manual key on the enrollment page could not be read");
  }
  return secret;
}

// Reads the recovery codes from the list and hides them in the DOM in the same call, so that no failure snapshot has
// them. The codes stay only in the memory of the test.
async function readAndHideCodes(page: Page): Promise<string[]> {
  const items = await page
    .getByRole("list", { name: CODES_LIST })
    .getByRole("listitem")
    .evaluateAll((els, hidden) => {
      const values = els.map((el) => el.textContent ?? "");
      for (const el of els) el.textContent = hidden;
      return values;
    }, HIDDEN);
  const codes = items.map((c) => c.replace(/[\s-]/g, ""));
  if (codes.length !== 10 || !codes.every((c) => /^[A-Z2-7]{24}$/.test(c))) {
    throw new Error("The recovery codes on the screen are not 10 valid codes");
  }
  return codes;
}

// Confirms that the codes were saved (the button is disabled until then) and clicks the final button.
async function acknowledgeCodes(page: Page, doneLabel: string) {
  const done = page.getByRole("button", { name: doneLabel, exact: true });
  await expect(done).toBeDisabled();
  await page.getByLabel(CODES_SAVED).check();
  await expect(done).toBeEnabled();
  await done.click();
}

// Password, then the recovery code instead of the app code. Whatever happens, the field is cleared at the end.
async function recoveryLogin(
  page: Page,
  password: string,
  code: string,
  outcome: () => Promise<void>,
) {
  expect(await tryPassword(page, password), "the password was refused").toBe(
    true,
  );
  await page.getByRole("button", { name: USE_RECOVERY }).click();
  await expect(
    page.getByRole("heading", { name: RECOVERY_TITLE, exact: true }),
  ).toBeVisible();
  const field = page.getByLabel(RECOVERY_LABEL, { exact: true });
  try {
    await fillSecret(field, code);
    await page.getByRole("button", { name: "Entrar", exact: true }).click();
    await outcome();
  } finally {
    await clearSecret(field);
  }
}

// In the panel: confirms with a fresh app code and returns the 10 new recovery codes.
async function regenerateCodes(page: Page, secret: string): Promise<string[]> {
  const stored = await readStoredTotp();
  await waitForStepAfter(stored?.lastStep ?? 0);
  const step = currentStep();
  await writeStoredTotp({ secret, lastStep: step });
  await page.getByRole("button", { name: REGENERATE, exact: true }).click();
  const field = page.getByLabel(REGENERATE_LABEL, { exact: true });
  try {
    await fillSecret(field, totpCode(secret, step));
    await page
      .getByRole("button", { name: REGENERATE_SUBMIT, exact: true })
      .click();
    await expect(
      page.getByRole("heading", { name: REGENERATED_TITLE, exact: true }),
    ).toBeVisible();
  } finally {
    await clearSecret(field);
  }
  const codes = await readAndHideCodes(page);
  await acknowledgeCodes(page, REGENERATED_DONE);
  return codes;
}

test("SEC-001: o Administrador entra com senha e código e o Cliente é barrado", async ({
  page,
  baseURL,
  browser,
}) => {
  test.setTimeout(300_000);
  const host = new URL(baseURL ?? "").hostname;
  if (host !== "localhost" && host !== "127.0.0.1") {
    throw new Error(
      "Refusing to run the administrator e2e outside localhost/127.0.0.1",
    );
  }

  // 1. Password (the stored one, or a new one from the first-access e-mail).
  const stored = await readStoredPassword();
  let passwordOk = stored !== null && (await tryPassword(page, stored));
  let password = stored;
  if (!passwordOk) {
    password = await setPasswordFromNewestEmail(page);
    passwordOk = await tryPassword(page, password);
  }
  expect(passwordOk, "the Administrator could not log in").toBe(true);
  if (password === null) throw new Error("No Administrator password");

  const enrolling = await page
    .getByRole("heading", { name: ENROLL_TITLE, exact: true })
    .isVisible();
  const painel = page.getByRole("heading", { name: "Painel do Administrador" });
  const panelShown = () => expect(painel).toBeVisible();
  const wrongCodeShown = async () => {
    await expect(page.getByText(WRONG_CODE)).toBeVisible();
    await expect(painel).toBeHidden();
  };

  // 5. A code outside the window (10 steps ahead) is refused. It never opens the panel.
  // It runs first, on the page of either mode, with the secret only known after the enrollment page is read.
  let secret: string;
  if (enrolling) {
    // 2. MFA not active: enroll with the key shown on the page.
    secret = await readAndHideKey(page);
    await enterCode(page, totpCode(secret, currentStep() + 10), wrongCodeShown);
    const step = currentStep();
    // Saved before the click, so a failure after the activation does not lose the secret. If the activation
    // does not happen, the next run sees the enrollment page again and overwrites it.
    await writeStoredTotp({ secret, lastStep: step });
    // The recovery codes come first; the button to the panel stays disabled until they are confirmed as saved.
    await enterCode(page, totpCode(secret, step), async () => {
      await expect(
        page.getByRole("heading", { name: CODES_TITLE, exact: true }),
      ).toBeVisible();
      const enrollmentCodes = await readAndHideCodes(page);
      expect(new Set(enrollmentCodes).size).toBe(10);
      await acknowledgeCodes(page, CODES_CONTINUE);
      await panelShown();
    });
  } else {
    // 3. MFA active: the stored secret is needed.
    const totp = await readStoredTotp();
    if (totp === null) {
      throw new Error(
        "The two-step verification is active but the stored secret is missing. See the manual recovery procedure in docs/adr/0004-identidade-e-mfa.md: delete the row in `administrators`, restart the API, then delete .tmp/e2e/admin-password.txt and run again.",
      );
    }
    secret = totp.secret;
    await enterCode(page, totpCode(secret, currentStep() + 10), wrongCodeShown);
    await waitForStepAfter(totp.lastStep);
    const step = currentStep();
    await writeStoredTotp({ secret, lastStep: step });
    await enterCode(page, totpCode(secret, step), panelShown);
  }

  // 6. Log out and back in with the password and the next code.
  const usedStep = (await readStoredTotp())?.lastStep ?? currentStep();
  await page.getByRole("button", { name: "Sair" }).click();
  await expect(page).toHaveURL(/\/painel\/entrar$/);
  expect(await tryPassword(page, password), "the password was refused").toBe(
    true,
  );
  await expect(
    page.getByRole("heading", { name: VERIFY_TITLE, exact: true }),
  ).toBeVisible();
  await waitForStepAfter(usedStep);
  const nextStep = currentStep();
  await writeStoredTotp({ secret, lastStep: nextStep });
  await enterCode(page, totpCode(secret, nextStep), panelShown);
  await page.getByRole("button", { name: "Sair" }).click();
  await expect(page).toHaveURL(/\/painel\/entrar$/);

  // 6b. Recovery codes. New codes from the panel (needs a fresh app code), then each code works once.
  expect(await tryPassword(page, password), "the password was refused").toBe(
    true,
  );
  await waitForStepAfter((await readStoredTotp())?.lastStep ?? 0);
  const loginStep = currentStep();
  await writeStoredTotp({ secret, lastStep: loginStep });
  await enterCode(page, totpCode(secret, loginStep), panelShown);
  const firstCodes = await regenerateCodes(page, secret);

  // A recovery code opens the panel; the same code does not work a second time.
  await page.getByRole("button", { name: "Sair" }).click();
  await expect(page).toHaveURL(/\/painel\/entrar$/);
  await recoveryLogin(page, password, firstCodes[0], panelShown);
  await page.getByRole("button", { name: "Sair" }).click();
  await expect(page).toHaveURL(/\/painel\/entrar$/);
  const rejected = async () => {
    await expect(page.getByText(RECOVERY_WRONG)).toBeVisible();
    await expect(painel).toBeHidden();
  };
  await recoveryLogin(page, password, firstCodes[0], rejected);

  // New codes from the panel: the old ones stop working and a new one works.
  await recoveryLogin(page, password, firstCodes[1], panelShown);
  const secondCodes = await regenerateCodes(page, secret);
  await page.getByRole("button", { name: "Sair" }).click();
  await expect(page).toHaveURL(/\/painel\/entrar$/);
  await recoveryLogin(page, password, firstCodes[2], rejected);
  await recoveryLogin(page, password, secondCodes[0], panelShown);
  await page.getByRole("button", { name: "Sair" }).click();
  await expect(page).toHaveURL(/\/painel\/entrar$/);

  // 7. A Customer (new context, new session) is told the area is not theirs.
  const context = await browser.newContext({ baseURL: baseURL ?? undefined });
  const customerPage = await context.newPage();
  const email = uniqueEmail("painel");
  const customerPassword = randomBytes(12).toString("hex");
  await registerAndConfirm(customerPage, email, customerPassword);
  await customerPage.goto("/entrar");
  await customerPage.getByLabel("E-mail").fill(email);
  await customerPage.getByLabel("Senha").fill(customerPassword);
  await customerPage.getByRole("button", { name: "Entrar" }).click();
  await expect(
    customerPage.getByText(`Você entrou como ${email}.`),
  ).toBeVisible();
  await customerPage.goto("/painel");
  await expect(customerPage.getByText(CUSTOMER_BLOCKED)).toBeVisible();
  await context.close();
});

// Logs in with the stored password and the stored TOTP secret (the Administrator already has the two-step
// verification active, which the test above guarantees). Nothing secret goes into a step, a log or an error.
async function loginWithStoredCredentials(page: Page) {
  const password = await readStoredPassword();
  const totp = await readStoredTotp();
  if (password === null || totp === null) {
    throw new Error(
      "The stored Administrator password or two-step secret is missing: run the login test of this file first.",
    );
  }
  expect(await tryPassword(page, password), "the password was refused").toBe(
    true,
  );
  await waitForStepAfter(totp.lastStep);
  const step = currentStep();
  await writeStoredTotp({ secret: totp.secret, lastStep: step });
  await enterCode(
    page,
    totpCode(totp.secret, step),
    async () =>
      await expect(
        page.getByRole("heading", { name: "Painel do Administrador" }),
      ).toBeVisible(),
  );
}

test("FR-016 FR-018: o Administrador cria um Produto em rascunho, edita os dados e o preço", async ({
  page,
  baseURL,
}) => {
  test.setTimeout(120_000);
  const host = new URL(baseURL ?? "").hostname;
  if (host !== "localhost" && host !== "127.0.0.1") {
    throw new Error(
      "Refusing to run the administrator e2e outside localhost/127.0.0.1",
    );
  }
  const stamp = Date.now();
  const title = `E2E Produto ${stamp}`;
  const newTitle = `E2E Produto ${stamp} editado`;

  await loginWithStoredCredentials(page);

  // Create.
  await page.goto("/painel/produtos");
  await page.getByRole("link", { name: "Novo Produto" }).click();
  await expect(
    page.getByRole("heading", { name: "Novo Produto", exact: true }),
  ).toBeVisible();
  await page.getByLabel("Título").fill(title);
  await page.getByLabel("Descrição").fill("Descrição do Produto de teste.");
  await page.getByLabel("Categoria").selectOption({ label: "Tecnologia" });
  await page.getByLabel("Valor (R$)").fill("19,90");
  await page.getByRole("button", { name: "Criar Produto" }).click();

  // The list shows the confirmation and the new draft Product.
  await expect(page.getByText("Produto criado.")).toBeVisible();
  const row = page.getByRole("row", { name: new RegExp(title) });
  await expect(row.getByText("R$ 19,90")).toBeVisible();
  await expect(row.getByText("Rascunho")).toBeVisible();

  // Edit the data.
  await page.getByRole("link", { name: `Editar ${title}` }).click();
  await expect(
    page.getByRole("heading", { name: "Editar Produto", exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel("Título")).toHaveValue(title);
  await page.getByLabel("Título").fill(newTitle);
  await page.getByLabel("Descrição").fill("Descrição editada.");
  await page.getByRole("button", { name: "Salvar dados" }).click();
  await expect(page.getByText("Dados salvos.")).toBeVisible();

  // Edit the price.
  await page.getByLabel("Valor (R$)").fill("24,50");
  await page.getByRole("button", { name: "Salvar preço" }).click();
  await expect(page.getByText("Preço salvo.")).toBeVisible();

  // Everything persisted.
  await page.reload();
  await expect(page.getByLabel("Título")).toHaveValue(newTitle);
  await expect(page.getByLabel("Descrição")).toHaveValue("Descrição editada.");
  await expect(page.getByLabel("Valor (R$)")).toHaveValue("24,50");

  await page.goto("/painel/produtos");
  const updated = page.getByRole("row", { name: new RegExp(newTitle) });
  await expect(updated.getByText("R$ 24,50")).toBeVisible();
  await expect(updated.getByText("Rascunho")).toBeVisible();
});

test("SEC-043 FR-034: o Administrador envia o PDF do Produto e o substitui; arquivo falso é recusado", async ({
  page,
  baseURL,
}) => {
  test.setTimeout(120_000);
  const host = new URL(baseURL ?? "").hostname;
  if (host !== "localhost" && host !== "127.0.0.1") {
    throw new Error(
      "Refusing to run the administrator e2e outside localhost/127.0.0.1",
    );
  }
  const title = `E2E Arquivo ${Date.now()}`;

  await loginWithStoredCredentials(page);

  // Create a draft Product through the UI and open its edit page.
  await page.goto("/painel/produtos");
  await page.getByRole("link", { name: "Novo Produto" }).click();
  await page.getByLabel("Título").fill(title);
  await page.getByLabel("Descrição").fill("Produto de teste do arquivo.");
  await page.getByLabel("Categoria").selectOption({ label: "Tecnologia" });
  await page.getByLabel("Valor (R$)").fill("19,90");
  await page.getByRole("button", { name: "Criar Produto" }).click();
  await expect(page.getByText("Produto criado.")).toBeVisible();
  await page.getByRole("link", { name: `Editar ${title}` }).click();
  await expect(
    page.getByRole("heading", { name: "Editar Produto", exact: true }),
  ).toBeVisible();

  const input = page.getByLabel("Arquivo PDF");
  const send = page.getByRole("button", { name: "Enviar arquivo" });
  const sent = page.getByText("Arquivo enviado.");
  const current = page.getByText(/^Arquivo atual: 1 página,/);

  // A valid 1-page PDF.
  await input.setInputFiles({
    name: "produto.pdf",
    mimeType: "application/pdf",
    buffer: buildPdf("primeiro"),
  });
  await send.click();
  await expect(sent).toBeVisible();
  await expect(current).toBeVisible();

  // It persisted.
  await page.reload();
  await expect(current).toBeVisible();

  // A text file named .pdf passes the browser check and is refused by the server.
  await input.setInputFiles({
    name: "falso.pdf",
    mimeType: "application/pdf",
    buffer: Buffer.from("isto não é um PDF"),
  });
  await send.click();
  await expect(
    page.getByText("O arquivo não é um PDF. Envie um arquivo PDF."),
  ).toBeVisible();

  // Another valid PDF replaces the first one.
  await input.setInputFiles({
    name: "produto-2.pdf",
    mimeType: "application/pdf",
    buffer: buildPdf("segundo"),
  });
  await send.click();
  await expect(sent).toBeVisible();
  await expect(current).toBeVisible();
});

test("FR-011 FR-016 FR-018 SEC-008 SEC-010 FR-010: o Administrador publica, despublica e troca o PDF depois de confirmar senha e código", async ({
  page,
  browser,
  baseURL,
}) => {
  test.setTimeout(120_000);
  const host = new URL(baseURL ?? "").hostname;
  if (host !== "localhost" && host !== "127.0.0.1") {
    throw new Error(
      "Refusing to run the administrator e2e outside localhost/127.0.0.1",
    );
  }
  const title = `E2E Publicação ${Date.now()}`;

  await loginWithStoredCredentials(page);

  // Create a draft Product through the UI and open its edit page.
  await page.goto("/painel/produtos");
  await page.getByRole("link", { name: "Novo Produto" }).click();
  await page.getByLabel("Título").fill(title);
  await page.getByLabel("Descrição").fill("Produto de teste da publicação.");
  await page.getByLabel("Categoria").selectOption({ label: "Tecnologia" });
  await page.getByLabel("Valor (R$)").fill("19,90");
  await page.getByRole("button", { name: "Criar Produto" }).click();
  await expect(page.getByText("Produto criado.")).toBeVisible();
  await page.getByRole("link", { name: `Editar ${title}` }).click();
  await expect(
    page.getByRole("heading", { name: "Editar Produto", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Publicação", exact: true }),
  ).toBeVisible();
  const productId =
    /\/painel\/produtos\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/.exec(
      page.url(),
    )?.[1];
  if (productId === undefined) {
    throw new Error("Could not read the Product id from the edit page URL.");
  }

  // Without a file, publishing is disabled.
  const publish = page.getByRole("button", { name: "Publicar", exact: true });
  await expect(publish).toBeDisabled();
  await expect(
    page.getByText("Envie o Arquivo do produto para poder publicar."),
  ).toBeVisible();

  // Upload the PDF.
  await page.getByLabel("Arquivo PDF").setInputFiles({
    name: "produto.pdf",
    mimeType: "application/pdf",
    buffer: buildPdf("publicacao"),
  });
  await page.getByRole("button", { name: "Enviar arquivo" }).click();
  await expect(page.getByText("Arquivo enviado.")).toBeVisible();

  // Publish with the inline confirmation.
  await expect(publish).toBeEnabled();
  await publish.click();
  await page.getByRole("button", { name: "Confirmar publicação" }).click();
  await expect(page.getByText("Produto publicado.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Despublicar" })).toBeVisible();

  // A visitor context has no Administrator cookies: it sees only what is public.
  const visitorContext = await browser.newContext({ baseURL });
  try {
    const visitor = await visitorContext.newPage();

    // The published Product appears in the Catálogo and opens its page.
    await visitor.goto("/catalogo");
    const catalogLink = visitor.getByRole("link", { name: title });
    await expect(catalogLink).toBeVisible();
    await catalogLink.click();
    await expect(
      visitor.getByRole("heading", { level: 1, name: title }),
    ).toBeVisible();
    await expect(visitor.getByText("Tecnologia")).toBeVisible();
    await expect(visitor.getByText(/R\$\s19,90/)).toBeVisible();
    expect(
      (await visitor.request.get(`/api/products/${productId}`)).status(),
    ).toBe(200);

    // The list shows "Publicado".
    await page.goto("/painel/produtos");
    await expect(
      page.getByRole("row", { name: new RegExp(title) }).getByText("Publicado"),
    ).toBeVisible();

    // Unpublish.
    await page.getByRole("link", { name: `Editar ${title}` }).click();
    await page.getByRole("button", { name: "Despublicar" }).click();
    await expect(page.getByText("Produto despublicado.")).toBeVisible();
    await expect(
      page.getByRole("button", { name: "Publicar de novo" }),
    ).toBeVisible();

    // The unpublished Product disappears for the visitor.
    await visitor.goto("/catalogo");
    await expect(
      visitor.getByRole("heading", { level: 1, name: "Catálogo" }),
    ).toBeVisible();
    await expect(visitor.getByText("Carregando…")).toHaveCount(0);
    await expect(visitor.getByRole("link", { name: title })).toHaveCount(0);
    await visitor.goto(`/catalogo/${productId}`);
    await expect(
      visitor.getByRole("heading", {
        level: 1,
        name: "Produto não encontrado",
      }),
    ).toBeVisible();
    await expect(visitor.getByText(title)).toHaveCount(0);
    expect(
      (await visitor.request.get(`/api/products/${productId}`)).status(),
    ).toBe(404);

    // The list shows "Despublicado".
    await page.goto("/painel/produtos");
    await expect(
      page
        .getByRole("row", { name: new RegExp(title) })
        .getByText("Despublicado"),
    ).toBeVisible();

    // After the first publication the file section explains the rule; choosing a file and sending asks to confirm.
    await page.getByRole("link", { name: `Editar ${title}` }).click();
    await expect(
      page.getByText(
        "Este Produto já foi publicado. Para trocar o arquivo, confirme sua senha e o código do aplicativo autenticador.",
        { exact: false },
      ),
    ).toBeVisible();
    const input = page.getByLabel("Arquivo PDF");
    await expect(page.getByText(/^Arquivo atual: 1 página,/)).toBeVisible();
    await input.setInputFiles({
      name: "produto-novo.pdf",
      mimeType: "application/pdf",
      buffer: buildPdf("troca", 2),
    });
    await page.getByRole("button", { name: "Enviar arquivo" }).click();
    await expect(
      page.getByRole("heading", { name: "Confirme sua identidade" }),
    ).toBeVisible();

    const password = await readStoredPassword();
    const totp = await readStoredTotp();
    if (password === null || totp === null) {
      throw new Error(
        "The stored Administrator password or two-step secret is missing.",
      );
    }
    const passwordField = page.getByLabel("Senha", { exact: true });
    const codeField = page.getByLabel("Código do aplicativo autenticador");
    const confirm = page.getByRole("button", { name: "Confirmar e enviar" });

    // One wrong attempt only (it must not lock the account): the file stays chosen.
    await fillSecret(passwordField, password);
    await fillSecret(codeField, totpCode(totp.secret, currentStep() + 10));
    await confirm.click();
    await expect(
      page.getByText("Senha ou código incorretos. Confira e tente de novo."),
    ).toBeVisible();
    await expect(input).toHaveValue(/produto-novo.pdf$/);

    // The right password and the next TOTP step (after the one used by the login): the file is replaced.
    await waitForStepAfter(totp.lastStep);
    const step = currentStep();
    await writeStoredTotp({ secret: totp.secret, lastStep: step });
    await fillSecret(passwordField, password);
    await fillSecret(codeField, totpCode(totp.secret, step));
    await confirm.click();
    await expect(page.getByText("Arquivo enviado.")).toBeVisible();
    await expect(page.getByText(/^Arquivo atual: 2 páginas,/)).toBeVisible();
  } finally {
    await visitorContext.close();
  }
});

test("FR-016 SEC-017 SEC-010: capa enviada no painel aparece no Catálogo e capa de rascunho não é servida", async ({
  page,
  browser,
  baseURL,
}) => {
  test.setTimeout(120_000);
  const host = new URL(baseURL ?? "").hostname;
  if (host !== "localhost" && host !== "127.0.0.1") {
    throw new Error(
      "Refusing to run the administrator e2e outside localhost/127.0.0.1",
    );
  }
  const title = `E2E Capa ${Date.now()}`;
  const loaded = (img: Locator) =>
    img.evaluate((el: HTMLImageElement) => el.complete && el.naturalWidth);

  await loginWithStoredCredentials(page);

  // Create a draft Product through the UI and open its edit page.
  await page.goto("/painel/produtos");
  await page.getByRole("link", { name: "Novo Produto" }).click();
  await page.getByLabel("Título").fill(title);
  await page.getByLabel("Descrição").fill("Produto de teste da capa.");
  await page.getByLabel("Categoria").selectOption({ label: "Tecnologia" });
  await page.getByLabel("Valor (R$)").fill("19,90");
  await page.getByRole("button", { name: "Criar Produto" }).click();
  await expect(page.getByText("Produto criado.")).toBeVisible();
  await page.getByRole("link", { name: `Editar ${title}` }).click();
  await expect(
    page.getByRole("heading", { name: "Editar Produto", exact: true }),
  ).toBeVisible();
  const productId =
    /\/painel\/produtos\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/.exec(
      page.url(),
    )?.[1];
  if (productId === undefined) {
    throw new Error("Could not read the Product id from the edit page URL.");
  }
  const coverUrl = `/api/products/${productId}/cover`;

  // 1. Upload a WebP that carries an EXIF chunk; the preview loads.
  await page.getByLabel("Imagem da capa").setInputFiles({
    name: "capa.webp",
    mimeType: "image/webp",
    buffer: buildWebpWithExif(),
  });
  await page.getByRole("button", { name: "Enviar capa" }).click();
  await expect(page.getByText("Capa enviada.")).toBeVisible();
  const preview = page.getByRole("img", { name: `Capa de ${title}` });
  await expect.poll(() => loaded(preview)).toBeGreaterThan(0);

  const visitorContext = await browser.newContext({ baseURL });
  try {
    const visitor = await visitorContext.newPage();

    // 2. A draft cover is not served to a Visitor.
    const draft = await visitor.request.get(coverUrl);
    expect(draft.status()).toBe(404);
    expect((await draft.body()).length).toBe(0);

    // 3. Published: the Catálogo shows the cover, with the exact type, nosniff and no EXIF.
    await page.getByLabel("Arquivo PDF").setInputFiles({
      name: "produto.pdf",
      mimeType: "application/pdf",
      buffer: buildPdf("capa"),
    });
    await page.getByRole("button", { name: "Enviar arquivo" }).click();
    await expect(page.getByText("Arquivo enviado.")).toBeVisible();
    await page.getByRole("button", { name: "Publicar", exact: true }).click();
    await page.getByRole("button", { name: "Confirmar publicação" }).click();
    await expect(page.getByText("Produto publicado.")).toBeVisible();

    await visitor.goto("/catalogo");
    const card = visitor.getByRole("img", { name: `Capa de ${title}` });
    await expect(card).toBeVisible();
    await expect.poll(() => loaded(card)).toBeGreaterThan(0);
    const served = await visitor.request.get(coverUrl);
    expect(served.status()).toBe(200);
    expect(served.headers()["content-type"]).toBe("image/webp");
    expect(served.headers()["x-content-type-options"]).toBe("nosniff");
    expect((await served.body()).includes(Buffer.from("Exif"))).toBe(false);

    // 4. Replace with a 12x18 PNG; the Product page shows the new one.
    await page.getByLabel("Imagem da capa").setInputFiles({
      name: "capa.png",
      mimeType: "image/png",
      buffer: buildPng(12, 18),
    });
    await page.getByRole("button", { name: "Enviar capa" }).click();
    await expect(page.getByText("Capa enviada.")).toBeVisible();
    await visitor.goto(`/catalogo/${productId}`);
    const productImg = visitor.getByRole("img", { name: `Capa de ${title}` });
    await expect.poll(() => loaded(productImg)).toBe(12);

    // 5. Remove (with confirmation); the Catálogo shows the neutral frame and the route answers 404.
    await page.getByRole("button", { name: "Remover capa" }).click();
    await page.getByRole("button", { name: "Confirmar remoção" }).click();
    await expect(page.getByText("Capa removida.")).toBeVisible();
    await visitor.goto("/catalogo");
    await expect(visitor.getByRole("link", { name: title })).toBeVisible();
    await expect(
      visitor.getByRole("img", { name: `Capa de ${title}` }),
    ).toHaveCount(0);
    expect((await visitor.request.get(coverUrl)).status()).toBe(404);
  } finally {
    await visitorContext.close();
  }
});

test("SEC-010 FR-016: sem sessão, as rotas de Produto do painel negam", async ({
  playwright,
  baseURL,
}) => {
  // A fresh request context has no cookies: a plain anonymous visitor against the real API.
  const anonymous = await playwright.request.newContext({ baseURL });
  try {
    // Reads are denied by the authorization rules: 401 for an anonymous visitor.
    for (const url of ["/api/admin/products", "/api/admin/products/1"]) {
      expect((await anonymous.get(url)).status(), `GET ${url}`).toBe(401);
    }
    // Writes without a session never succeed: 401, or 403 when the CSRF check answers first.
    const body = { data: { title: "x" } };
    for (const response of [
      await anonymous.post("/api/admin/products", body),
      await anonymous.put("/api/admin/products/1", body),
      await anonymous.put("/api/admin/products/1/price", body),
    ]) {
      expect([401, 403]).toContain(response.status());
    }
  } finally {
    await anonymous.dispose();
  }
});

// A valid CPF for the test, built at run time from 9 random digits plus the two check digits.
function fictitiousCpf(): string {
  const digits = Array.from({ length: 9 }, () => randomBytes(1)[0] % 10);
  for (const length of [9, 10]) {
    const sum = digits
      .slice(0, length)
      .reduce((total, d, i) => total + d * (length + 1 - i), 0);
    const rest = (sum * 10) % 11;
    digits.push(rest === 10 ? 0 : rest);
  }
  return digits.join("");
}

const PRODUCT_EDIT_PATH =
  /\/painel\/produtos\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/;
const ORDER_PATH =
  /\/pedidos\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

test("FR-019 SEC-012 SEC-033 SEC-011: o Visitante é levado a entrar, volta ao Produto e gera um Pedido com Pix", async ({
  page,
  browser,
  baseURL,
}) => {
  test.setTimeout(180_000);
  const host = new URL(baseURL ?? "").hostname;
  if (host !== "localhost" && host !== "127.0.0.1") {
    throw new Error(
      "Refusing to run the administrator e2e outside localhost/127.0.0.1",
    );
  }
  const title = `E2E Pix ${Date.now()}`;

  // The Administrator publishes a Product (the same steps as the cover test).
  await loginWithStoredCredentials(page);
  await page.goto("/painel/produtos");
  await page.getByRole("link", { name: "Novo Produto" }).click();
  await page.getByLabel("Título").fill(title);
  await page.getByLabel("Descrição").fill("Produto de teste do Pix.");
  await page.getByLabel("Categoria").selectOption({ label: "Tecnologia" });
  await page.getByLabel("Valor (R$)").fill("29,90");
  await page.getByRole("button", { name: "Criar Produto" }).click();
  await expect(page.getByText("Produto criado.")).toBeVisible();
  await page.getByRole("link", { name: `Editar ${title}` }).click();
  await expect(
    page.getByRole("heading", { name: "Editar Produto", exact: true }),
  ).toBeVisible();
  const productId = PRODUCT_EDIT_PATH.exec(page.url())?.[1];
  if (productId === undefined) {
    throw new Error("Could not read the Product id from the edit page URL.");
  }
  await page.getByLabel("Arquivo PDF").setInputFiles({
    name: "produto.pdf",
    mimeType: "application/pdf",
    buffer: buildPdf("pix"),
  });
  await page.getByRole("button", { name: "Enviar arquivo" }).click();
  await expect(page.getByText("Arquivo enviado.")).toBeVisible();
  await page.getByRole("button", { name: "Publicar", exact: true }).click();
  await page.getByRole("button", { name: "Confirmar publicação" }).click();
  await expect(page.getByText("Produto publicado.")).toBeVisible();

  async function newCustomer(customerPage: Page) {
    const email = uniqueEmail("pedido");
    const password = strongPassword();
    await registerAndConfirm(customerPage, email, password);
    return { email, password };
  }

  async function logIn(
    customerPage: Page,
    customer: { email: string; password: string },
  ) {
    await customerPage.goto("/entrar");
    await customerPage.getByLabel("E-mail").fill(customer.email);
    await customerPage.getByLabel("Senha").fill(customer.password);
    await customerPage.getByRole("button", { name: "Entrar" }).click();
  }

  const first = await browser.newContext({ baseURL: baseURL ?? undefined });
  const second = await browser.newContext({ baseURL: baseURL ?? undefined });
  const mobile = await browser.newContext({
    ...devices["Pixel 7"],
    baseURL: baseURL ?? undefined,
  });
  try {
    // A Visitor with a confirmed account but no session clicks to buy and is sent to log in.
    const visitor = await first.newPage();
    const customer = await newCustomer(visitor);
    await visitor.goto(`/catalogo/${productId}`);
    await visitor.getByRole("button", { name: "Comprar com Pix" }).click();
    await expect(visitor).toHaveURL(/\/entrar$/);
    await expect(
      visitor.getByText("Entre ou crie sua conta para continuar a compra."),
    ).toBeVisible();

    // Logging in brings the Customer back to the Product; nothing is bought on its own.
    await visitor.getByLabel("E-mail").fill(customer.email);
    await visitor.getByLabel("Senha").fill(customer.password);
    await visitor.getByRole("button", { name: "Entrar" }).click();
    await expect(visitor).toHaveURL(
      (url) => url.pathname === `/catalogo/${productId}`,
    );
    await visitor.getByRole("button", { name: "Comprar com Pix" }).click();

    // First purchase: the CPF block, with its explanation.
    await expect(
      visitor.getByRole("heading", { name: "Informe seu CPF" }),
    ).toBeVisible();
    await expect(
      visitor.getByText(
        "O CPF é exigido para gerar a cobrança por Pix. Ele é enviado ao Asaas, que processa o pagamento, e não fica guardado na SapiensIA.",
      ),
    ).toBeVisible();
    await visitor.getByLabel("CPF").fill(fictitiousCpf());
    await visitor.getByRole("button", { name: "Gerar Pix" }).click();

    // The Order page: QR Code, copy and paste code, price and countdown.
    await expect(visitor).toHaveURL(ORDER_PATH);
    const orderUrl = visitor.url();
    await expect(
      visitor.getByRole("heading", { name: "Pague com Pix" }),
    ).toBeVisible();
    await expect(
      visitor.getByRole("img", { name: "QR Code do Pix" }),
    ).toBeVisible();
    const code = visitor.getByLabel("Pix copia e cola");
    await expect(code).not.toHaveValue("");
    const shownCode = await code.inputValue();
    await expect(visitor.getByText(title)).toBeVisible();
    await expect(visitor.getByText("R$ 29,90")).toBeVisible();
    await expect(
      visitor.getByText(/Este Pix expira em \d{2}:\d{2}\./),
    ).toBeVisible();

    // The code is read from the provider on every view: a reload shows the same one.
    await visitor.reload();
    await expect(visitor.getByLabel("Pix copia e cola")).toHaveValue(shownCode);

    // Buying again goes to the same Order and does not ask for the CPF again.
    await visitor.goto(`/catalogo/${productId}`);
    await visitor.getByRole("button", { name: "Comprar com Pix" }).click();
    await expect(visitor).toHaveURL(orderUrl);
    await expect(
      visitor.getByRole("heading", { name: "Pague com Pix" }),
    ).toBeVisible();
    await expect(
      visitor.getByRole("heading", { name: "Informe seu CPF" }),
    ).toHaveCount(0);

    // Another Customer does not see this Order.
    const other = await second.newPage();
    const otherCustomer = await newCustomer(other);
    await logIn(other, otherCustomer);
    await expect(
      other.getByText(`Você entrou como ${otherCustomer.email}.`),
    ).toBeVisible();
    await other.goto(orderUrl);
    await expect(
      other.getByRole("heading", { name: "Pedido não encontrado" }),
    ).toBeVisible();
    await expect(other.getByLabel("Pix copia e cola")).toHaveCount(0);

    // The same purchase on a phone screen: no horizontal scroll.
    const phone = await mobile.newPage();
    const phoneCustomer = await newCustomer(phone);
    await logIn(phone, phoneCustomer);
    await expect(
      phone.getByText(`Você entrou como ${phoneCustomer.email}.`),
    ).toBeVisible();
    await phone.goto(`/catalogo/${productId}`);
    await phone.getByRole("button", { name: "Comprar com Pix" }).click();
    await phone.getByLabel("CPF").fill(fictitiousCpf());
    await phone.getByRole("button", { name: "Gerar Pix" }).click();
    await expect(phone).toHaveURL(ORDER_PATH);
    await expect(
      phone.getByRole("img", { name: "QR Code do Pix" }),
    ).toBeVisible();
    await expect(phone.getByLabel("Pix copia e cola")).not.toHaveValue("");
    const widths = await phone.evaluate(() => ({
      scroll: document.documentElement.scrollWidth,
      client: document.documentElement.clientWidth,
    }));
    expect(widths.scroll).toBeLessThanOrEqual(widths.client);
  } finally {
    await first.close();
    await second.close();
    await mobile.close();
  }
});

// Local profile webhook token (fictitious), built at run time so no secret-looking literal sits in the repo.
const LOCAL_WEBHOOK_TOKEN = [
  "local-only-asaas-webhook-token",
  "000000000000",
].join("-");
// An official Asaas address: the prod profile of the CI reads it from Fly-Client-IP; locally the header is ignored.
const ASAAS_IP = "52.67.12.206";
const PAID_HEADING = "Pagamento confirmado";
const PAID_TEXT = "Recebemos o seu Pix. Você já tem Acesso a este Produto.";

test("FR-021 FR-024 SEC-025 SEC-026 SEC-027 SEC-031: o pagamento confirmado pelo Asaas libera o Acesso", async ({
  page,
  browser,
  baseURL,
  request,
}) => {
  test.setTimeout(180_000);
  const host = new URL(baseURL ?? "").hostname;
  if (host !== "localhost" && host !== "127.0.0.1") {
    throw new Error(
      "Refusing to run the administrator e2e outside localhost/127.0.0.1",
    );
  }
  const token = process.env.E2E_ASAAS_WEBHOOK_TOKEN ?? LOCAL_WEBHOOK_TOKEN;
  const title = `E2E Pago ${Date.now()}`;
  const eventId = () => `evt_e2e_${randomBytes(8).toString("hex")}`;

  // The Administrator publishes a Product.
  await loginWithStoredCredentials(page);
  await page.goto("/painel/produtos");
  await page.getByRole("link", { name: "Novo Produto" }).click();
  await page.getByLabel("Título").fill(title);
  await page.getByLabel("Descrição").fill("Produto de teste do pagamento.");
  await page.getByLabel("Categoria").selectOption({ label: "Tecnologia" });
  await page.getByLabel("Valor (R$)").fill("29,90");
  await page.getByRole("button", { name: "Criar Produto" }).click();
  await expect(page.getByText("Produto criado.")).toBeVisible();
  await page.getByRole("link", { name: `Editar ${title}` }).click();
  await expect(
    page.getByRole("heading", { name: "Editar Produto", exact: true }),
  ).toBeVisible();
  const productId = PRODUCT_EDIT_PATH.exec(page.url())?.[1];
  if (productId === undefined) {
    throw new Error("Could not read the Product id from the edit page URL.");
  }
  await page.getByLabel("Arquivo PDF").setInputFiles({
    name: "produto.pdf",
    mimeType: "application/pdf",
    buffer: buildPdf("pago"),
  });
  await page.getByRole("button", { name: "Enviar arquivo" }).click();
  await expect(page.getByText("Arquivo enviado.")).toBeVisible();
  await page.getByRole("button", { name: "Publicar", exact: true }).click();
  await page.getByRole("button", { name: "Confirmar publicação" }).click();
  await expect(page.getByText("Produto publicado.")).toBeVisible();

  // The provider event is sent outside the browser, to the real endpoint, with the accepted token and IP.
  async function sendPaymentReceived(chargeId: string, id: string) {
    const response = await request.post("/api/webhooks/asaas", {
      headers: { "asaas-access-token": token, "Fly-Client-IP": ASAAS_IP },
      data: {
        id,
        event: "PAYMENT_RECEIVED",
        dateCreated: new Date().toISOString().replace("T", " ").slice(0, 19),
        payment: {
          object: "payment",
          id: chargeId,
          billingType: "PIX",
          status: "RECEIVED",
        },
      },
    });
    expect(response.status()).toBe(200);
  }

  async function logInNewCustomer(customerPage: Page, prefix: string) {
    const email = uniqueEmail(prefix);
    const password = strongPassword();
    await registerAndConfirm(customerPage, email, password);
    await customerPage.goto("/entrar");
    await customerPage.getByLabel("E-mail").fill(email);
    await customerPage.getByLabel("Senha").fill(password);
    await customerPage.getByRole("button", { name: "Entrar" }).click();
    await expect(
      customerPage.getByText(`Você entrou como ${email}.`),
    ).toBeVisible();
  }

  async function buyFirstTime(customerPage: Page) {
    await logInNewCustomer(customerPage, "pago");
    await customerPage.goto(`/catalogo/${productId}`);
    await customerPage.getByRole("button", { name: "Comprar com Pix" }).click();
    await customerPage.getByLabel("CPF").fill(fictitiousCpf());
    await customerPage.getByRole("button", { name: "Gerar Pix" }).click();
    await expect(customerPage).toHaveURL(ORDER_PATH);
    await expect(
      customerPage.getByRole("heading", { name: "Pague com Pix" }),
    ).toBeVisible();
    const shown = await customerPage
      .getByLabel("Pix copia e cola")
      .inputValue();
    const chargeId = /(pay_[A-Za-z0-9_]+)$/.exec(shown)?.[1];
    if (chargeId === undefined) {
      throw new Error("Could not read the charge id from the shown Pix code.");
    }
    return { chargeId, orderUrl: customerPage.url() };
  }

  const first = await browser.newContext({ baseURL: baseURL ?? undefined });
  const second = await browser.newContext({ baseURL: baseURL ?? undefined });
  const mobile = await browser.newContext({
    ...devices["Pixel 7"],
    baseURL: baseURL ?? undefined,
  });
  try {
    const buyer = await first.newPage();
    const { chargeId, orderUrl } = await buyFirstTime(buyer);

    // Parameters in the URL never decide the state: before the event the page still asks for the payment.
    await buyer.goto(`${orderUrl}?status=PAID`);
    await expect(
      buyer.getByRole("heading", { name: "Pague com Pix" }),
    ).toBeVisible();
    await expect(buyer.getByText(PAID_HEADING)).toHaveCount(0);
    await buyer.goto(orderUrl);
    await expect(
      buyer.getByRole("heading", { name: "Pague com Pix" }),
    ).toBeVisible();

    // The confirmed payment arrives; the same page, without a reload, turns to paid.
    await sendPaymentReceived(chargeId, eventId());
    await expect(
      buyer.getByRole("heading", { name: PAID_HEADING }),
    ).toBeVisible({ timeout: 30_000 });
    await expect(buyer.getByText(PAID_TEXT)).toBeVisible();
    await expect(buyer.getByLabel("Pix copia e cola")).toHaveCount(0);

    // The same event again (same id) and a new id for the same payment change nothing.
    const repeatedId = eventId();
    await sendPaymentReceived(chargeId, repeatedId);
    await sendPaymentReceived(chargeId, repeatedId);
    await sendPaymentReceived(chargeId, eventId());
    await buyer.reload();
    await expect(
      buyer.getByRole("heading", { name: PAID_HEADING }),
    ).toBeVisible();
    await expect(buyer.getByText(PAID_TEXT)).toBeVisible();

    // Buying the same Product again is refused: the Customer already has Access.
    await buyer.goto(`/catalogo/${productId}`);
    await buyer.getByRole("button", { name: "Comprar com Pix" }).click();
    await expect(
      buyer.getByText("Você já tem Acesso a este Produto."),
    ).toBeVisible();

    // Another Customer does not see the paid Order.
    const other = await second.newPage();
    await logInNewCustomer(other, "pagooutro");
    await other.goto(orderUrl);
    await expect(
      other.getByRole("heading", { name: "Pedido não encontrado" }),
    ).toBeVisible();
    await expect(other.getByText(PAID_HEADING)).toHaveCount(0);

    // The paid view on a phone screen: no horizontal scroll.
    const phone = await mobile.newPage();
    const phoneOrder = await buyFirstTime(phone);
    await sendPaymentReceived(phoneOrder.chargeId, eventId());
    await expect(
      phone.getByRole("heading", { name: PAID_HEADING }),
    ).toBeVisible({ timeout: 30_000 });
    const widths = await phone.evaluate(() => ({
      scroll: document.documentElement.scrollWidth,
      client: document.documentElement.clientWidth,
    }));
    expect(widths.scroll).toBeLessThanOrEqual(widths.client);
  } finally {
    await first.close();
    await second.close();
    await mobile.close();
  }
});
