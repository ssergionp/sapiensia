import { z } from "zod";
import { REFUND_REASONS } from "@/features/orders/schemas";

export const REFUND_FILTERS = ["OPEN", "REFUNDED"] as const;
export type RefundFilter = (typeof REFUND_FILTERS)[number];

// Mirrors AdminRefundRequestPage in the backend; customerEmail is already masked by the server.
export const adminRefundPageSchema = z.object({
  items: z.array(
    z.object({
      orderId: z.string(),
      productTitle: z.string(),
      priceCents: z.number().int(),
      customerEmail: z.string(),
      reason: z.enum(REFUND_REASONS),
      details: z.string().nullable(),
      requestedAt: z.string(),
      paidAt: z.string().nullable(),
      downloads: z.number().int(),
      otherRefunds: z.number().int(),
      chargeId: z.string().nullable(),
      refundedAt: z.string().nullable(),
    }),
  ),
  page: z.number().int(),
  totalPages: z.number().int(),
});

export type AdminRefundPage = z.infer<typeof adminRefundPageSchema>;
