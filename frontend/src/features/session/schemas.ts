import { z } from "zod";

// Login accepts any non-empty password (no strength or length rules on the client).
export const loginSchema = z.object({
  email: z
    .string()
    .trim()
    .pipe(
      z
        .email("Informe um e-mail válido.")
        .max(254, "Informe um e-mail válido."),
    ),
  password: z.string().min(1, "Informe a senha."),
});

export type LoginInput = z.infer<typeof loginSchema>;
