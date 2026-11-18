import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { mockSystemTheme, resetTheme } from "@/test/systemTheme";

// public/theme-init.js runs in <head> before React (a file of its own because the CSP has script-src 'self')
const source = readFileSync(resolve("public/theme-init.js"), "utf8");
const runThemeInit = new Function(source) as () => void;

const isDark = () => document.documentElement.classList.contains("dark");

describe("theme-init.js", () => {
  afterEach(resetTheme);

  it('escolha salva "dark" liga o tema escuro', () => {
    mockSystemTheme(false);
    localStorage.setItem("sapiensia-theme", "dark");
    runThemeInit();
    expect(isDark()).toBe(true);
  });

  it("sem escolha salva, segue o sistema escuro", () => {
    mockSystemTheme(true);
    runThemeInit();
    expect(isDark()).toBe(true);
  });

  it('escolha salva "light" vence o sistema escuro', () => {
    mockSystemTheme(true);
    localStorage.setItem("sapiensia-theme", "light");
    runThemeInit();
    expect(isDark()).toBe(false);
  });

  it("localStorage que lança erro não impede seguir o sistema", () => {
    mockSystemTheme(true);
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });
    expect(runThemeInit).not.toThrow();
    expect(isDark()).toBe(true);
  });
});
