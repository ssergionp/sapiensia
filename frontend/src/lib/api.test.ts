import { afterEach, expect, test, vi } from "vitest";
import { apiFetch } from "./api";

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
