import "@testing-library/jest-dom/vitest";

// jsdom has no matchMedia (every real browser does): default to a light system theme.
// Tests that need another system theme use mockSystemTheme from ./systemTheme.
Object.defineProperty(window, "matchMedia", {
  configurable: true,
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }),
});
