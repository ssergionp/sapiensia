import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { mockSystemTheme, resetTheme } from "@/test/systemTheme";
import { useTheme } from "./theme";

const isDark = () => document.documentElement.classList.contains("dark");

describe("useTheme", () => {
  afterEach(resetTheme);

  it("sem escolha salva, acompanha a mudança do sistema", () => {
    const system = mockSystemTheme(false);
    const { result } = renderHook(() => useTheme());
    expect(result.current.theme).toBe("light");

    act(() => system.setDark(true));

    expect(result.current.theme).toBe("dark");
    expect(isDark()).toBe(true);
  });

  it("valor salvo que não é claro nem escuro não conta como escolha: segue o sistema", () => {
    const system = mockSystemTheme(false);
    localStorage.setItem("sapiensia-theme", "auto");
    const { result } = renderHook(() => useTheme());

    act(() => system.setDark(true));

    expect(result.current.theme).toBe("dark");
    expect(isDark()).toBe(true);
  });

  it("com escolha salva, ignora a mudança do sistema", () => {
    const system = mockSystemTheme(false);
    localStorage.setItem("sapiensia-theme", "light");
    const { result } = renderHook(() => useTheme());

    act(() => system.setDark(true));

    expect(result.current.theme).toBe("light");
    expect(isDark()).toBe(false);
  });
});
