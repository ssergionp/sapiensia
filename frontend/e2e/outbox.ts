import { randomBytes } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { expect, type Page } from "@playwright/test";

// Shared by the e2e tests that read the real e-mail outbox (files): no mocks.

export const OUTBOX_DIR = resolve(
  process.env.E2E_OUTBOX_DIR ?? "../.tmp/outbox",
);
const OUTBOX_TIMEOUT_MS = 30_000;

const REGISTER_SUCCESS =
  "Cadastro recebido. Verifique seu e-mail para continuar.";
const CONFIRMATION_SUBJECT = "Confirme seu e-mail na SapiensIA";
const CONFIRMED = "E-mail confirmado. Agora você já pode entrar na sua conta.";

// The desktop and celular projects share one database and one outbox, so each test gets its own e-mail.
export function uniqueEmail(projectName: string): string {
  return `cliente-${Date.now()}-${projectName}-${randomBytes(4).toString("hex")}@example.com`;
}

export function strongPassword(): string {
  return randomBytes(12).toString("hex");
}

export type Message = { file: string; body: string };

export async function messagesFor(
  email: string,
  subject: string,
): Promise<Message[]> {
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

export async function waitForMessage(
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

export async function registerAndConfirm(
  page: Page,
  email: string,
  password: string,
) {
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
