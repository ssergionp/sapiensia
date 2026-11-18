import { useCallback, useEffect, useState } from "react";

// Same key as public/theme-init.js, which applies the theme before React starts
export const THEME_STORAGE_KEY = "sapiensia-theme";

export type Theme = "light" | "dark";

const SYSTEM_DARK = "(prefers-color-scheme: dark)";

// Only "light" or "dark" is a choice, as in theme-init.js; anything else means "follow the system"
function readStored(): Theme | null {
  try {
    const stored = localStorage.getItem(THEME_STORAGE_KEY);
    return stored === "light" || stored === "dark" ? stored : null;
  } catch {
    return null;
  }
}

function apply(theme: Theme): void {
  document.documentElement.classList.toggle("dark", theme === "dark");
}

// The <html> class is the source of truth (set by theme-init.js); while the visitor has not chosen,
// the page follows the operating system.
export function useTheme(): { theme: Theme; toggle: () => void } {
  const [theme, setTheme] = useState<Theme>(() =>
    document.documentElement.classList.contains("dark") ? "dark" : "light",
  );
  const [chosen, setChosen] = useState(() => readStored() !== null);

  useEffect(() => {
    if (chosen) return;
    const media = window.matchMedia(SYSTEM_DARK);
    const follow = (event: MediaQueryListEvent) => {
      const next: Theme = event.matches ? "dark" : "light";
      apply(next);
      setTheme(next);
    };
    media.addEventListener("change", follow);
    return () => media.removeEventListener("change", follow);
  }, [chosen]);

  const toggle = useCallback(() => {
    const next: Theme = theme === "dark" ? "light" : "dark";
    apply(next);
    setTheme(next);
    setChosen(true);
    try {
      localStorage.setItem(THEME_STORAGE_KEY, next);
    } catch {
      // storage blocked: the theme still changes for this visit
    }
  }, [theme]);

  return { theme, toggle };
}
