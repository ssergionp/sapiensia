import { expect, test } from "@playwright/test";

// Smoke test of the whole system. Each project adds one file per critical flow (purchase, delivery, login...),
// always against the real API and database: no page.route() and no mocks here.

test("a API está no ar e o banco responde", async ({ request }) => {
  const response = await request.get("/api/system/health");
  expect(response.ok()).toBe(true);
  expect(await response.json()).toMatchObject({ status: "UP" });
});

test("a tela inicial abre sem erros no console", async ({ page }) => {
  const errors: string[] = [];
  page.on("console", (message) => {
    // Asking who is logged in answers 401 for a visitor; the browser logs it, and it is expected.
    if (
      message.location().url.endsWith("/api/sessions/current") &&
      message.text().includes("401")
    )
      return;
    if (message.type() === "error") errors.push(message.text());
  });
  page.on("pageerror", (error) => errors.push(error.message));

  await page.goto("/");

  // The Home has no heading; with no session it offers the "Entrar" link.
  await expect(page.getByRole("link", { name: "Entrar" })).toBeVisible();
  expect(errors).toEqual([]);
});
