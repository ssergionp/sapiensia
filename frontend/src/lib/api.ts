// Every call to the backend goes through apiFetch: it sends the session cookie and, on requests that change
// data (POST, PUT, PATCH, DELETE), the CSRF token that Spring Security left in the XSRF-TOKEN cookie. The cookie
// comes from an earlier API response, but not from the public Catalog GETs, which set no cookie (#20); if it is
// missing, apiFetch gets it with a GET /api/system/health first.

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

function readCookie(name: string): string | undefined {
  const prefix = `${name}=`;
  const found = document.cookie
    .split("; ")
    .find((part) => part.startsWith(prefix));
  return found ? decodeURIComponent(found.slice(prefix.length)) : undefined;
}

// The SPA page itself is not served by the API in development, so a visitor may send the first change request
// without the cookie. One GET to the health endpoint makes Spring Security set it.
// Requests that start together share one priming call, so a second response cannot replace the token the first read.
let priming: Promise<void> | undefined;

function primeCsrfCookie(): Promise<void> {
  priming ??= fetch("/api/system/health", {
    method: "GET",
    credentials: "same-origin",
  })
    .then(() => undefined)
    .catch(() => {
      // The original request goes out anyway, as it would without the priming.
    })
    .finally(() => {
      priming = undefined;
    });
  return priming;
}

// A 401 on a customer route means the session ended on the server. Whoever shows the account state (the site
// header) listens and checks the session again. The session itself, the login and the admin routes answer 401
// by design (no session, wrong password, panel flow), so they never notify; that also keeps the check the
// listener starts (GET /api/sessions/current) from notifying again.
const NOT_A_SESSION_END = ["/api/sessions", "/api/admin"];
const unauthorizedListeners = new Set<() => void>();

// Exact route or a sub-route ("/api/sessions", "/api/sessions/current"), never a name that merely starts alike
function endsSessionByDesign(path: string): boolean {
  const route = path.split("?")[0];
  return NOT_A_SESSION_END.some(
    (prefix) => route === prefix || route.startsWith(`${prefix}/`),
  );
}

export function onUnauthorized(listener: () => void): () => void {
  unauthorizedListeners.add(listener);
  return () => {
    unauthorizedListeners.delete(listener);
  };
}

export async function apiFetch(
  path: string,
  init: RequestInit = {},
): Promise<Response> {
  const method = (init.method ?? "GET").toUpperCase();
  const headers = new Headers(init.headers);
  if (!SAFE_METHODS.has(method)) {
    if (readCookie("XSRF-TOKEN") === undefined) await primeCsrfCookie();
    const token = readCookie("XSRF-TOKEN");
    if (token) headers.set("X-XSRF-TOKEN", token);
  }
  const response = await fetch(path, {
    ...init,
    method,
    headers,
    credentials: "same-origin",
  });
  if (
    response.status === 401 &&
    !endsSessionByDesign(path)
  ) {
    for (const listener of unauthorizedListeners) listener();
  }
  return response;
}
