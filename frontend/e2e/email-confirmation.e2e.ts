import { randomBytes } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { expect, type Page, test } from "@playwright/test";

// Real API, database and e-mail outbox (files): no page.route() and no mocks.
// The system under test writes e-mails to a folder (local profile, or the file provider in CI).

const OUTBOX_DIR = resolve(process.env.E2E_OUTBOX_DIR ?? "../.tmp/outbox");
const OUTBOX_TIMEOUT_MS = 30_000;

const REGISTER_SUCCESS =
  "Cadastro recebido. Verifique seu e-mail para continuar.";
const CONFIRMATION_SUBJECT = "Confirme seu e-mail na SapiensIA";
const NOTICE_SUBJECT = "Tentativa de cadastro na SapiensIA";
const WRONG_PASSWORD =
  "Senha incorreta. Use a senha do seu cadastro mais recente.";
const INVALID_LINK =
  "Este link venceu ou já foi usado. Se o seu e-mail ainda não foi confirmado, faça o cadastro de novo para receber outro link.";
const CONFIRMED = "E-mail confirmado. Agora você já pode entrar na sua conta.";

// The desktop and celular projects share one database and one outbox, so each test gets its own e-mail.
function uniqueEmail(projectName: string): string {
  return `cliente-${Date.now()}-${projectName}-${randomBytes(4).toString("hex")}@example.com`;
}

function strongPassword(): string {
  return randomBytes(12).toString("hex");
}

async function register(page: Page, email: string, password: string) {
  await page.goto("/cadastro");
  await page.getByLabel("E-mail").fill(email);
  await page.getByLabel("Senha").fill(password);
  await page.getByRole("button", { name: "Criar conta" }).click();
  await expect(page.getByRole("status")).toHaveText(REGISTER_SUCCESS);
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

// Waits for the nth (1-based) message with this subject sent to the address.
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

// Returns the path + fragment so the link works against any base URL (Vite or the Docker image).
async function confirmationLink(email: string, nth = 1): Promise<string> {
  const message = await waitForMessage(email, CONFIRMATION_SUBJECT, nth);
  const match = message.body.match(/\/confirmar-email#[A-Za-z0-9_-]+/);
  if (!match) throw new Error("No confirmation link in the e-mail");
  return match[0];
}

async function submitPassword(page: Page, password: string) {
  await page.getByLabel("Senha").fill(password);
  await page.getByRole("button", { name: "Confirmar e-mail" }).click();
}

// The page cannot know the link is dead before a password is submitted, so a dead link is
// proven by submitting the right password and getting the "invalid link" text.
async function expectDeadLink(page: Page, link: string, password: string) {
  await openLink(page, link);
  await submitPassword(page, password);
  await expect(page.getByText(INVALID_LINK)).toBeVisible();
}

// A fresh navigation, because changing only the fragment would not reload the page.
async function openLink(page: Page, link: string) {
  await page.goto("/cadastro");
  await page.goto(link);
}

test("SEC-007: o Visitante cadastra, abre o link do e-mail e confirma com a senha", async ({
  page,
}, testInfo) => {
  const email = uniqueEmail(testInfo.project.name);
  const password = strongPassword();
  await register(page, email, password);
  const link = await confirmationLink(email);

  await openLink(page, link);
  await submitPassword(page, `${password}x`);
  await expect(page.getByText(WRONG_PASSWORD)).toBeVisible();

  await submitPassword(page, password);
  await expect(page.getByText(CONFIRMED)).toBeVisible();

  await expectDeadLink(page, link, password);
});

test("#49: o segundo cadastro do mesmo e-mail não confirmado vale; o link e a senha do primeiro não", async ({
  page,
}, testInfo) => {
  const email = uniqueEmail(testInfo.project.name);
  const passwordA = strongPassword();
  const passwordB = strongPassword();
  await register(page, email, passwordA);
  const firstLink = await confirmationLink(email, 1);
  await register(page, email, passwordB);
  const secondLink = await confirmationLink(email, 2);

  await expectDeadLink(page, firstLink, passwordA);

  await openLink(page, secondLink);
  await submitPassword(page, passwordA);
  await expect(page.getByText(WRONG_PASSWORD)).toBeVisible();
  await submitPassword(page, passwordB);
  await expect(page.getByText(CONFIRMED)).toBeVisible();
});

test("SEC-007: cadastro com e-mail de conta confirmada mostra a mesma mensagem e envia o aviso", async ({
  page,
}, testInfo) => {
  const email = uniqueEmail(testInfo.project.name);
  const password = strongPassword();
  await register(page, email, password);
  await openLink(page, await confirmationLink(email));
  await submitPassword(page, password);
  await expect(page.getByText(CONFIRMED)).toBeVisible();

  await register(page, email, strongPassword());
  const notice = await waitForMessage(email, NOTICE_SUBJECT);
  expect(notice.body).not.toContain("confirmar-email");
});

test("#49: cinco senhas erradas apagam o link", async ({ page }, testInfo) => {
  const email = uniqueEmail(testInfo.project.name);
  const password = strongPassword();
  await register(page, email, password);
  const link = await confirmationLink(email);

  await openLink(page, link);
  for (let attempt = 1; attempt <= 4; attempt++) {
    await submitPassword(page, `${password}x${attempt}`);
    await expect(page.getByText(WRONG_PASSWORD)).toBeVisible();
  }
  await submitPassword(page, `${password}x5`);
  await expect(page.getByText(INVALID_LINK)).toBeVisible();

  await expectDeadLink(page, link, password);
});
