import { expect, test } from "@playwright/test";
import { expectNoA11yViolations } from "./a11y";

// Needs no server: the page is built in the browser with setContent.
const page = (img: string) =>
  `<!doctype html><html lang="pt-BR"><head><title>t</title></head><body><main>${img}</main></body></html>`;
const GIF = "data:image/gif;base64,R0lGODlhAQABAAAAACw=";

test("NFR-005: o helper do axe acusa uma violação conhecida", async ({
  page: browserPage,
}) => {
  await browserPage.setContent(page(`<img src="${GIF}">`));
  await expect(expectNoA11yViolations(browserPage, "teste")).rejects.toThrow(
    "image-alt",
  );

  await browserPage.setContent(page(`<img src="${GIF}" alt="x">`));
  await expect(
    expectNoA11yViolations(browserPage, "teste"),
  ).resolves.toBeUndefined();
});
