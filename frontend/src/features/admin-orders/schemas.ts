import { z } from "zod";

export const ORDER_STATUSES = ["PENDING", "PAID", "EXPIRED"] as const;
export type OrderStatus = (typeof ORDER_STATUSES)[number];

// Mirrors AdminOrderPage in the backend; customerEmail is already masked by the server.
export const adminOrderPageSchema = z.object({
  items: z.array(
    z.object({
      id: z.string(),
      productTitle: z.string(),
      priceCents: z.number().int(),
      status: z.enum(ORDER_STATUSES),
      customerEmail: z.string(),
      createdAt: z.string(),
      paidAt: z.string().nullable(),
    }),
  ),
  page: z.number().int(),
  totalPages: z.number().int(),
});

export type AdminOrderPage = z.infer<typeof adminOrderPageSchema>;
