import AxeBuilder from "@axe-core/playwright";
import { expect, type Page } from "@playwright/test";

export type AxeResult = Awaited<
  ReturnType<AxeBuilder["analyze"]>
>["violations"][number];

const WCAG_TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"];
const MAX_SELECTORS = 5;

// A violation we know about and track in an issue; matched by rule AND screen, never by rule alone.
export const KNOWN_ISSUES: ReadonlyArray<{
  rule: string;
  screen: string;
  issue: number;
}> = [];

// Never prints node.html or failureSummary: they can echo page content such as a customer e-mail.
export function formatViolations(violations: AxeResult[]): string {
  return violations
    .map((v) => {
      const selectors = v.nodes
        .slice(0, MAX_SELECTORS)
        .map((n) => `    ${n.target.join(" ")}`)
        .join("\n");
      return `${v.id} (${v.impact ?? "unknown"}): ${v.helpUrl}\n${selectors}`;
    })
    .join("\n");
}

export function unresolved(
  violations: AxeResult[],
  screen: string,
  known: typeof KNOWN_ISSUES,
): AxeResult[] {
  return violations.filter(
    (v) => !known.some((k) => k.rule === v.id && k.screen === screen),
  );
}

// Screen name of the scan in the other theme, e.g. "catalogo-escuro"; also the name KNOWN_ISSUES matches.
export function themedScreen(screen: string, theme: "light" | "dark"): string {
  return `${screen}-${theme === "dark" ? "escuro" : "claro"}`;
}

async function scan(page: Page, screen: string): Promise<void> {
  const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze();
  const remaining = unresolved(results.violations, screen, KNOWN_ISSUES);
  expect(
    remaining.map((v) => v.id),
    `Accessibility violations on "${screen}":\n${formatViolations(remaining)}`,
  ).toEqual([]);
}

// bothThemes (#186): scans, flips the browser color scheme (the page follows it while the visitor has not
// chosen a theme), scans again as "<screen>-escuro" or "<screen>-claro", then flips back.
export async function expectNoA11yViolations(
  page: Page,
  screen: string,
  options: { bothThemes?: boolean } = {},
): Promise<void> {
  await scan(page, screen);
  if (!options.bothThemes) return;

  const html = page.locator("html");
  const storedTheme = await page.evaluate(() =>
    localStorage.getItem("sapiensia-theme"),
  );
  if (storedTheme !== null) {
    throw new Error(
      `bothThemes on "${screen}": the visitor chose a theme (${storedTheme}), so the page no longer follows the browser color scheme`,
    );
  }
  const wasDark = await html.evaluate((el) => el.classList.contains("dark"));
  const other = wasDark ? "light" : "dark";
  try {
    await page.emulateMedia({ colorScheme: other });
    if (other === "dark") await expect(html).toHaveClass(/\bdark\b/);
    else await expect(html).not.toHaveClass(/\bdark\b/);
    // buttons have color transitions: scanning mid-transition measures a color that is never at rest
    await page.waitForFunction(() =>
      document.getAnimations().every((a) => a.playState !== "running"),
    );
    await scan(page, themedScreen(screen, other));
  } finally {
    // back to the original theme even when the scan fails, so later steps on the same page are not affected
    await page.emulateMedia({ colorScheme: wasDark ? "dark" : "light" });
  }
  if (wasDark) await expect(html).toHaveClass(/\bdark\b/);
  else await expect(html).not.toHaveClass(/\bdark\b/);
}
