import {
  postPasswordSetup,
  type ResetPasswordRequest,
} from "@/features/customer/api";
import { LoginBlockedError, LoginRejectedError } from "@/features/session/api";
import type { LoginInput } from "@/features/session/schemas";
import { apiFetch } from "@/lib/api";
import {
  type AdminSession,
  adminSessionSchema,
  type MfaEnrollment,
  type MfaStatus,
  mfaEnrollmentSchema,
  mfaStatusSchema,
} from "./schemas";

// 400: the code is wrong, expired or already used.
export class MfaCodeRejectedError extends Error {
  constructor() {
    super("MFA code was rejected");
    this.name = "MfaCodeRejectedError";
  }
}

// 409: the enrollment was replaced by another session, or the MFA is not active yet.
export class MfaConflictError extends Error {
  constructor() {
    super("MFA state conflict");
    this.name = "MfaConflictError";
  }
}

// 401: the pending step (5 minutes after the password) is gone.
export class MfaExpiredError extends Error {
  constructor() {
    super("MFA step expired");
    this.name = "MfaExpiredError";
  }
}

// 401 means the pending step is gone ("expired"); 403 means this is not a pending Administrator ("forbidden").
export async function fetchMfaStatus(): Promise<
  MfaStatus | "expired" | "forbidden"
> {
  const response = await apiFetch("/api/admin/sessions/mfa");
  if (response.status === 401) return "expired";
  if (response.status === 403) return "forbidden";
  if (response.status !== 200) {
    throw new Error(`Unexpected status ${response.status}`);
  }
  return mfaStatusSchema.parse(await response.json());
}

// Every call replaces the secret on the server, so callers must not repeat it needlessly. 409 means the MFA is already active.
export async function startMfaEnrollment(): Promise<MfaEnrollment | "active"> {
  const response = await apiFetch("/api/admin/sessions/mfa/enrollment", {
    method: "POST",
  });
  if (response.status === 409) return "active";
  if (response.status === 401) throw new MfaExpiredError();
  if (response.status !== 200) {
    throw new Error(`Unexpected status ${response.status}`);
  }
  return mfaEnrollmentSchema.parse(await response.json());
}

async function postMfaCode(path: string, code: string): Promise<void> {
  const response = await apiFetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code }),
  });
  if (response.status === 204) return;
  if (response.status === 400) throw new MfaCodeRejectedError();
  if (response.status === 429) throw new LoginBlockedError();
  if (response.status === 409) throw new MfaConflictError();
  if (response.status === 401) throw new MfaExpiredError();
  throw new Error(`Unexpected status ${response.status}`);
}

export function confirmMfaEnrollment(code: string): Promise<void> {
  return postMfaCode("/api/admin/sessions/mfa/enrollment/confirmation", code);
}

export function verifyMfaCode(code: string): Promise<void> {
  return postMfaCode("/api/admin/sessions/mfa", code);
}

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
