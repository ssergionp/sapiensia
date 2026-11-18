import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { mockSystemTheme, resetTheme } from "@/test/systemTheme";
import ThemeToggle from "./ThemeToggle";

const isDark = () => document.documentElement.classList.contains("dark");

describe("ThemeToggle", () => {
  afterEach(resetTheme);

  it('no tema claro, o botão "Usar tema escuro" liga o escuro e lembra a escolha', async () => {
    mockSystemTheme(false);
    render(<ThemeToggle />);

    await userEvent.click(
      screen.getByRole("button", { name: "Usar tema escuro" }),
    );

    expect(isDark()).toBe(true);
    expect(localStorage.getItem("sapiensia-theme")).toBe("dark");
    expect(
      screen.getByRole("button", { name: "Usar tema claro" }),
    ).toBeInTheDocument();
  });

  it("sem conseguir gravar no navegador, o botão ainda troca o tema", async () => {
    mockSystemTheme(false);
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("blocked", "QuotaExceededError");
    });
    render(<ThemeToggle />);

    await userEvent.click(
      screen.getByRole("button", { name: "Usar tema escuro" }),
    );

    expect(isDark()).toBe(true);
    expect(
      screen.getByRole("button", { name: "Usar tema claro" }),
    ).toBeInTheDocument();
  });
});
