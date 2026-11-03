import { z } from "zod";

// Login accepts any non-empty password (no strength rules); only the backend maximum of 128 characters is mirrored.
export const loginSchema = z.object({
  email: z
    .string()
    .trim()
    .pipe(
      z
        .email("Informe um e-mail válido.")
        .max(254, "Informe um e-mail válido."),
    ),
  password: z
    .string()
    .min(1, "Informe a senha.")
    .max(128, "A senha pode ter no máximo 128 caracteres."),
});

export type LoginInput = z.infer<typeof loginSchema>;
