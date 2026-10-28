import { apiFetch } from "@/lib/api";
import type { RegisterCustomerInput } from "./schemas";

export class RegisterCustomerError extends Error {
  readonly fieldErrors: Record<string, string>;

  constructor(fieldErrors: Record<string, string>) {
    super("Customer registration was rejected");
    this.name = "RegisterCustomerError";
    this.fieldErrors = fieldErrors;
  }
}

export async function registerCustomer(
  input: RegisterCustomerInput,
): Promise<void> {
  const response = await apiFetch("/api/customers", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
  if (response.status === 202) return;
  if (response.status === 400) {
    const body: unknown = await response.json().catch(() => null);
    const errors =
      typeof body === "object" && body !== null && "errors" in body
        ? body.errors
        : null;
    if (typeof errors === "object" && errors !== null) {
      throw new RegisterCustomerError(errors as Record<string, string>);
    }
  }
  throw new Error(`Unexpected status ${response.status}`);
}
