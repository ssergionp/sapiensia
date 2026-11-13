import { z } from "zod";
import { PRODUCT_CATEGORIES } from "@/features/products/schemas";

// Public Catalog shapes: only what the backend serves to visitors (no status, version or file data).
export const catalogItemSchema = z.object({
  id: z.string(),
  title: z.string(),
  summary: z.string(),
  category: z.enum(PRODUCT_CATEGORIES),
  priceCents: z.number().int(),
  coverVersion: z.string().nullable(),
});

export const catalogPageSchema = z.object({
  items: z.array(catalogItemSchema),
  page: z.number().int(),
  totalPages: z.number().int(),
});

export const catalogProductSchema = z.object({
  id: z.string(),
  title: z.string(),
  description: z.string(),
  category: z.enum(PRODUCT_CATEGORIES),
  priceCents: z.number().int(),
  coverVersion: z.string().nullable(),
});

export function coverUrl(id: string, coverVersion: string | null) {
  return coverVersion === null
    ? null
    : `/api/products/${encodeURIComponent(id)}/cover?v=${encodeURIComponent(coverVersion)}`;
}

export type CatalogItem = z.infer<typeof catalogItemSchema>;
export type CatalogPage = z.infer<typeof catalogPageSchema>;
export type CatalogProduct = z.infer<typeof catalogProductSchema>;
