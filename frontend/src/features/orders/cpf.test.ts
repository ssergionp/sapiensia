import { describe, expect, test } from "vitest";
import { isValidCpf, normalizeCpf } from "./cpf";

describe("cpf", () => {
  test.each([
    "52998224725",
    "529.982.247-25",
    " 111.444.777-35 ",
    "529.982.\n247-25",
  ])("SEC-033: accepts the valid CPF %j with or without formatting", (raw) => {
    expect(isValidCpf(raw)).toBe(true);
  });

  test("SEC-033: keeps only the digits", () => {
    expect(normalizeCpf(" 529.982.247-25 ")).toBe("52998224725");
  });

  test.each([
    "52998224724",
    "11111111111",
    "5299822472",
    "529982247255",
    "5299822472a",
    "abc",
    "",
    "   ",
  ])("SEC-033: refuses the invalid CPF %j", (raw) => {
    expect(isValidCpf(raw)).toBe(false);
  });
});
