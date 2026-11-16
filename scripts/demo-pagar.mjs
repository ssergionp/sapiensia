// Local demo: marks an Order as paid, as if Asaas had confirmed the Pix (sends PAYMENT_RECEIVED to the local API).
import { execFileSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import { pathToFileURL } from "node:url";

// Same value as the default token of application-local.yml. Fictitious, local profile only.
// nosemgrep: generic.secrets.security.detected-generic-secret.detected-generic-secret -- fictitious local webhook token
export const LOCAL_WEBHOOK_TOKEN = "local-only-asaas-webhook-token-000000000000"; // gitleaks:allow

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const LOCAL_URL = /^http:\/\/(127\.0\.0\.1|localhost):\d{1,5}$/;

/** The Order id in lower case, or null when the text is not a UUID. */
export function parseOrderId(text) {
  return typeof text === "string" && UUID.test(text) ? text.toLowerCase() : null;
}

/** The local API address; the token never goes anywhere else. */
export function apiBaseUrl(env) {
  const url = env.DEMO_API_URL ?? "http://127.0.0.1:8081";
  if (!LOCAL_URL.test(url)) {
    throw new Error("DEMO_API_URL deve ser http://127.0.0.1:<porta> ou http://localhost:<porta>.");
  }
  return url;
}

export function paymentEvent(chargeId) {
  return {
    id: `evt_demo_${randomBytes(8).toString("hex")}`,
    event: "PAYMENT_RECEIVED",
    dateCreated: new Date().toISOString().replace("T", " ").slice(0, 19),
    payment: { object: "payment", id: chargeId, billingType: "PIX", status: "RECEIVED" },
  };
}

function fail(message) {
  console.error(message);
  process.exit(1);
}

// The charge id of the Order, from the local database container published on port 5433 (demo database first).
export function findChargeId(orderId) {
  const container = execFileSync("docker", ["ps", "--filter", "publish=5433", "--format", "{{.ID}}"], {
    encoding: "utf8",
  })
    .split("\n")[0]
    .trim();
  if (!container) fail("O banco local não está ligado (porta 5433).");
  const sql = `select provider_payment_id from orders where id = '${orderId}'`; // orderId is a validated UUID
  for (const db of ["app_demo", "app"]) {
    try {
      const out = execFileSync("docker", ["exec", container, "psql", "-U", "app", "-d", db, "-tAc", sql], {
        encoding: "utf8",
        stdio: ["ignore", "pipe", "ignore"],
      }).trim();
      if (out) return out;
    } catch {
      // database or table missing: try the next one
    }
  }
  return null;
}

async function main() {
  const orderId = parseOrderId(process.argv[2]);
  if (!orderId) fail("Use: just demo-pagar <id do Pedido>");
  let base;
  try {
    base = apiBaseUrl(process.env);
  } catch (e) {
    fail(e.message);
  }
  const chargeId = findChargeId(orderId);
  if (!chargeId) fail("Pedido não encontrado no banco local.");
  const response = await fetch(`${base}/api/webhooks/asaas`, {
    method: "POST",
    headers: { "content-type": "application/json", "asaas-access-token": LOCAL_WEBHOOK_TOKEN },
    body: JSON.stringify(paymentEvent(chargeId)),
  });
  if (response.status === 401) fail("A API recusou o token local. Ela está no perfil local?");
  if (response.status !== 200) fail(`A API respondeu ${response.status}.`);
  console.log("Evento enviado. Em alguns segundos o Pedido aparece como pago na página dele.");
  console.log("O e-mail de confirmação é gravado como arquivo em .tmp/demo/outbox (fora da demonstração, em .tmp/outbox).");
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main();
}
