import { z } from "zod";

// Mirrors the backend limits: e-mail up to 254 characters, password from 12 to 128 characters.
export const registerCustomerSchema = z.object({
  email: z
    .email("Informe um e-mail válido.")
    .max(254, "Informe um e-mail válido."),
  password: z
    .string()
    .min(12, "A senha precisa ter pelo menos 12 caracteres.")
    .max(128, "A senha pode ter no máximo 128 caracteres."),
});

export type RegisterCustomerInput = z.infer<typeof registerCustomerSchema>;

// Translates the error codes sent by the backend into the texts shown to the user.
export const errorMessages: Record<string, string> = {
  EMAIL_INVALID: "Informe um e-mail válido.",
  PASSWORD_REQUIRED: "A senha precisa ter pelo menos 12 caracteres.",
  PASSWORD_TOO_SHORT: "A senha precisa ter pelo menos 12 caracteres.",
  PASSWORD_TOO_LONG: "A senha pode ter no máximo 128 caracteres.",
  PASSWORD_COMPROMISED:
    "Essa senha já apareceu em vazamentos de dados. Escolha outra.",
};
