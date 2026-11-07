import { z } from "zod";

// Mirrors the backend response of GET /api/admin/sessions/current.
export const adminSessionSchema = z.object({
  email: z.string(),
  capabilities: z.array(z.string()),
});

export type AdminSession = z.infer<typeof adminSessionSchema>;

// Spaces are removed first so a code pasted as "123 456" is accepted; the backend stays strict (6 digits).
export const mfaCodeSchema = z.object({
  code: z
    .string()
    .transform((value) => value.replace(/\s/g, ""))
    .pipe(z.string().regex(/^\d{6}$/, "Digite os 6 dígitos do código.")),
});

export type MfaCodeInput = z.input<typeof mfaCodeSchema>;
export type MfaCodeOutput = z.output<typeof mfaCodeSchema>;

// Mirrors the backend response of GET /api/admin/sessions/mfa.
export const mfaStatusSchema = z.object({ enrolled: z.boolean() });
export type MfaStatus = z.infer<typeof mfaStatusSchema>;

// Mirrors the backend response of POST /api/admin/sessions/mfa/enrollment.
export const mfaEnrollmentSchema = z.object({
  secret: z.string(),
  otpauthUri: z.string(),
});
export type MfaEnrollment = z.infer<typeof mfaEnrollmentSchema>;
