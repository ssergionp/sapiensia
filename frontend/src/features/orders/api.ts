import { apiFetch } from "@/lib/api";
import { type Order, orderSchema, orderStatusSchema } from "./schemas";

export class NotLoggedInError extends Error {}
export class CpfRequiredError extends Error {}
export class CpfInvalidError extends Error {}
export class OrderInProgressError extends Error {}
export class ProductUnavailableError extends Error {}
export class PaymentUnavailableError extends Error {}
export class OrderNotFoundError extends Error {}
export class AlreadyOwnedError extends Error {}

async function errorsOf(response: Response): Promise<Record<string, unknown>> {
  const body: unknown = await response.json().catch(() => null);
  if (typeof body === "object" && body !== null && "errors" in body) {
    const errors = body.errors;
    if (typeof errors === "object" && errors !== null) {
      return errors as Record<string, unknown>;
    }
  }
  return {};
}

export async function createOrder(input: {
  productId: string;
  cpf?: string;
}): Promise<{ id: string }> {
  const response = await apiFetch("/api/orders", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
  if (response.status === 200 || response.status === 201) {
    const body: unknown = await response.json();
    if (
      typeof body === "object" &&
      body !== null &&
      "id" in body &&
      typeof body.id === "string"
    ) {
      return { id: body.id };
    }
    throw new Error("Unexpected order payload");
  }
  if (response.status === 401) throw new NotLoggedInError();
  if (response.status === 404) throw new ProductUnavailableError();
  if (response.status === 502) throw new PaymentUnavailableError();
  if (response.status === 409) {
    const code = (await errorsOf(response)).order;
    if (code === "ORDER_IN_PROGRESS") throw new OrderInProgressError();
    if (code === "ALREADY_OWNED") throw new AlreadyOwnedError();
  }
  if (response.status === 400) {
    const errors = await errorsOf(response);
    if (errors.cpf === "CPF_REQUIRED") throw new CpfRequiredError();
    if (errors.cpf === "CPF_INVALID") throw new CpfInvalidError();
  }
  throw new Error(`Unexpected status ${response.status}`);
}

export async function fetchOrder(id: string): Promise<Order> {
  const response = await apiFetch(`/api/orders/${encodeURIComponent(id)}`);
  if (response.status === 200) return orderSchema.parse(await response.json());
  if (response.status === 404 || response.status === 400) {
    throw new OrderNotFoundError();
  }
  if (response.status === 401) throw new NotLoggedInError();
  if (response.status === 502) throw new PaymentUnavailableError();
  throw new Error(`Unexpected status ${response.status}`);
}

// Light read of the status only (database, never the provider); polled while the page waits for the payment.
export async function fetchOrderStatus(id: string): Promise<Order["status"]> {
  const response = await apiFetch(
    `/api/orders/${encodeURIComponent(id)}/status`,
  );
  if (response.status === 200) {
    return orderStatusSchema.parse(await response.json()).status;
  }
  if (response.status === 404 || response.status === 400) {
    throw new OrderNotFoundError();
  }
  if (response.status === 401) throw new NotLoggedInError();
  throw new Error(`Unexpected status ${response.status}`);
}
