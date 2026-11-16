import type { formatViolations as FormatViolations } from "./a11y";
import { formatViolations, unresolved } from "./a11y";

type AxeResult = Parameters<typeof FormatViolations>[0][number];

function violation(id: string, targets: string[] = ["#a"]): AxeResult {
  return {
    id,
    impact: "serious",
    tags: [],
    description: "d",
    help: "h",
    helpUrl: `https://dequeuniversity.com/rules/axe/4.14/${id}`,
    nodes: targets.map((t) => ({
      html: '<a href="#">cliente@example.com</a>',
      target: [t],
      failureSummary: "Fix any of the following: cliente@example.com",
    })),
  } as unknown as AxeResult;
}

describe("e2e a11y helper", () => {
  it("lists id, impact and helpUrl of each violation", () => {
    const text = formatViolations([violation("color-contrast")]);
    expect(text).toContain("color-contrast");
    expect(text).toContain("serious");
    expect(text).toContain(
      "https://dequeuniversity.com/rules/axe/4.14/color-contrast",
    );
  });

  it("lists at most 5 selectors per violation", () => {
    const text = formatViolations([
      violation("label", ["#1", "#2", "#3", "#4", "#5", "#6", "#7"]),
    ]);
    expect(text).toContain("#5");
    expect(text).not.toContain("#6");
  });

  it("never prints node html or failure summary", () => {
    const text = formatViolations([violation("link-name")]);
    expect(text).not.toContain("cliente@example.com");
    expect(text).not.toContain("<a");
  });

  it("ignores a known issue on the same screen", () => {
    const known = [{ rule: "color-contrast", screen: "Catalogo", issue: 1 }];
    expect(
      unresolved([violation("color-contrast")], "Catalogo", known),
    ).toEqual([]);
  });

  it("keeps the same rule on another screen", () => {
    const known = [{ rule: "color-contrast", screen: "Catalogo", issue: 1 }];
    const v = violation("color-contrast");
    expect(unresolved([v], "Biblioteca", known)).toEqual([v]);
  });

  it("keeps other rules on a known screen", () => {
    const known = [{ rule: "color-contrast", screen: "Catalogo", issue: 1 }];
    const v = violation("label");
    expect(unresolved([v], "Catalogo", known)).toEqual([v]);
  });
});
