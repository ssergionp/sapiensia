// Every call to the backend goes through apiFetch: it sends the session cookie and, on requests that change
// data (POST, PUT, PATCH, DELETE), the CSRF token that Spring Security left in the XSRF-TOKEN cookie.

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

function readCookie(name: string): string | undefined {
  const prefix = `${name}=`;
  const found = document.cookie
    .split("; ")
    .find((part) => part.startsWith(prefix));
  return found ? decodeURIComponent(found.slice(prefix.length)) : undefined;
}

export function apiFetch(path: string, init: RequestInit = {}) {
  const method = (init.method ?? "GET").toUpperCase();
  const headers = new Headers(init.headers);
  if (!SAFE_METHODS.has(method)) {
    const token = readCookie("XSRF-TOKEN");
    if (token) headers.set("X-XSRF-TOKEN", token);
  }
  return fetch(path, { ...init, method, headers, credentials: "same-origin" });
}
