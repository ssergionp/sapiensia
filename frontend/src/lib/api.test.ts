import { afterEach, expect, test, vi } from "vitest";
import { apiFetch, onUnauthorized } from "./api";

afterEach(() => {
  vi.unstubAllGlobals();
  // biome-ignore lint/suspicious/noDocumentCookie: the test sets and clears the CSRF cookie directly
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
});

function stubFetch() {
  const fetchMock = vi.fn().mockResolvedValue(new Response(null));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

test("sends the CSRF token from the cookie on requests that change data", async () => {
  // biome-ignore lint/suspicious/noDocumentCookie: the test sets the cookie the backend would set
  document.cookie = "XSRF-TOKEN=abc%20123";
  const fetchMock = stubFetch();

  await apiFetch("/api/items", { method: "post", body: "{}" });

  const [, init] = fetchMock.mock.calls[0];
  expect(init.method).toBe("POST");
  expect(init.credentials).toBe("same-origin");
  expect(new Headers(init.headers).get("X-XSRF-TOKEN")).toBe("abc 123");
});

test("does not send the CSRF token on reads", async () => {
  // biome-ignore lint/suspicious/noDocumentCookie: the test sets the cookie the backend would set
  document.cookie = "XSRF-TOKEN=abc";
  const fetchMock = stubFetch();

  await apiFetch("/api/items");

  const [, init] = fetchMock.mock.calls[0];
  expect(new Headers(init.headers).has("X-XSRF-TOKEN")).toBe(false);
});

test("fetches the CSRF cookie first when a change request has none", async () => {
  const fetchMock = vi
    .fn()
    .mockImplementationOnce(async () => {
      // biome-ignore lint/suspicious/noDocumentCookie: the test sets the cookie the backend would set
      document.cookie = "XSRF-TOKEN=fresh-token";
      return new Response(null);
    })
    .mockResolvedValue(new Response(null));
  vi.stubGlobal("fetch", fetchMock);

  await apiFetch("/api/items", { method: "POST", body: "{}" });

  expect(fetchMock).toHaveBeenCalledTimes(2);
  const [primePath, primeInit] = fetchMock.mock.calls[0];
  expect(primePath).toBe("/api/system/health");
  expect(primeInit.method).toBe("GET");
  const [path, init] = fetchMock.mock.calls[1];
  expect(path).toBe("/api/items");
  expect(new Headers(init.headers).get("X-XSRF-TOKEN")).toBe("fresh-token");
});

test("does not fetch the CSRF cookie when it is already present", async () => {
  // biome-ignore lint/suspicious/noDocumentCookie: the test sets the cookie the backend would set
  document.cookie = "XSRF-TOKEN=abc";
  const fetchMock = stubFetch();

  await apiFetch("/api/items", { method: "POST", body: "{}" });

  expect(fetchMock).toHaveBeenCalledTimes(1);
  expect(fetchMock.mock.calls[0][0]).toBe("/api/items");
});

test("never fetches the CSRF cookie before a read", async () => {
  const fetchMock = stubFetch();

  await apiFetch("/api/items");

  expect(fetchMock).toHaveBeenCalledTimes(1);
});

test("still sends the change request when fetching the CSRF cookie fails", async () => {
  const fetchMock = vi
    .fn()
    .mockRejectedValueOnce(new TypeError("network down"))
    .mockResolvedValue(new Response(null));
  vi.stubGlobal("fetch", fetchMock);

  const response = await apiFetch("/api/items", { method: "POST", body: "{}" });

  expect(fetchMock).toHaveBeenCalledTimes(2);
  expect(fetchMock.mock.calls[1][0]).toBe("/api/items");
  expect(response).toBeInstanceOf(Response);
});

test("two change requests started together share one CSRF priming call", async () => {
  const fetchMock = vi
    .fn()
    .mockImplementationOnce(async () => {
      // the cookie only arrives after the response, so a second request started now still sees none
      await new Promise((resolve) => setTimeout(resolve, 10));
      // biome-ignore lint/suspicious/noDocumentCookie: the test sets the cookie the backend would set
      document.cookie = "XSRF-TOKEN=shared-token";
      return new Response(null);
    })
    .mockResolvedValue(new Response(null));
  vi.stubGlobal("fetch", fetchMock);

  await Promise.all([
    apiFetch("/api/items", { method: "POST", body: "{}" }),
    apiFetch("/api/other", { method: "POST", body: "{}" }),
  ]);

  const primes = fetchMock.mock.calls.filter(
    ([path]) => path === "/api/system/health",
  );
  expect(primes).toHaveLength(1);
  const real = fetchMock.mock.calls.filter(
    ([path]) => path !== "/api/system/health",
  );
  expect(real).toHaveLength(2);
  for (const [, init] of real) {
    expect(new Headers(init.headers).get("X-XSRF-TOKEN")).toBe("shared-token");
  }
});

test("tells the listeners about a 401 on a customer route", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue(new Response(null, { status: 401 })),
  );
  const listener = vi.fn();
  const stop = onUnauthorized(listener);

  await apiFetch("/api/library");
  expect(listener).toHaveBeenCalledTimes(1);

  stop();
  await apiFetch("/api/library");
  expect(listener).toHaveBeenCalledTimes(1);
});

test("a 401 on the session, login or admin routes is not an ended session", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue(new Response(null, { status: 401 })),
  );
  const listener = vi.fn();
  const stop = onUnauthorized(listener);

  await apiFetch("/api/sessions/current");
  await apiFetch("/api/sessions", { method: "POST", body: "{}" });
  await apiFetch("/api/admin/orders");
  expect(listener).not.toHaveBeenCalled();
  stop();
});

test("other statuses never reach the listeners", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue(new Response(null, { status: 403 })),
  );
  const listener = vi.fn();
  const stop = onUnauthorized(listener);
  await apiFetch("/api/library");
  expect(listener).not.toHaveBeenCalled();
  stop();
});
