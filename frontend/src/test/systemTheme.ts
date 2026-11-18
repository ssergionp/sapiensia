import { vi } from "vitest";

const QUERY = "(prefers-color-scheme: dark)";

// jsdom has no matchMedia: simulates the operating system theme and lets a test flip it
export function mockSystemTheme(initiallyDark: boolean): {
  setDark: (dark: boolean) => void;
} {
  let dark = initiallyDark;
  const listeners = new Set<(event: MediaQueryListEvent) => void>();
  vi.stubGlobal(
    "matchMedia",
    vi.fn((query: string) => ({
      get matches() {
        return query === QUERY && dark;
      },
      media: query,
      onchange: null,
      addEventListener: (_: string, l: (event: MediaQueryListEvent) => void) =>
        listeners.add(l),
      removeEventListener: (
        _: string,
        l: (event: MediaQueryListEvent) => void,
      ) => listeners.delete(l),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  );
  return {
    setDark(next) {
      dark = next;
      for (const l of listeners) {
        l({ matches: next, media: QUERY } as MediaQueryListEvent);
      }
    },
  };
}

export function resetTheme(): void {
  document.documentElement.classList.remove("dark");
  localStorage.clear();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
}
