import { currentStep, totpCode } from "../../e2e/totp";

// RFC 6238 secret "12345678901234567890" in Base32; 6 digits instead of the 8 of the RFC table.
const SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

describe("e2e TOTP helper", () => {
  it.each([
    [1, "287082"],
    [37037036, "081804"],
    [37037037, "050471"],
    [41152263, "005924"],
    [66666666, "279037"],
    [666666666, "353130"],
  ])("RFC 6238 vector for step %i", (step, expected) => {
    expect(totpCode(SECRET, step)).toBe(expected);
  });

  it("accepts a lower-case secret", () => {
    expect(totpCode(SECRET.toLowerCase(), 1)).toBe("287082");
  });

  it("computes the 30 s step", () => {
    expect(currentStep(59_999)).toBe(1);
    expect(currentStep(60_000)).toBe(2);
  });
});
