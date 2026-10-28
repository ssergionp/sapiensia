// Every call to the backend goes through apiFetch: it sends the session cookie and, on requests that change
// data (POST, PUT, PATCH, DELETE), the CSRF token that Spring Security left in the XSRF-TOKEN cookie. The cookie
// comes from any earlier API response; if it is missing, apiFetch gets it with a GET /api/system/health first.

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
async function primeCsrfCookie() {
  try {
    await fetch("/api/system/health", {
      method: "GET",
      credentials: "same-origin",
    });
  } catch {
    // The original request goes out anyway, as it would without the priming.
  }
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
  return fetch(path, { ...init, method, headers, credentials: "same-origin" });
}
