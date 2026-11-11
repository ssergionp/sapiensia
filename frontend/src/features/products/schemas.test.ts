import { describe, expect, test } from "vitest";
import {
  centsToInput,
  formatCents,
  parsePriceToCents,
  productDataSchema,
  productPageSchema,
} from "./schemas";

describe("productPageSchema", () => {
  test("a list item without file or description parses (the backend list has neither)", () => {
    const page = productPageSchema.parse({
      items: [
        {
          id: "p1",
          title: "Guia",
          category: "HEALTH",
          priceCents: 1990,
          status: "DRAFT",
          firstPublishedAt: null,
          version: 1,
          createdAt: "2026-01-01T00:00:00Z",
          updatedAt: "2026-01-01T00:00:00Z",
        },
      ],
      page: 0,
      totalPages: 1,
    });
    expect(page.items).toHaveLength(1);
  });
});

describe("parsePriceToCents", () => {
  test.each([
    ["19,90", 1990],
    ["19", 1900],
    ["5", 500],
    ["5,00", 500],
    ["49,90", 4990],
    ["9999,99", 999999],
    ["0019,90", 1990],
    ["  19,90  ", 1990],
  ])("FR-018: accepts %j as %i cents", (text, cents) => {
    expect(parsePriceToCents(text)).toEqual({ ok: true, cents });
  });

  test.each([
    ["49.90"],
    ["19.90"],
    ["1.990"],
    ["1.990,00"],
    ["1,990"],
    ["19,"],
    [",90"],
    ["19,9"],
    ["19,999"],
    ["abc"],
    ["-5"],
    ["+5"],
    ["1e3"],
    ["1 9,90"],
    ["R$ 19,90"],
    ["19,90,1"],
  ])(
    "FR-018: rejects %j as badly written (a thousand separator such as 1.990,00 is refused on purpose)",
    (text) => {
      expect(parsePriceToCents(text)).toEqual({ ok: false, error: "invalid" });
    },
  );

  test("FR-018: 49.90 is never read as 4990 reais", () => {
    expect(parsePriceToCents("49.90")).not.toMatchObject({ ok: true });
  });

  test("empty and blank are required", () => {
    expect(parsePriceToCents("")).toEqual({ ok: false, error: "required" });
    expect(parsePriceToCents("   ")).toEqual({ ok: false, error: "required" });
  });

  test("FR-018: checks the range after parsing", () => {
    expect(parsePriceToCents("4,99")).toEqual({ ok: false, error: "low" });
    expect(parsePriceToCents("0")).toEqual({ ok: false, error: "low" });
    expect(parsePriceToCents("10000")).toEqual({ ok: false, error: "high" });
    expect(parsePriceToCents("10000,00")).toEqual({ ok: false, error: "high" });
    expect(parsePriceToCents("99999999999999999999")).toEqual({
      ok: false,
      error: "high",
    });
  });
});

describe("money display", () => {
  test("formatCents uses the pt-BR currency format", () => {
    expect(formatCents(1990).replace(/\s/g, " ")).toBe("R$ 19,90");
    expect(formatCents(999999).replace(/\s/g, " ")).toBe("R$ 9.999,99");
    expect(formatCents(500).replace(/\s/g, " ")).toBe("R$ 5,00");
  });

  test("centsToInput builds the field text with integer math", () => {
    expect(centsToInput(1990)).toBe("19,90");
    expect(centsToInput(500)).toBe("5,00");
    expect(centsToInput(1905)).toBe("19,05");
    expect(centsToInput(999999)).toBe("9999,99");
  });

  test.each([
    [1, "0,01", "R$ 0,01"],
    [5, "0,05", "R$ 0,05"],
    [99, "0,99", "R$ 0,99"],
    [100, "1,00", "R$ 1,00"],
    [123456789, "1234567,89", "R$ 1.234.567,89"],
    [Number.MAX_SAFE_INTEGER, "90071992547409,91", "R$ 90.071.992.547.409,91"],
  ])("cents %i is written exactly", (cents, input, display) => {
    expect(centsToInput(cents)).toBe(input);
    expect(formatCents(cents).replace(/\s/g, " ")).toBe(display);
  });

  test("the field text round-trips", () => {
    for (const cents of [500, 1990, 1905, 4990, 123456, 999999]) {
      expect(parsePriceToCents(centsToInput(cents))).toEqual({
        ok: true,
        cents,
      });
    }
  });
});

