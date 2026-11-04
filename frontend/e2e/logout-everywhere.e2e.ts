import { randomBytes } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { expect, type Page, test } from "@playwright/test";

// Real API, database and e-mail outbox (files): no page.route() and no mocks.

const OUTBOX_DIR = resolve(process.env.E2E_OUTBOX_DIR ?? "../.tmp/outbox");
const OUTBOX_TIMEOUT_MS = 30_000;
// The scheduler polls every second in the local profile; wait well past that before asserting "no e-mail".
const QUIET_PERIOD_MS = 5_000;

const REGISTER_SUCCESS =
  "Cadastro recebido. Verifique seu e-mail para continuar.";
const CONFIRMATION_SUBJECT = "Confirme seu e-mail na SapiensIA";
const NEW_DEVICE_SUBJECT = "Novo acesso à sua conta na SapiensIA";
const CONFIRMED = "E-mail confirmado. Agora você já pode entrar na sua conta.";
const LOGGED_OUT_EVERYWHERE = "Você saiu de todos os aparelhos.";

// The desktop and celular projects share one database and one outbox, so each test gets its own e-mail.
function uniqueEmail(projectName: string): string {
  return `cliente-${Date.now()}-${projectName}-${randomBytes(4).toString("hex")}@example.com`;
}

function strongPassword(): string {
  return randomBytes(12).toString("hex");
}

type Message = { file: string; body: string };

async function messagesFor(email: string, subject: string): Promise<Message[]> {
  let names: string[];
  try {
    names = (await readdir(OUTBOX_DIR)).filter((n) => n.endsWith(".txt"));
  } catch {
    return [];
  }
  // File names start with a sortable timestamp, so name order is send order.
  names.sort();
  const found: Message[] = [];
  for (const name of names) {
    const content = await readFile(resolve(OUTBOX_DIR, name), "utf8");
    const lines = content.split(/\r?\n/);
    if (
      lines[0] === `To: ${email.toLowerCase()}` &&
      lines[1] === `Subject: ${subject}`
    ) {
      found.push({ file: name, body: content });
    }
  }
  return found;
}

async function waitForMessage(
  email: string,
  subject: string,
  nth = 1,
): Promise<Message> {
  const deadline = Date.now() + OUTBOX_TIMEOUT_MS;
  while (Date.now() < deadline) {
    const messages = await messagesFor(email, subject);
    if (messages.length >= nth) return messages[nth - 1];
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`No "${subject}" e-mail for ${email} in ${OUTBOX_DIR}`);
}

async function registerAndConfirm(page: Page, email: string, password: string) {
  await page.goto("/cadastro");
  await page.getByLabel("E-mail").fill(email);
  await page.getByLabel("Senha").fill(password);
  await page.getByRole("button", { name: "Criar conta" }).click();
  await expect(page.getByRole("status")).toHaveText(REGISTER_SUCCESS);

  const message = await waitForMessage(email, CONFIRMATION_SUBJECT);
  const match = message.body.match(/\/confirmar-email#[A-Za-z0-9_-]+/);
  if (!match) throw new Error("No confirmation link in the e-mail");
  await page.goto("/cadastro");
  await page.goto(match[0]);
  await page.getByLabel("Senha").fill(password);
  await page.getByRole("button", { name: "Confirmar e-mail" }).click();
  await expect(page.getByText(CONFIRMED)).toBeVisible();
}

async function logIn(page: Page, email: string, password: string) {
  await page.goto("/entrar");
  await page.getByLabel("E-mail").fill(email);
  await page.getByLabel("Senha").fill(password);
  await page.getByRole("button", { name: "Entrar" }).click();
}

async function expectAnonymousHome(page: Page) {
  await expect(page.getByRole("link", { name: "Entrar" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Criar conta" })).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Sair de todos os aparelhos" }),
  ).toHaveCount(0);
}

async function expectLoggedIn(page: Page, email: string) {
  await expect(page.getByText(`Você entrou como ${email}.`)).toBeVisible();
}

async function quietPeriod() {
  await new Promise((r) => setTimeout(r, QUIET_PERIOD_MS));
}

test("SEC-009: sair de todos os aparelhos encerra a outra sessão e só o aparelho novo recebe aviso", async ({
  browser,
}, testInfo) => {
  const email = uniqueEmail(testInfo.project.name);
  const password = strongPassword();

  // Two browser contexts have separate cookie jars, so they are two devices.
  const contextA = await browser.newContext();
  const contextB = await browser.newContext();
  const a = await contextA.newPage();
  const b = await contextB.newPage();

  await registerAndConfirm(a, email, password);

  // Device A: the first login of the account sends no notice.
  await logIn(a, email, password);
  await expectLoggedIn(a, email);
  await quietPeriod();
  expect(await messagesFor(email, NEW_DEVICE_SUBJECT)).toHaveLength(0);

  // Device B: a new device, so the notice arrives.
  await logIn(b, email, password);
  await expectLoggedIn(b, email);
  await waitForMessage(email, NEW_DEVICE_SUBJECT);

  // Logging out everywhere from B.
  await b.getByRole("button", { name: "Sair de todos os aparelhos" }).click();
  await expect(b.getByRole("status")).toHaveText(LOGGED_OUT_EVERYWHERE);
  await expectAnonymousHome(b);

  // Device A lost its session too.
  const current = await a.request.get("/api/sessions/current");
  expect(current.status()).toBe(401);
  await a.goto("/");
  await expectAnonymousHome(a);

  // A logs in again: it works, and A is a known device, so no new notice.
  await logIn(a, email, password);
  await expectLoggedIn(a, email);
  await quietPeriod();
  expect(await messagesFor(email, NEW_DEVICE_SUBJECT)).toHaveLength(1);

  await contextA.close();
  await contextB.close();
});
