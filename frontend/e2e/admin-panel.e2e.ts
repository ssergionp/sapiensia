import { randomBytes } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { expect, type Page, test } from "@playwright/test";
import {
  messagesFor,
  registerAndConfirm,
  strongPassword,
  uniqueEmail,
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
const PASSWORD_SET = "Senha definida. Entre no painel com a nova senha.";
const CUSTOMER_BLOCKED = "Esta área é só para o Administrador.";

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
  await page.getByLabel("Senha").fill(password);
  await page.getByRole("button", { name: "Entrar" }).click();
  const failed = page.getByText(LOGIN_FAILED);
  const ok = page.getByText(`Você entrou como ${ADMIN_EMAIL}.`);
  await expect(failed.or(ok)).toBeVisible();
  return ok.isVisible();
}

async function setPasswordFromNewestEmail(page: Page): Promise<string> {
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
  await page.goto("/painel/entrar");
  await page.goto(link[0]);
  await page.getByLabel("Nova senha").fill(password);
  await page.getByRole("button", { name: "Salvar senha" }).click();
  await expect(page.getByText(PASSWORD_SET)).toBeVisible();
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
