import { expect, type Page, test } from "@playwright/test";
import {
  registerAndConfirm,
  strongPassword,
  uniqueEmail,
  waitForMessage,
} from "./outbox";

// Real API, database and e-mail outbox (files): no page.route() and no mocks.

const RESET_SUBJECT = "Redefinição de senha na SapiensIA";
const NOTICE_SUBJECT = "Sua senha na SapiensIA foi alterada";
const LOGIN_FAILED =
  "E-mail ou senha incorretos, ou o e-mail ainda não foi confirmado.";
const REQUEST_RECEIVED =
  "Pedido recebido. Se o e-mail estiver cadastrado, você receberá um link em instantes.";
const PASSWORD_CHANGED =
  "Senha alterada. Por segurança, você saiu de todos os aparelhos. Entre com a nova senha.";
const DEAD_LINK =
  'Este link venceu ou já foi usado. Peça um novo link em "Esqueci minha senha".';
const LOGIN_BLOCKED =
  "Muitas tentativas de entrada. Aguarde alguns minutos e tente de novo.";

async function logIn(page: Page, email: string, password: string) {
  await page.goto("/entrar");
  await page.getByLabel("E-mail").fill(email);
  await page.getByLabel("Senha").fill(password);
  await page.getByRole("button", { name: "Entrar" }).click();
}

async function requestReset(page: Page, email: string) {
  await page.goto("/esqueci-senha");
  await page.getByLabel("E-mail").fill(email);
  await page.getByRole("button", { name: "Enviar link" }).click();
  await expect(page.getByText(REQUEST_RECEIVED)).toBeVisible();
}

async function expectAnonymousHome(page: Page) {
  await expect(page.getByRole("link", { name: "Entrar" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Criar conta" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Sair" })).toHaveCount(0);
}

// A fresh navigation, because changing only the fragment would not reload the page.
async function openLink(page: Page, link: string) {
  await page.goto("/esqueci-senha");
  await page.goto(link);
  // The page has read the token, so it must be out of the address bar.
  await expect(page).not.toHaveURL(/#/);
}

// Done after the new password is saved: once the token left the history entry, going back
// and forward shows the page without a token, so the link cannot be used any more.
async function expectTokenOutOfHistory(page: Page) {
  await page.goBack();
  await page.goForward();
  await expect(page).not.toHaveURL(/#/);
}

async function submitNewPassword(page: Page, password: string) {
  await page.getByLabel("Nova senha").fill(password);
  await page.getByRole("button", { name: "Salvar nova senha" }).click();
}

test("SEC-006/SEC-007: recuperar a senha encerra a sessão aberta, o link vale uma vez e o titular recebe o aviso", async ({
  browser,
  page,
}, testInfo) => {
  const email = uniqueEmail(testInfo.project.name);
  const oldPassword = strongPassword();
  const newPassword = strongPassword();
  await registerAndConfirm(page, email, oldPassword);

  // Page A: a session opened before the password change.
  await logIn(page, email, oldPassword);
  await expect(page.getByText(`Você entrou como ${email}.`)).toBeVisible();

  // Another browser context asks for recovery: same answer for a known and an unknown e-mail.
  const otherContext = await browser.newContext();
  const other = await otherContext.newPage();
  await requestReset(other, uniqueEmail("desconhecido"));
  await requestReset(other, email);

  const message = await waitForMessage(email, RESET_SUBJECT);
  const match = message.body.match(
    /^.*(\/redefinir-senha#[A-Za-z0-9_-]+)\s*$/m,
  );
  if (!match) throw new Error("No reset link in the e-mail");
  const link = match[1];

  await openLink(other, link);
  await submitNewPassword(other, newPassword);
  await expect(other.getByText(PASSWORD_CHANGED)).toBeVisible();
  await expectTokenOutOfHistory(other);
  // the success message's own "Entrar" link (the signed-out header has one too since #186 stage 2)
  await expect(
    other.getByRole("main").getByRole("link", { name: "Entrar" }),
  ).toBeVisible();

  // The old session is gone.
  const current = await page.request.get("/api/sessions/current");
  expect(current.status()).toBe(401);
  await page.goto("/");
  await expectAnonymousHome(page);

  // New password works, old one does not.
  await logIn(page, email, oldPassword);
  await expect(page.getByText(LOGIN_FAILED)).toBeVisible();
  await logIn(page, email, newPassword);
  await expect(page.getByText(`Você entrou como ${email}.`)).toBeVisible();

  // The same link cannot be used again.
  await openLink(other, link);
  await submitNewPassword(other, strongPassword());
  await expect(other.getByText(DEAD_LINK)).toBeVisible();

  // The owner is told, and the notice carries neither password nor token.
  const notice = await waitForMessage(email, NOTICE_SUBJECT);
  const token = link.split("#")[1];
  expect(notice.body).not.toContain(newPassword);
  expect(notice.body).not.toContain(oldPassword);
  expect(notice.body).not.toContain(token);
  expect(notice.body).not.toContain("redefinir-senha");

  await otherContext.close();
});

test("SEC-002/SEC-007: trocar a senha pelo link libera a conta bloqueada por senhas erradas", async ({
  page,
}, testInfo) => {
  const email = uniqueEmail(testInfo.project.name);
  const oldPassword = strongPassword();
  const newPassword = strongPassword();
  await registerAndConfirm(page, email, oldPassword);

  // Five wrong passwords lock the account, even for the right one.
  await page.goto("/entrar");
  for (let attempt = 1; attempt <= 5; attempt++) {
    await page.getByLabel("E-mail").fill(email);
    await page.getByLabel("Senha").fill(`${oldPassword}x`);
    await page.getByRole("button", { name: "Entrar" }).click();
    await expect(page.getByRole("alert")).toHaveText(LOGIN_FAILED);
  }
  await page.getByLabel("Senha").fill(oldPassword);
  await page.getByRole("button", { name: "Entrar" }).click();
  await expect(page.getByRole("alert")).toHaveText(LOGIN_BLOCKED);

  await requestReset(page, email);
  const message = await waitForMessage(email, RESET_SUBJECT);
  const match = message.body.match(
    /^.*(\/redefinir-senha#[A-Za-z0-9_-]+)\s*$/m,
  );
  if (!match) throw new Error("No reset link in the e-mail");
  await openLink(page, match[1]);
  await submitNewPassword(page, newPassword);
  await expect(page.getByText(PASSWORD_CHANGED)).toBeVisible();

  await logIn(page, email, newPassword);
  await expect(page.getByText(`Você entrou como ${email}.`)).toBeVisible();
});
