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

export async function expectNoA11yViolations(
  page: Page,
  screen: string,
): Promise<void> {
  const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze();
  const remaining = unresolved(results.violations, screen, KNOWN_ISSUES);
  expect(
    remaining.map((v) => v.id),
    `Accessibility violations on "${screen}":\n${formatViolations(remaining)}`,
  ).toEqual([]);
}