describe("productDataSchema", () => {
  const valid = { title: "Guia", description: "Texto", category: "HEALTH" };
  const messageOf = (input: object) => {
    const result = productDataSchema.safeParse(input);
    return result.success ? null : result.error.issues[0].message;
  };

  test("accepts valid data", () => {
    expect(productDataSchema.safeParse(valid).success).toBe(true);
  });

  test("title rules", () => {
    expect(messageOf({ ...valid, title: "   " })).toBe("Digite o título.");
    expect(messageOf({ ...valid, title: "a".repeat(120) })).toBeNull();
    expect(messageOf({ ...valid, title: "a".repeat(121) })).toBe(
      "O título pode ter até 120 caracteres.",
    );
    for (const bad of [
      "a\u0000b",
      "a\nb",
      "a\u202Eb",
      "a\u200Bb",
      "a\uFEFFb",
    ]) {
      expect(messageOf({ ...valid, title: bad })).toBe(
        "O título tem caracteres que não podem ser usados.",
      );
    }
  });

  test("description rules", () => {
    expect(messageOf({ ...valid, description: "" })).toBe(
      "Digite a descrição.",
    );
    expect(messageOf({ ...valid, description: "a\nb\tc\r" })).toBeNull();
    expect(messageOf({ ...valid, description: "a".repeat(5001) })).toBe(
      "A descrição pode ter até 5.000 caracteres.",
    );
    for (const bad of ["a\u0000b", "a\u2066b", "a\u2060b", "a\u202Ab"]) {
      expect(messageOf({ ...valid, description: bad })).toBe(
        "A descrição tem caracteres que não podem ser usados.",
      );
    }
  });

  test("rejects more invisible and formatting characters, keeps ZWJ and ZWNJ", () => {
    const forbidden = [
      0x200e, 0x200f, 0x061c, 0x00ad, 0x2061, 0x2064, 0x206a, 0x206f, 0xfff9,
      0xfffb, 0xe0000, 0xe0041, 0xe007f,
    ];
    for (const code of forbidden) {
      const text = `a${String.fromCodePoint(code)}b`;
      expect(messageOf({ ...valid, title: text })).toBe(
        "O título tem caracteres que não podem ser usados.",
      );
      expect(messageOf({ ...valid, description: text })).toBe(
        "A descrição tem caracteres que não podem ser usados.",
      );
    }
    expect(messageOf({ ...valid, title: "a‍b‌c" })).toBeNull();
    expect(messageOf({ ...valid, description: "a‍b‌c" })).toBeNull();
  });

  test("limits count the NFC-normalized, trimmed text", () => {
    const decomposed = "é"; // 2 UTF-16 units, 1 after NFC
    expect(messageOf({ ...valid, title: decomposed.repeat(120) })).toBeNull();
    expect(messageOf({ ...valid, title: decomposed.repeat(121) })).toBe(
      "O título pode ter até 120 caracteres.",
    );
    expect(
      messageOf({ ...valid, description: decomposed.repeat(5000) }),
    ).toBeNull();
    expect(messageOf({ ...valid, description: decomposed.repeat(5001) })).toBe(
      "A descrição pode ter até 5.000 caracteres.",
    );
  });

  test("category is required", () => {
    expect(messageOf({ ...valid, category: "" })).toBe(
      "Selecione uma categoria.",
    );
    expect(messageOf({ ...valid, category: "NOPE" })).toBe(
      "Selecione uma categoria.",
    );
  });
});
