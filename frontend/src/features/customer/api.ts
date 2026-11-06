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

export async function requestPasswordReset(input: {
  email: string;
}): Promise<void> {
  const response = await apiFetch("/api/customers/password-resets", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
  if (response.status === 202) return;
  throw new Error(`Unexpected status ${response.status}`);
}

export class ResetPasswordError extends Error {
  readonly fieldErrors: Record<string, string>;

  constructor(fieldErrors: Record<string, string>) {
    super("Password reset was rejected");
    this.name = "ResetPasswordError";
    this.fieldErrors = fieldErrors;
  }
}

export type ResetPasswordRequest = { token: string; newPassword: string };

export function resetPassword(input: ResetPasswordRequest): Promise<void> {
  return postPasswordSetup(
    "/api/customers/password-resets/confirmation",
    input,
  );
}

// Shared with the administrator first-access page: same body and same 400 format.
export async function postPasswordSetup(
  url: string,
  input: ResetPasswordRequest,
): Promise<void> {
  const response = await apiFetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
  if (response.status === 204) return;
  if (response.status === 400) {
    const body: unknown = await response.json().catch(() => null);
    const errors =
      typeof body === "object" && body !== null && "errors" in body
        ? body.errors
        : null;
    if (typeof errors === "object" && errors !== null) {
      throw new ResetPasswordError(errors as Record<string, string>);
    }
  }
  throw new Error(`Unexpected status ${response.status}`);
}

export class ConfirmEmailError extends Error {
  readonly fieldErrors: Record<string, string>;

  constructor(fieldErrors: Record<string, string>) {
    super("E-mail confirmation was rejected");
    this.name = "ConfirmEmailError";
    this.fieldErrors = fieldErrors;
  }
}

export async function confirmEmail(input: {
  token: string;
  password: string;
}): Promise<void> {
  const response = await apiFetch("/api/customers/email-confirmations", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
  if (response.status === 204) return;
  if (response.status === 400) {
    const body: unknown = await response.json().catch(() => null);
    const errors =
      typeof body === "object" && body !== null && "errors" in body
        ? body.errors
        : null;
    if (typeof errors === "object" && errors !== null) {
      throw new ConfirmEmailError(errors as Record<string, string>);
    }
  }
  throw new Error(`Unexpected status ${response.status}`);
}
