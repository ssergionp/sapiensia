// Applies the theme before the first paint so a dark page never flashes light.
// A file of its own (not inline in index.html) because the CSP is script-src 'self'.
// Keep in sync with THEME_STORAGE_KEY in src/lib/theme.ts.
(() => {
  let stored = null;
  try {
    stored = localStorage.getItem("sapiensia-theme");
  } catch {
    // storage blocked (private window, site data blocked): follow the system
  }
  const dark =
    stored === "dark" ||
    (stored !== "light" &&
      window.matchMedia("(prefers-color-scheme: dark)").matches);
  document.documentElement.classList.toggle("dark", dark);
})();
