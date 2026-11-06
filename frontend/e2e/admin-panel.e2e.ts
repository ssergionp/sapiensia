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

// Real API, database and e-mail outbox (files): no page.route() and no mocks.
// Runs only in the desktop project (see playwright.config.ts): there is a single Administrator.
// The password never goes into a title, a log or an expect message, and the artifacts that could show it are off.
test.use({ trace: "off", screenshot: "off", video: "off" });

const ADMIN_EMAIL = (
  process.env.E2E_ADMIN_EMAIL ?? "admin@example.com"
).toLowerCase();
// Repo-root .tmp/ (git-ignored); the tests run from frontend/.
const PASSWORD_FILE = resolve(
  process.env.E2E_ADMIN_PASSWORD_FILE ?? "../.tmp/e2e/admin-password.txt",
);

const FIRST_ACCESS_SUBJECT = "Defina sua senha de Administrador na SapiensIA";
const LOGIN_FAILED = "E-mail ou senha incorretos.";
const DEAD_LINK_START = "Este link venceu ou já foi usado.";
const PASSWORD_SET = "Senha definida. Entre no painel com a nova senha.";
const CUSTOMER_BLOCKED = "Esta área é só para o Administrador.";

// Playwright records the value of locator.fill() in the step title, and the HTML report keeps steps even with
// trace off. Setting the value from the page keeps the Administrator password out of every step and call log.
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

async function tryLogIn(page: Page, password: string): Promise<boolean> {
  await page.goto("/painel/entrar");
  await page.getByLabel("E-mail").fill(ADMIN_EMAIL);
  await fillSecret(page.getByLabel("Senha"), password);
  await page.getByRole("button", { name: "Entrar" }).click();
  const failed = page.getByText(LOGIN_FAILED);
  const ok = page.getByText(`Você entrou como ${ADMIN_EMAIL}.`);
  await expect(failed.or(ok)).toBeVisible();
  return ok.isVisible();
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

test("SEC-001: o Administrador entra e sai do painel e o Cliente é barrado", async ({
  page,
  baseURL,
  browser,
}) => {
  const host = new URL(baseURL ?? "").hostname;
  if (host !== "localhost" && host !== "127.0.0.1") {
    throw new Error(
      "Refusing to run the administrator e2e outside localhost/127.0.0.1",
    );
  }

  const stored = await readStoredPassword();
  let loggedIn = stored !== null && (await tryLogIn(page, stored));
  if (!loggedIn) {
    const password = await setPasswordFromNewestEmail(page);
    loggedIn = await tryLogIn(page, password);
  }
  expect(loggedIn, "the Administrator could not log in").toBe(true);

  await expect(
    page.getByRole("heading", { name: "Painel do Administrador" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Sair" }).click();
  await expect(page).toHaveURL(/\/painel\/entrar$/);

  // A Customer (new context, new session) is told the area is not theirs.
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
