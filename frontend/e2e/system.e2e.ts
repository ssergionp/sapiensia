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
    if (message.type() === "error") errors.push(message.text());
  });
  page.on("pageerror", (error) => errors.push(error.message));

  await page.goto("/");

  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  expect(errors).toEqual([]);
});
