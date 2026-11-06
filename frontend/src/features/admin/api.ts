import {
  postPasswordSetup,
  type ResetPasswordRequest,
} from "@/features/customer/api";
import { LoginBlockedError, LoginRejectedError } from "@/features/session/api";
import type { LoginInput } from "@/features/session/schemas";
import { apiFetch } from "@/lib/api";
import { type AdminSession, adminSessionSchema } from "./schemas";

export async function adminLogin(input: LoginInput): Promise<void> {
  const response = await apiFetch("/api/admin/sessions", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
  if (response.status === 204) return;
  if (response.status === 401) throw new LoginRejectedError();
  if (response.status === 429) throw new LoginBlockedError();
  throw new Error(`Unexpected status ${response.status}`);
}

// 401 means "no session" (null) and 403 means a Customer is logged in ("forbidden"); neither is an error.
export async function fetchAdminSession(): Promise<
  AdminSession | null | "forbidden"
> {
  const response = await apiFetch("/api/admin/sessions/current");
  if (response.status === 401) return null;
  if (response.status === 403) return "forbidden";
  if (response.status !== 200) {
    throw new Error(`Unexpected status ${response.status}`);
  }
  return adminSessionSchema.parse(await response.json());
}

// The token travels only in the POST body, never in the URL.
export function setAdminPassword(input: ResetPasswordRequest): Promise<void> {
  return postPasswordSetup("/api/admin/password-setup", input);
}
