// Local demo: refunds a paid Order, as if Asaas had refunded the Pix (local API route, then PAYMENT_REFUNDED webhook).
import { randomBytes } from "node:crypto";
import { pathToFileURL } from "node:url";
import {
  apiBaseUrl,
  findChargeId,
  LOCAL_WEBHOOK_TOKEN,
  parseOrderId,
} from "./demo-pagar.mjs";

export function refundEvent(chargeId) {
  return {
    id: `evt_demo_${randomBytes(8).toString("hex")}`,
    event: "PAYMENT_REFUNDED",
    dateCreated: new Date().toISOString().replace("T", " ").slice(0, 19),
    payment: {
      object: "payment",
      id: chargeId,
      billingType: "PIX",
      status: "REFUNDED",
    },
  };
}

function fail(message) {
  console.error(message);
  process.exit(1);
}

function post(url, body) {
  return fetch(url, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "asaas-access-token": LOCAL_WEBHOOK_TOKEN,
    },
    body,
  });
}

function checkStatus(response, missingRouteMessage) {
  if (response.status === 401 || response.status === 403) {
    fail("A API recusou o token local. Ela está no perfil local?");
  }
  if (response.status === 404 && missingRouteMessage) fail(missingRouteMessage);
  if (!response.ok) fail(`A API respondeu ${response.status}.`);
}

async function main() {
  const orderId = parseOrderId(process.argv[2]);
  if (!orderId) fail("Use: just demo-estornar <id do Pedido>");
  let base;
  try {
    base = apiBaseUrl(process.env);
  } catch (e) {
    fail(e.message);
  }
  const chargeId = findChargeId(orderId);
  if (!chargeId) fail("Pedido não encontrado no banco local.");
  // First the local route that marks the fake charge as refunded, then the webhook Asaas would send.
  const refund = await post(
    `${base}/api/local-demo/charges/${encodeURIComponent(chargeId)}/refund`,
  );
  checkStatus(
    refund,
    "A API não tem o estorno da demonstração. Ela está no perfil local, com app.pix.provider=fake?",
  );
  const webhook = await post(
    `${base}/api/webhooks/asaas`,
    JSON.stringify(refundEvent(chargeId)),
  );
  checkStatus(webhook);
  console.log(
    "Estorno enviado. Em alguns segundos o Pedido aparece como Reembolsado e o Produto sai da Biblioteca.",
  );
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  await main();
}
