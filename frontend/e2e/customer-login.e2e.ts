import { randomBytes } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { expect, type Page, test } from "@playwright/test";

// Real API, database and e-mail outbox (files): no page.route() and no mocks.

const OUTBOX_DIR = resolve(process.env.E2E_OUTBOX_DIR ?? "../.tmp/outbox");
const OUTBOX_TIMEOUT_MS = 30_000;

const REGISTER_SUCCESS =
  "Cadastro recebido. Verifique seu e-mail para continuar.";
const CONFIRMATION_SUBJECT = "Confirme seu e-mail na SapiensIA";
const CONFIRMED = "E-mail confirmado. Agora você já pode entrar na sua conta.";
const LOGIN_FAILED =
  "E-mail ou senha incorretos, ou o e-mail ainda não foi confirmado.";
const LOGIN_BLOCKED =
  "Muitas tentativas de entrada. Aguarde alguns minutos e tente de novo.";

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

async function confirmationLink(email: string): Promise<string> {
  const deadline = Date.now() + OUTBOX_TIMEOUT_MS;
  while (Date.now() < deadline) {
    let names: string[] = [];
    try {
      names = (await readdir(OUTBOX_DIR)).filter((n) => n.endsWith(".txt"));
    } catch {
      // The outbox folder does not exist until the first e-mail is sent.
    }
    for (const name of names.sort()) {
      const content = await readFile(resolve(OUTBOX_DIR, name), "utf8");
      const lines = content.split(/\r?\n/);
      if (
        lines[0] === `To: ${email.toLowerCase()}` &&
        lines[1] === `Subject: ${CONFIRMATION_SUBJECT}`
      ) {
        const match = content.match(/\/confirmar-email#[A-Za-z0-9_-]+/);
        if (!match) throw new Error("No confirmation link in the e-mail");
        return match[0];
      }
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`No confirmation e-mail for ${email} in ${OUTBOX_DIR}`);
}

async function registerAndConfirm(page: Page, email: string, password: string) {
  await register(page, email, password);
  const link = await confirmationLink(email);
  await page.goto("/cadastro");
  await page.goto(link);
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
  await expect(page.getByRole("button", { name: "Sair" })).toHaveCount(0);
}

test("SEC-004: o Cliente confirmado entra, continua logado ao recarregar, sai e continua fora", async ({
  page,
}, testInfo) => {
  const email = uniqueEmail(testInfo.project.name);
  const password = strongPassword();
  await registerAndConfirm(page, email, password);

  await logIn(page, email, password);
  await expect(page.getByText(`Você entrou como ${email}.`)).toBeVisible();

  await page.reload();
  await expect(page.getByText(`Você entrou como ${email}.`)).toBeVisible();

  await page.getByRole("button", { name: "Sair", exact: true }).click();
  await expectAnonymousHome(page);

  await page.reload();
  await expectAnonymousHome(page);
});

test("SEC-004/SEC-006: senha errada mostra a mensagem única e não cria sessão", async ({
  page,
}, testInfo) => {
  const email = uniqueEmail(testInfo.project.name);
  const password = strongPassword();
  await registerAndConfirm(page, email, password);

  await logIn(page, email, `${password}x`);
  await expect(page.getByText(LOGIN_FAILED)).toBeVisible();

  await page.goto("/");
  await expectAnonymousHome(page);
});

test("SEC-002: depois de 5 senhas erradas a conta fica bloqueada, mesmo com a senha certa", async ({
  page,
}, testInfo) => {
  const email = uniqueEmail(testInfo.project.name);
  const password = strongPassword();
  await registerAndConfirm(page, email, password);

  await page.goto("/entrar");
  for (let attempt = 1; attempt <= 5; attempt++) {
    await page.getByLabel("E-mail").fill(email);
    await page.getByLabel("Senha").fill(`${password}x`);
    await page.getByRole("button", { name: "Entrar" }).click();
    await expect(page.getByRole("alert")).toHaveText(LOGIN_FAILED);
  }

  await page.getByLabel("Senha").fill(password);
  await page.getByRole("button", { name: "Entrar" }).click();
  await expect(page.getByRole("alert")).toHaveText(LOGIN_BLOCKED);

  await page.goto("/");
  await expectAnonymousHome(page);
});

test("SEC-006: conta não confirmada com a senha certa recebe a mesma mensagem e não entra", async ({
  page,
}, testInfo) => {
  const email = uniqueEmail(testInfo.project.name);
  const password = strongPassword();
  await register(page, email, password);

  await logIn(page, email, password);
  await expect(page.getByText(LOGIN_FAILED)).toBeVisible();

  await page.goto("/");
  await expectAnonymousHome(page);
});
