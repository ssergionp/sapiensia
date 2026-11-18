import { expect, test } from "@playwright/test";
import { expectNoA11yViolations } from "./a11y";

// Real system, no page.route() and no mocks: the footer links lead to the three draft legal pages (#23).
const DRAFT_NOTICE =
  "RASCUNHO — revisar com advogado. Este texto ainda não é a versão final e pode mudar.";

const PAGES = [
  { link: "Termos de uso", url: /\/termos-de-uso$/, screen: "termos" },
  {
    link: "Política de privacidade",
    url: /\/privacidade$/,
    screen: "privacidade",
  },
  { link: "Política de reembolso", url: /\/reembolso$/, screen: "reembolso" },
] as const;

for (const { link, url, screen } of PAGES) {
  test(`o rodapé leva a "${link}" em rascunho, sem violações de acessibilidade (FR-023, FR-036, NFR-005)`, async ({
    page,
  }) => {
    await page.goto("/");
    await page
      .getByRole("contentinfo", { name: "Informações legais" })
      .getByRole("link", { name: link })
      .click();

    await expect(page).toHaveURL(url);
    await expect(
      page.getByRole("heading", { level: 1, name: link }),
    ).toBeVisible();
    await expect(page.getByRole("note")).toHaveText(DRAFT_NOTICE);
    await expect(page).toHaveTitle(`${link} | SapiensIA`);
    await expectNoA11yViolations(page, screen);
  });
}

// #186: the axe scans above run in the light theme; this one covers the dark palette and the remembered choice.
test("as páginas públicas passam no axe também no tema escuro, e a escolha de tema fica lembrada (NFR-005)", async ({
  page,
}) => {
  const html = page.locator("html");
  await page.emulateMedia({ colorScheme: "dark" });

  await page.goto("/");
  await expect(html).toHaveClass(/\bdark\b/);
  await expect(page.getByRole("link", { name: "Entrar" })).toBeVisible();
  await expectNoA11yViolations(page, "inicial-escuro");

  await page.goto("/termos-de-uso");
  await expect(
    page.getByRole("heading", { level: 1, name: "Termos de uso" }),
  ).toBeVisible();
  await expectNoA11yViolations(page, "termos-escuro");

  await page.getByRole("button", { name: "Usar tema claro" }).click();
  await expect(html).not.toHaveClass(/\bdark\b/);
  await page.reload();
  await expect(html).not.toHaveClass(/\bdark\b/);
  await expect(
    page.getByRole("button", { name: "Usar tema escuro" }),
  ).toBeVisible();
});
