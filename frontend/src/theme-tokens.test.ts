/// <reference types="node" />
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

// vitest hands CSS imports (even ?raw) back empty and runs from frontend/: read the file itself
const css = readFileSync(resolve("src/index.css"), "utf8");

function tokens(selector: ":root" | ".dark"): Record<string, string> {
  const start = css.search(
    new RegExp(`^${selector.replace(".", "\\.")} \\{`, "m"),
  );
  const block = css.slice(start, css.indexOf("}", start));
  const result: Record<string, string> = {};
  for (const [, name, value] of block.matchAll(/--([\w-]+):\s*([^;]+);/g)) {
    // Biome writes hex in lowercase; compare in uppercase like DESIGN.md
    result[name] = value.trim().toUpperCase();
  }
  return result;
}

function rgb(hex: string): [number, number, number] {
  const match = /^#([0-9a-f]{6})$/i.exec(hex);
  if (!match) throw new Error(`not a 6-digit hex color: ${hex}`);
  return [0, 2, 4].map((i) =>
    Number.parseInt(match[1].slice(i, i + 2), 16),
  ) as [number, number, number];
}

function luminance(hex: string): number {
  const [r, g, b] = rgb(hex).map((v) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

function isPurple(hex: string): boolean {
  const [r, g, b] = rgb(hex).map((v) => v / 255);
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const delta = max - min;
  const lightness = (max + min) / 2;
  const saturation =
    delta === 0 ? 0 : delta / (1 - Math.abs(2 * lightness - 1));
  if (delta === 0) return false;
  let hue =
    max === r
      ? ((g - b) / delta) % 6
      : max === g
        ? (b - r) / delta + 2
        : (r - g) / delta + 4;
  hue = (hue * 60 + 360) % 360;
  return saturation > 0.2 && hue >= 250 && hue <= 320;
}

const themes = [
  ["claro", tokens(":root")],
  ["escuro", tokens(".dark")],
] as const;

describe("tokens de cor do tema", () => {
  it("tema claro e escuro usam as cores da marca", () => {
    expect(tokens(":root")).toMatchObject({
      background: "#E9E8E2",
      card: "#FAF6F0",
      foreground: "#121615",
      primary: "#114B3E",
      "primary-foreground": "#FAF6F0",
    });
    expect(tokens(".dark")).toMatchObject({
      background: "#0D110F",
      card: "#121615",
      foreground: "#FAF6F0",
      primary: "#F08A4B",
      "primary-foreground": "#121615",
    });
  });

  it.each(themes)(
    "texto comum tem contraste AA e botão de ação AAA no tema %s",
    (_, t) => {
      for (const surface of [t.background, t.card]) {
        expect(contrast(t.foreground, surface)).toBeGreaterThanOrEqual(4.5);
        expect(contrast(t["muted-foreground"], surface)).toBeGreaterThanOrEqual(
          4.5,
        );
        expect(contrast(t.destructive, surface)).toBeGreaterThanOrEqual(4.5);
      }
      expect(
        contrast(t["primary-foreground"], t.primary),
      ).toBeGreaterThanOrEqual(7);
      expect(contrast(t.primary, t.background)).toBeGreaterThanOrEqual(4.5);
    },
  );

  it.each(themes)("nenhum token do tema %s usa roxo", (_, t) => {
    const colors = Object.values(t).filter((v) => v.startsWith("#"));
    expect(colors.length).toBeGreaterThan(10);
    expect(
      colors.filter((c) => /^#[0-9a-f]{6}$/i.test(c) && isPurple(c)),
    ).toEqual([]);
  });
});
