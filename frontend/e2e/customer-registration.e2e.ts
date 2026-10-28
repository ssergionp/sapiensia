import { randomBytes } from "node:crypto";
import { expect, type Page, test } from "@playwright/test";

// Real API and database: no page.route() and no mocks. The leaked-password case is not here
// because it depends on the real HIBP service (it is covered by unit and integration tests).

const SUCCESS_MESSAGE =
  "Cadastro recebido. Verifique seu e-mail para continuar.";

// The desktop and celular projects share one database, so each test gets its own e-mail.
function uniqueEmail(projectName: string): string {
  return `cliente-${Date.now()}-${projectName}-${randomBytes(4).toString("hex")}@example.com`;
}

// 24 random hex characters: letters and digits, well above the minimum length.
function strongPassword(): string {
  return randomBytes(12).toString("hex");
}

async function register(page: Page, email: string, password: string) {
  await page.goto("/cadastro");
  await expect(
    page.getByRole("heading", { level: 1, name: "Criar conta" }),
  ).toBeVisible();
  await page.getByLabel("E-mail").fill(email);
  await page.getByLabel("Senha").fill(password);
  await page.getByRole("button", { name: "Criar conta" }).click();
  // A missing CSRF token would give 403 and a generic error instead of this message.
  await expect(page.getByRole("status")).toHaveText(SUCCESS_MESSAGE);
}

test("SEC-002/SEC-033: o Visitante cria a conta pelo navegador", async ({
  page,
}, testInfo) => {
  await register(page, uniqueEmail(testInfo.project.name), strongPassword());
});

test("SEC-002/SEC-033: e-mail repetido mostra a mesma mensagem de sucesso", async ({
  page,
}, testInfo) => {
  const email = uniqueEmail(testInfo.project.name);
  const password = strongPassword();

  await register(page, email, password);
  await register(page, email, password);
});
