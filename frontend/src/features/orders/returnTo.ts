// Where to send the Customer after login (SEC-012). Kept in localStorage, not in the URL, because creating an
// account goes through an e-mail link that opens another tab. Valid for 60 minutes and erased when read.
const KEY = "sapiensia.returnTo";
const MAX_AGE_MS = 60 * 60 * 1000;
const ALLOWED =
  /^(\/(catalogo|pedidos)\/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|\/biblioteca)$/;

export function rememberReturnTo(path: string): void {
  try {
    localStorage.setItem(KEY, JSON.stringify({ path, at: Date.now() }));
  } catch {
    // Storage unavailable: the Customer lands on the home page after login.
  }
}

function read(): string | null {
  try {
    return localStorage.getItem(KEY);
  } catch {
    return null;
  }
}

// Same validation as takeReturnTo, but leaves the stored value in place.
export function hasReturnTo(now: number = Date.now()): boolean {
  const raw = read();
  return raw !== null && validate(raw, now) !== null;
}

export function takeReturnTo(now: number = Date.now()): string | null {
  const raw = read();
  if (raw === null) return null;
  try {
    localStorage.removeItem(KEY);
  } catch {
    // Nothing to do.
  }
  return validate(raw, now);
}

function validate(raw: string, now: number): string | null {
  try {
    const value: unknown = JSON.parse(raw);
    if (typeof value !== "object" || value === null) return null;
    const { path, at } = value as { path?: unknown; at?: unknown };
    if (typeof path !== "string" || typeof at !== "number") return null;
    if (now - at > MAX_AGE_MS || now < at) return null;
    return ALLOWED.test(path) ? path : null;
  } catch {
    return null;
  }
}
