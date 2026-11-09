import { z } from "zod";

export const PRODUCT_CATEGORIES = [
  "HEALTH",
  "LAW",
  "FINANCE",
  "SECURITY",
  "EDUCATION",
  "TECHNOLOGY",
  "BUSINESS",
  "OTHER",
] as const;

export type ProductCategory = (typeof PRODUCT_CATEGORIES)[number];

// Mirrors ProductResponse and ProductPage in the backend.
export const productSchema = z.object({
  id: z.string(),
  title: z.string(),
  description: z.string(),
  category: z.enum(PRODUCT_CATEGORIES),
  priceCents: z.number().int(),
  status: z.string(),
  version: z.number().int(),
  createdAt: z.string(),
  updatedAt: z.string(),
});

export type Product = z.infer<typeof productSchema>;

export const productPageSchema = z.object({
  items: z.array(productSchema),
  page: z.number().int(),
  totalPages: z.number().int(),
});

export type ProductPage = z.infer<typeof productPageSchema>;

export const MIN_PRICE_CENTS = 500;
export const MAX_PRICE_CENTS = 999_999;

export type PriceParseResult =
  | { ok: true; cents: number }
  | { ok: false; error: "required" | "invalid" | "low" | "high" };

// Text to integer cents with string handling only: no float is ever involved. Only "19" and "19,90" shapes
// are accepted; "49.90" or "1.990" are ambiguous and refused instead of being read as another amount.
export function parsePriceToCents(text: string): PriceParseResult {
  const value = text.trim();
  if (value === "") return { ok: false, error: "required" };
  const match = /^(\d+)(?:,(\d{2}))?$/.exec(value);
  if (!match) return { ok: false, error: "invalid" };
  const reais = match[1].replace(/^0+(?=\d)/, "");
  if (reais.length > 7) return { ok: false, error: "high" };
  const cents = Number.parseInt(reais + (match[2] ?? "00"), 10);
  if (cents < MIN_PRICE_CENTS) return { ok: false, error: "low" };
  if (cents > MAX_PRICE_CENTS) return { ok: false, error: "high" };
  return { ok: true, cents };
}

// "19,90" from 1990, by integer math.
export function centsToInput(cents: number): string {
  const reais = Math.trunc(cents / 100);
  const rest = cents % 100;
  return `${reais},${rest < 10 ? "0" : ""}${rest}`;
}

const currency = new Intl.NumberFormat("pt-BR", {
  style: "currency",
  currency: "BRL",
});

// Display only: the division is never used to compute or send a value.
export function formatCents(cents: number): string {
  return currency.format(cents / 100);
}

export const PRICE_MESSAGES = {
  required: "Digite o preço.",
  invalid: "Digite um preço válido, como 19,90.",
  low: "O preço mínimo é R$ 5,00.",
  high: "O preço máximo é R$ 9.999,99.",
} as const;

export const productPriceSchema = z.object({
  price: z.string().superRefine((value, ctx) => {
    const result = parsePriceToCents(value);
    if (!result.ok)
      ctx.addIssue({ code: "custom", message: PRICE_MESSAGES[result.error] });
  }),
});

export type ProductPriceInput = z.infer<typeof productPriceSchema>;

// Control characters (except \t \n \r in the description), lone surrogates, bidi controls and invisible characters.
const TITLE_FORBIDDEN =
  /[\p{Cc}\p{Cs}\u{202A}-\u{202E}\u{2066}-\u{2069}\u{200B}\u{2060}\u{FEFF}]/u;
const DESCRIPTION_FORBIDDEN =
  /[\p{Cs}\u{202A}-\u{202E}\u{2066}-\u{2069}\u{200B}\u{2060}\u{FEFF}]|(?![\t\n\r])\p{Cc}/u;

function textField(
  forbidden: RegExp,
  max: number,
  messages: { required: string; tooLong: string; invalid: string },
) {
  return z.string().superRefine((value, ctx) => {
    if (value.trim() === "") {
      ctx.addIssue({ code: "custom", message: messages.required });
    } else if (value.trim().length > max) {
      ctx.addIssue({ code: "custom", message: messages.tooLong });
    } else if (forbidden.test(value)) {
      ctx.addIssue({ code: "custom", message: messages.invalid });
    }
  });
}

export const productDataSchema = z.object({
  title: textField(TITLE_FORBIDDEN, 120, {
    required: "Digite o título.",
    tooLong: "O título pode ter até 120 caracteres.",
    invalid: "O título tem caracteres que não podem ser usados.",
  }),
  description: textField(DESCRIPTION_FORBIDDEN, 5000, {
    required: "Digite a descrição.",
    tooLong: "A descrição pode ter até 5.000 caracteres.",
    invalid: "A descrição tem caracteres que não podem ser usados.",
  }),
  category: z.enum(PRODUCT_CATEGORIES, {
    error: "Selecione uma categoria.",
  }),
});

export type ProductDataInput = z.input<typeof productDataSchema>;
