import { z } from "zod";
import { isValidCpf } from "./cpf";
import { ORDER_TEXTS as T } from "./messages";

const orderStatusEnum = z.enum(["PENDING", "EXPIRED", "PAID", "REFUNDED"]);

// Mirrors OrderResponse in the backend. The Pix code is read from the provider on every view; it is never stored.
export const orderSchema = z.object({
  id: z.string(),
  productId: z.string(),
  productTitle: z.string(),
  priceCents: z.number().int(),
  status: orderStatusEnum,
  expiresAt: z.string(),
  secondsToExpire: z.number().int(),
  pixCopyPaste: z.string().nullable(),
  refundRequestedAt: z.string().nullable(),
});

// Mirrors OrderListResponse in the backend; the status is already the shown one (a pending past its time arrives as EXPIRED).
export const orderListSchema = z.object({
  items: z.array(
    z.object({
      id: z.string(),
      productTitle: z.string(),
      priceCents: z.number().int(),
      status: orderStatusEnum,
      createdAt: z.string(),
      expiresAt: z.string(),
      // "yyyy-mm-dd": the last day to ask for a Refund; null unless a paid Order with no request is inside the window.
      refundableUntil: z.string().nullable(),
      refundRequestedAt: z.string().nullable(),
    }),
  ),
});

export const refundRequestedSchema = z.object({ requestedAt: z.string() });

export const orderStatusSchema = z.object({ status: orderStatusEnum });

export type OrderList = z.infer<typeof orderListSchema>;

export type Order = z.infer<typeof orderSchema>;

export const cpfFormSchema = z.object({
  cpf: z
    .string()
    .trim()
    .min(1, T.cpfEmpty)
    .refine(isValidCpf, { message: T.cpfInvalid }),
});

export type CpfForm = z.infer<typeof cpfFormSchema>;

export const REFUND_REASONS = [
  "NOT_AS_EXPECTED",
  "DEFECTIVE_FILE",
  "BOUGHT_BY_MISTAKE",
  "OTHER",
] as const;

export type RefundReason = (typeof REFUND_REASONS)[number];

// The 500 limit mirrors RefundRequestService in the backend (it validates there, so no OpenAPI maxLength). Zod 4 counts code points, like the backend and the database.
export const refundFormSchema = z.object({
  reason: z.enum(REFUND_REASONS, { error: T.reasonRequired }),
  details: z.string().max(500, T.detailsTooLong),
});

export type RefundForm = z.infer<typeof refundFormSchema>;
