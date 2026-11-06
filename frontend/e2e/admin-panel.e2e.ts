import { randomBytes } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { expect, type Locator, type Page, test } from "@playwright/test";
import {
  messagesFor,
  registerAndConfirm,
  strongPassword,
  uniqueEmail,
  waitForMessage,
} from "./outbox";
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
  const field = page.getByLabel("Código");
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

test("SEC-001: o Administrador entra com senha e código e o Cliente é barrado", async ({
  page,
  baseURL,
  browser,
}) => {
  test.setTimeout(120_000);
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
    await enterCode(page, totpCode(secret, step), panelShown);
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
