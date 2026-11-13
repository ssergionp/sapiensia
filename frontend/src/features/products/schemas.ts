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

// Mirrors the file summary of ProductResponse: never a key or a URL.
export const productFileSchema = z.object({
  sizeBytes: z.number().int(),
  pageCount: z.number().int(),
  uploadedAt: z.string(),
});

export type ProductFile = z.infer<typeof productFileSchema>;

// Mirrors ProductCoverInfo: never a key or a storage URL.
export const productCoverSchema = z.object({
  mediaType: z.string(),
  sizeBytes: z.number().int(),
  width: z.number().int(),
  height: z.number().int(),
  version: z.string(),
  uploadedAt: z.string(),
});

export type ProductCover = z.infer<typeof productCoverSchema>;

// Codes of the "cover" field in a 400/413 answer of the cover upload.
export const COVER_REJECTION_CODES = [
  "COVER_REQUIRED",
  "COVER_EMPTY",
  "COVER_TOO_LARGE",
  "COVER_NOT_IMAGE",
  "COVER_INVALID",
  "COVER_TOO_MANY_PIXELS",
  "COVER_ANIMATED",
] as const;

export type ProductCoverRejectionCode = (typeof COVER_REJECTION_CODES)[number];

// 2 MB, base 1024. The same limit the backend enforces.
export const MAX_COVER_BYTES = 2_097_152;

export const COVER_MEDIA_TYPES = ["image/png", "image/jpeg", "image/webp"];

// Codes of the "file" field in a 400/409/413 answer of the file upload.
export const FILE_REJECTION_CODES = [
  "FILE_REQUIRED",
  "FILE_EMPTY",
  "FILE_TOO_LARGE",
  "FILE_NOT_PDF",
  "FILE_INVALID",
  "FILE_ENCRYPTED",
  "FILE_TOO_MANY_PAGES",
] as const;

export type ProductFileRejectionCode = (typeof FILE_REJECTION_CODES)[number];

// 50 MB, base 1024. The same limit the backend enforces.
export const MAX_FILE_BYTES = 52_428_800;

// "320 KB" below 1 MB (minimum "1 KB", dot as thousands separator);
// from 1 MB on, "3,4 MB": one decimal, comma, base 1024.
export function formatFileSize(bytes: number): string {
  if (bytes < 1024 * 1024) {
    const kb = Math.max(1, Math.round(bytes / 1024));
    if (kb < 1024) {
      return `${kb.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ".")} KB`;
    }
  }
  const tenths = Math.round((bytes * 10) / (1024 * 1024));
  return `${Math.floor(tenths / 10)},${tenths % 10} MB`;
}

const brasiliaParts = new Intl.DateTimeFormat("pt-BR", {
  timeZone: "America/Sao_Paulo",
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
});

// "09/11/2026 às 14:32" in Brasilia time, whatever the zone of the browser.
export function formatUploadedAt(iso: string): string {
  const parts: Record<string, string> = {};
  for (const part of brasiliaParts.formatToParts(new Date(iso))) {
    parts[part.type] = part.value;
  }
  return `${parts.day}/${parts.month}/${parts.year} às ${parts.hour}:${parts.minute}`;
}

// Mirrors ProductResponse and ProductPage in the backend.
export const productSchema = z.object({
  id: z.string(),
  title: z.string(),
  description: z.string(),
  category: z.enum(PRODUCT_CATEGORIES),
  priceCents: z.number().int(),
  status: z.enum(["DRAFT", "PUBLISHED", "UNPUBLISHED"]),
  firstPublishedAt: z.string().nullable(),
  version: z.number().int(),
  file: productFileSchema.nullable(),
  cover: productCoverSchema.nullable(),
  createdAt: z.string(),
  updatedAt: z.string(),
});

export type Product = z.infer<typeof productSchema>;

export type ProductStatus = Product["status"];

// Mirrors ProductListItem in the backend: the list has no description.
export const productListItemSchema = productSchema.omit({
  description: true,
  file: true,
  cover: true,
  firstPublishedAt: true,
});

export type ProductListItem = z.infer<typeof productListItemSchema>;

export const productPageSchema = z.object({
  items: z.array(productListItemSchema),
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
  const rest = cents % 100;
  const reais = (cents - rest) / 100;
  return `${reais},${rest < 10 ? "0" : ""}${rest}`;
}

const grouping = new Intl.NumberFormat("pt-BR", { maximumFractionDigits: 0 });

// "R$ 19,90" from 1990: the reais and the cents are split by integer math, so no float rounding shows up.
export function formatCents(cents: number): string {
  const rest = cents % 100;
  const reais = (cents - rest) / 100;
  return `R$ ${grouping.format(reais)},${rest < 10 ? "0" : ""}${rest}`;
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
// ZWJ (U+200D) and ZWNJ (U+200C) stay allowed. Mirrors ProductText.FORBIDDEN in the backend.
const INVISIBLE =
  "\\u{202A}-\\u{202E}\\u{2066}-\\u{2069}\\u{200B}\\u{2060}\\u{FEFF}\\u{200E}\\u{200F}\\u{061C}\\u{00AD}\\u{2061}-\\u{2064}\\u{206A}-\\u{206F}\\u{FFF9}-\\u{FFFB}\\u{E0000}-\\u{E007F}";
const TITLE_FORBIDDEN = new RegExp(`[\\p{Cc}\\p{Cs}${INVISIBLE}]`, "u");
const DESCRIPTION_FORBIDDEN = new RegExp(
  `[\\p{Cs}${INVISIBLE}]|(?![\\t\\n\\r])\\p{Cc}`,
  "u",
);

function textField(
  forbidden: RegExp,
  max: number,
  messages: { required: string; tooLong: string; invalid: string },
) {
  return z.string().superRefine((value, ctx) => {
    if (value.trim() === "") {
      ctx.addIssue({ code: "custom", message: messages.required });
    } else if (value.normalize("NFC").trim().length > max) {
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
