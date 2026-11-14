import { z } from "zod";

// Mirrors the library item of the backend: what the Customer owns, most recent first.
export const libraryItemSchema = z.object({
  productId: z.string(),
  title: z.string(),
  grantedAt: z.string(),
  coverVersion: z.string().nullable(),
});

export const libraryResponseSchema = z.object({
  items: z.array(libraryItemSchema),
});

export type LibraryItem = z.infer<typeof libraryItemSchema>;
export type LibraryResponse = z.infer<typeof libraryResponseSchema>;

export function libraryCoverUrl(
  productId: string,
  coverVersion: string | null,
) {
  return coverVersion === null
    ? null
    : `/api/library/${encodeURIComponent(productId)}/cover?v=${encodeURIComponent(coverVersion)}`;
}
