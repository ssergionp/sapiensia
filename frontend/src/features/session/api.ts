import { apiFetch } from "@/lib/api";
import type { LoginInput } from "./schemas";

export const SESSION_URL = "/api/sessions/current";

export type Session = { email: string };

// The backend answers 401 to every login failure, so one error type is enough.
export class LoginRejectedError extends Error {
  constructor() {
    super("Login was rejected");
    this.name = "LoginRejectedError";
  }
}

export async function login(input: LoginInput): Promise<void> {
  const response = await apiFetch("/api/sessions", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
  if (response.status === 204) return;
  if (response.status === 401) throw new LoginRejectedError();
  throw new Error(`Unexpected status ${response.status}`);
}

// 401 means "no session", not an error: it resolves to null.
export async function fetchSession(): Promise<Session | null> {
  const response = await apiFetch(SESSION_URL);
  if (response.status === 401) return null;
  if (response.status !== 200) {
    throw new Error(`Unexpected status ${response.status}`);
  }
  const body: unknown = await response.json();
  if (
    typeof body === "object" &&
    body !== null &&
    "email" in body &&
    typeof body.email === "string"
  ) {
    return { email: body.email };
  }
  throw new Error("Unexpected session payload");
}

export async function logout(): Promise<void> {
  const response = await apiFetch(SESSION_URL, { method: "DELETE" });
  if (response.status === 204) return;
  throw new Error(`Unexpected status ${response.status}`);
}
