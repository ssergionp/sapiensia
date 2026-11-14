import { z } from "zod";
import { isValidCpf } from "./cpf";
import { ORDER_TEXTS as T } from "./messages";

// Mirrors OrderResponse in the backend. The Pix code is read from the provider on every view; it is never stored.
export const orderSchema = z.object({
  id: z.string(),
  productId: z.string(),
  productTitle: z.string(),
  priceCents: z.number().int(),
  status: z.enum(["PENDING", "EXPIRED"]),
  expiresAt: z.string(),
  secondsToExpire: z.number().int(),
  pixCopyPaste: z.string().nullable(),
});

export type Order = z.infer<typeof orderSchema>;

export const cpfFormSchema = z.object({
  cpf: z
    .string()
    .trim()
    .min(1, T.cpfEmpty)
    .refine(isValidCpf, { message: T.cpfInvalid }),
});

export type CpfForm = z.infer<typeof cpfFormSchema>;
