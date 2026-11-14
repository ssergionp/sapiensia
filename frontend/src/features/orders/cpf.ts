// Same rules as the backend Cpf: strip dots, dashes and whitespace; 11 digits, not all equal, both check digits.
export function normalizeCpf(text: string): string {
  return text.replace(/[.\-\s]/g, "");
}

function checkDigit(digits: string, length: number): number {
  let sum = 0;
  for (let i = 0; i < length; i++) {
    sum += Number(digits[i]) * (length + 1 - i);
  }
  const rest = (sum * 10) % 11;
  return rest === 10 ? 0 : rest;
}

export function isValidCpf(text: string): boolean {
  const digits = normalizeCpf(text);
  if (!/^[0-9]{11}$/.test(digits)) return false;
  if (new Set(digits).size === 1) return false;
  return (
    checkDigit(digits, 9) === Number(digits[9]) &&
    checkDigit(digits, 10) === Number(digits[10])
  );
}
