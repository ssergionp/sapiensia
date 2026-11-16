// Prepares the load test on the LOCAL system only: creates confirmed Customer accounts, buys and pays one Product for
// each (through the local fake Pix and the local webhook) and writes .tmp/carga/contas.json for scripts/carga/cenarios.js.
import { randomBytes } from "node:crypto";
import { mkdir, readdir, readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { apiBaseUrl, findChargeId, LOCAL_WEBHOOK_TOKEN, paymentEvent } from "../demo-pagar.mjs";

const DEFAULT_COUNT = 30;
const CONFIRMATION_SUBJECT = "Confirme seu e-mail na SapiensIA";
// A valid (check digits) CPF that belongs to nobody known; the fake Pix gateway only keeps a generated code.
const FICTITIOUS_CPF = "529.982.247-25";
const OUTBOX_TIMEOUT_MS = 30_000;
const POLL_MS = 300;

/** The local API address (CARGA_API_URL, default http://127.0.0.1:8081); anything else is refused. */
export function localApiUrl(env) {
  try {
    return apiBaseUrl({ DEMO_API_URL: env.CARGA_API_URL });
  } catch {
    throw new Error("CARGA_API_URL deve ser http://127.0.0.1:<porta> ou http://localhost:<porta>. A preparação roda só no sistema local.");
  }
}

/** The token of the confirmation link in the e-mail text, or null. */
export function confirmationToken(body) {
  const match = /\/confirmar-email#([A-Za-z0-9_-]+)/.exec(body);
  return match ? match[1] : null;
}

/** The Product ids of one catalog page. */
export function parseProductIds(page) {
  return Array.isArray(page?.items) ? page.items.map((item) => item.id) : [];
}

/** The accounts to create: unique e-mails, a strong password each, and the Product each one will buy (round robin). */
export function buildAccounts({ count, products, runId }) {
  if (products.length === 0) throw new Error("Nenhum Produto publicado para comprar.");
  return Array.from({ length: count }, (_, i) => ({
    email: `carga-${runId}-${i + 1}@example.com`,
    password: randomBytes(12).toString("hex"),
    productId: products[i % products.length],
  }));
}

/** The shape k6 reads from .tmp/carga/contas.json. */
export function buildContas({ accounts, products }) {
  return { createdAt: new Date().toISOString(), products, accounts };
}

// A tiny cookie jar: the session and the CSRF token cookies of one Customer.
class Client {
  constructor(base) {
    this.base = base;
    this.cookies = new Map();
  }

  async request(method, path, body) {
    const headers = { accept: "application/json" };
    if (this.cookies.size) headers.cookie = [...this.cookies].map(([k, v]) => `${k}=${v}`).join("; ");
    if (method !== "GET") {
      if (!this.cookies.has("XSRF-TOKEN")) await this.request("GET", "/api/system/health");
      headers["x-xsrf-token"] = this.cookies.get("XSRF-TOKEN") ?? "";
      headers.cookie = [...this.cookies].map(([k, v]) => `${k}=${v}`).join("; ");
    }
    if (body !== undefined) headers["content-type"] = "application/json";
    const response = await fetch(`${this.base}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    for (const line of response.headers.getSetCookie()) {
      const [pair] = line.split(";");
      const at = pair.indexOf("=");
      this.cookies.set(pair.slice(0, at), pair.slice(at + 1));
    }
    return response;
  }
}

async function waitFor(what, check) {
  const deadline = Date.now() + OUTBOX_TIMEOUT_MS;
  while (Date.now() < deadline) {
    const value = await check();
    if (value) return value;
    await new Promise((r) => setTimeout(r, POLL_MS));
  }
  throw new Error(`Tempo esgotado esperando: ${what}`);
}

async function confirmationFor(outboxDir, email) {
  const names = (await readdir(outboxDir).catch(() => [])).filter((n) => n.endsWith(`-${email}.txt`)).sort();
  for (const name of names) {
    const body = await readFile(resolve(outboxDir, name), "utf8");
    if (body.split(/\r?\n/)[1] === `Subject: ${CONFIRMATION_SUBJECT}` && confirmationToken(body)) {
      return confirmationToken(body);
    }
  }
  return null;
}

function expectStatus(response, expected, step) {
  if (!expected.includes(response.status)) {
    throw new Error(`${step}: a API respondeu ${response.status} (esperado ${expected.join(" ou ")}).`);
  }
}

async function prepareAccount(base, outboxDir, account) {
  const client = new Client(base);
  expectStatus(await client.request("POST", "/api/customers", { email: account.email, password: account.password }), [202], "cadastro");
  const token = await waitFor(`e-mail de confirmação de ${account.email} em ${outboxDir}`, () => confirmationFor(outboxDir, account.email));
  expectStatus(await client.request("POST", "/api/customers/email-confirmations", { token, password: account.password }), [204], "confirmação");
  expectStatus(await client.request("POST", "/api/sessions", { email: account.email, password: account.password }), [204], "login");
  const order = await client.request("POST", "/api/orders", { productId: account.productId, cpf: FICTITIOUS_CPF });
  expectStatus(order, [200, 201], "pedido");
  const { id: orderId } = await order.json();
  const chargeId = findChargeId(orderId);
  if (!chargeId) throw new Error("Pedido não encontrado no banco local.");
  const paid = await fetch(`${base}/api/webhooks/asaas`, {
    method: "POST",
    headers: { "content-type": "application/json", "asaas-access-token": LOCAL_WEBHOOK_TOKEN },
    body: JSON.stringify(paymentEvent(chargeId)),
  });
  expectStatus(paid, [200], "webhook de pagamento");
  await waitFor(`Acesso de ${account.email} na Biblioteca`, async () => {
    const library = await client.request("GET", "/api/library");
    const { items = [] } = library.ok ? await library.json() : {};
    return items.some((item) => item.productId === account.productId);
  });
}

async function main() {
  const count = Number(process.argv[2] ?? process.env.CARGA_CONTAS ?? DEFAULT_COUNT);
  if (!Number.isInteger(count) || count < 1) throw new Error("Use: node scripts/carga/preparar.mjs [quantidade de contas]");
  const base = localApiUrl(process.env);
  const outboxDir = resolve(process.env.CARGA_OUTBOX_DIR ?? ".tmp/outbox");
  const catalog = await fetch(`${base}/api/products?page=0`);
  if (!catalog.ok) throw new Error(`A API local não respondeu (${catalog.status}). Ela está ligada (just dev-back, com a demonstração)?`);
  const products = parseProductIds(await catalog.json());
  const accounts = buildAccounts({ count, products, runId: Date.now().toString(36) });
  console.log(`Preparando ${count} contas em ${base} (caixa de saída: ${outboxDir})...`);
  const pending = [...accounts];
  await Promise.all(
    Array.from({ length: 4 }, async () => {
      for (let account = pending.shift(); account; account = pending.shift()) {
        await prepareAccount(base, outboxDir, account);
        process.stdout.write(".");
      }
    }),
  );
  const dir = resolve(".tmp/carga");
  await mkdir(dir, { recursive: true });
  await writeFile(resolve(dir, "contas.json"), `${JSON.stringify(buildContas({ accounts, products }), null, 2)}\n`);
  console.log(`\nPronto: ${count} contas em .tmp/carga/contas.json (fora do git).`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((e) => {
    console.error(e.message);
    process.exit(1);
  });
}
