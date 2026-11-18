import type { StatusTone } from "@/components/StatusBadge";
import type { ProductStatus } from "./schemas";

// Draft neutral, Published in Pinho, Unpublished neutral with a border (DESIGN.md)
export const PRODUCT_STATUS_TONE: Record<ProductStatus, StatusTone> = {
  DRAFT: "neutral",
  PUBLISHED: "success",
  UNPUBLISHED: "outline",
};
