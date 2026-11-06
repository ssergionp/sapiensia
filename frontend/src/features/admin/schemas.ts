import { z } from "zod";

// Mirrors the backend response of GET /api/admin/sessions/current.
export const adminSessionSchema = z.object({
  email: z.string(),
  capabilities: z.array(z.string()),
});

export type AdminSession = z.infer<typeof adminSessionSchema>;
