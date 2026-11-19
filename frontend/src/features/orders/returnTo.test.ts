import { afterEach, describe, expect, test, vi } from "vitest";
import { hasReturnTo, rememberReturnTo, takeReturnTo } from "./returnTo";

const ID = "3f2b8c1e-4d5a-4b6c-9d7e-1a2b3c4d5e6f";
const KEY = "sapiensia.returnTo";

afterEach(() => {
  localStorage.clear();
  vi.restoreAllMocks();
});

describe("returnTo", () => {
  test.each([`/catalogo/${ID}`, `/pedidos/${ID}`])(
    "SEC-012: aceita só /catalogo/<uuid> e /pedidos/<uuid>: %s",
    (path) => {
      rememberReturnTo(path);
      expect(takeReturnTo()).toBe(path);
    },
  );

  test("SEC-012: aceita /pedidos/<uuid>/reembolso", () => {
    rememberReturnTo(`/pedidos/${ID}/reembolso`);
    expect(takeReturnTo()).toBe(`/pedidos/${ID}/reembolso`);
  });

  test("SEC-012: aceita /biblioteca", () => {
    rememberReturnTo("/biblioteca");
    expect(takeReturnTo()).toBe("/biblioteca");
  });

  test.each([
    "/biblioteca/x",
    "/biblioteca?a=1",
    "https://evil.test",
    "//evil.test",
    "/catalogo/../painel",
    "javascript:alert(1)",
    "/catalogo/abc",
    `/painel/${ID}`,
    `/catalogo/${ID}/x`,
    `/catalogo/${ID}?a=1`,
  ])("SEC-012: recusa %s", (path) => {
    rememberReturnTo(path);
    expect(takeReturnTo()).toBeNull();
  });

  test("SEC-012: recusa texto que não é JSON", () => {
    localStorage.setItem(KEY, `/catalogo/${ID}`);
    expect(takeReturnTo()).toBeNull();
  });

  test("vence em 60 minutos", () => {
    rememberReturnTo(`/catalogo/${ID}`);
    const stored = JSON.parse(localStorage.getItem(KEY) as string);
    expect(takeReturnTo(stored.at + 60 * 60 * 1000 + 1)).toBeNull();
    rememberReturnTo(`/catalogo/${ID}`);
    const again = JSON.parse(localStorage.getItem(KEY) as string);
    expect(takeReturnTo(again.at + 59 * 60 * 1000)).toBe(`/catalogo/${ID}`);
  });

  test("é apagado ao ser lido", () => {
    rememberReturnTo(`/catalogo/${ID}`);
    expect(hasReturnTo()).toBe(true);
    takeReturnTo();
    expect(hasReturnTo()).toBe(false);
    expect(takeReturnTo()).toBeNull();
  });

  test("hasReturnTo só vale para valor válido e dentro do prazo, sem apagar", () => {
    rememberReturnTo(`/catalogo/${ID}`);
    const stored = JSON.parse(localStorage.getItem(KEY) as string);
    expect(hasReturnTo(stored.at + 59 * 60 * 1000)).toBe(true);
    expect(hasReturnTo(stored.at + 60 * 60 * 1000 + 1)).toBe(false);
    expect(localStorage.getItem(KEY)).not.toBeNull();
    expect(takeReturnTo(stored.at)).toBe(`/catalogo/${ID}`);
  });

  test.each(["https://evil.test", "nao e json"])(
    "SEC-012: hasReturnTo é falso para valor inválido: %s",
    (value) => {
      localStorage.setItem(
        KEY,
        value.startsWith("http")
          ? JSON.stringify({ path: value, at: Date.now() })
          : value,
      );
      expect(hasReturnTo()).toBe(false);
    },
  );

  test("não quebra quando o localStorage lança", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "removeItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    expect(() => rememberReturnTo(`/catalogo/${ID}`)).not.toThrow();
    expect(hasReturnTo()).toBe(false);
    expect(takeReturnTo()).toBeNull();
  });
});
