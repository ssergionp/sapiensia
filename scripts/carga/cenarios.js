// k6 load test for the SapiensIA API (#44). Run it with `just carga` (k6 in Docker); see docs/runbooks/teste-de-carga.md.
// Needs .tmp/carga/contas.json, made by `node scripts/carga/preparar.mjs` (mounted at /carga in the container).
//
// Environment (all optional):
//   BASE_URL                API address (default http://host.docker.internal:8081, the local API seen from Docker)
//   CARGA_PERMITIR_REMOTO   "sim" lets BASE_URL be a non-local host (never without the owner's authorization)
//   RAMP_UP / HOLD / RAMP_DOWN   stage durations (default 1m / 8m / 1m)
//   VUS_CATALOG / VUS_LIBRARY    concurrent users per scenario (default 35 / 15, 50 in total)
//   ORDERS_PER_HOUR         arrival rate of the Order scenario (default 20)
//   DOWNLOADS_PER_ACCOUNT   Downloads per account per run (default 8; the limit is 10 per Product per day)
//   RESULT_FILE             where the summary goes (default /carga/resultado.json)
import exec from "k6/execution";
import http from "k6/http";
import { check, sleep } from "k6";
import { Counter } from "k6/metrics";

const BASE_URL = (__ENV.BASE_URL || "http://host.docker.internal:8081").replace(/\/+$/, "");
const LOCAL_HOST = /^https?:\/\/(127\.0\.0\.1|localhost|host\.docker\.internal)(:\d{1,5})?$/;
if (!LOCAL_HOST.test(BASE_URL) && __ENV.CARGA_PERMITIR_REMOTO !== "sim") {
  throw new Error(
    `BASE_URL ${BASE_URL} não é local. Este teste só roda contra o sistema local; ` +
      "para outro ambiente é preciso a autorização do responsável e CARGA_PERMITIR_REMOTO=sim.",
  );
}

const RAMP_UP = __ENV.RAMP_UP || "1m";
const HOLD = __ENV.HOLD || "8m";
const RAMP_DOWN = __ENV.RAMP_DOWN || "1m";
const VUS_CATALOG = Number(__ENV.VUS_CATALOG || 35);
const VUS_LIBRARY = Number(__ENV.VUS_LIBRARY || 15);
const ORDERS_PER_HOUR = Number(__ENV.ORDERS_PER_HOUR || 20);
const DOWNLOADS_PER_ACCOUNT = Number(__ENV.DOWNLOADS_PER_ACCOUNT || 8);
const RESULT_FILE = __ENV.RESULT_FILE || "/carga/resultado.json";
// A valid fictitious CPF; the local fake Pix gateway only keeps a generated code.
const FICTITIOUS_CPF = "529.982.247-25";

let contas;
try {
  contas = JSON.parse(open("/carga/contas.json"));
} catch (e) {
  throw new Error("Não achei /carga/contas.json. Rode antes: node scripts/carga/preparar.mjs");
}
const accounts = contas.accounts;
const products = contas.products;

// 429 is the rate limit working, not a server error: counted apart and left out of http_req_failed.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }, 429));
const throttled = new Counter("respostas_429");

function toSeconds(text) {
  const m = /^(\d+)(s|m|h)$/.exec(text);
  if (!m) throw new Error(`Duração inválida: ${text} (use 30s, 2m, 1h)`);
  return Number(m[1]) * { s: 1, m: 60, h: 3600 }[m[2]];
}

function ramp(target, name) {
  return {
    executor: "ramping-vus",
    startVUs: 0,
    stages: [
      { duration: RAMP_UP, target },
      { duration: HOLD, target },
      { duration: RAMP_DOWN, target: 0 },
    ],
    gracefulRampDown: "30s",
    exec: name,
  };
}

// tipo = which threshold applies; grupo = which row of the result table.
const GROUPS = [
  ["comum", "catalogo"],
  ["comum", "sessao"],
  ["comum", "biblioteca"],
  ["comum", "pedido_leitura"],
  ["login", "login"],
  ["download", "download"],
  ["pedido", "pedido_criacao"],
];

function thresholds() {
  const t = {
    // NFR-002: common endpoints
    "http_req_duration{tipo:comum}": ["p(95)<500", "p(99)<1500"],
    // approximation of the 99.5% of NFR-001 (that one is monthly; a short test does not measure it)
    http_req_failed: ["rate<0.005"],
    checks: ["rate>0.995"],
  };
  // Report-only thresholds (always true): they make k6 keep a sub-metric per group, for the result table.
  for (const [, grupo] of GROUPS) {
    t[`http_req_duration{grupo:${grupo}}`] = ["max>=0"];
    t[`http_reqs{grupo:${grupo}}`] = ["count>=0"];
  }
  for (const tipo of ["login", "download", "pedido"]) t[`http_req_duration{tipo:${tipo}}`] = ["max>=0"];
  return t;
}

export const options = {
  scenarios: {
    catalogo: ramp(VUS_CATALOG, "catalogo"),
    biblioteca: ramp(VUS_LIBRARY, "biblioteca"),
    pedido: {
      executor: "constant-arrival-rate",
      rate: ORDERS_PER_HOUR,
      timeUnit: "1h",
      duration: `${toSeconds(RAMP_UP) + toSeconds(HOLD) + toSeconds(RAMP_DOWN)}s`,
      preAllocatedVUs: 2,
      maxVUs: 5,
      exec: "pedido",
    },
  },
  // k6 clears the cookie jar at every iteration by default; the Customer session must live as long as the VU
  noCookiesReset: true,
  thresholds: thresholds(),
  summaryTrendStats: ["avg", "med", "p(95)", "p(99)", "max"],
};

// The SPA's way (frontend/src/lib/api.ts): the XSRF-TOKEN cookie echoed as X-XSRF-TOKEN. Spring may renew it at login.
function csrfToken() {
  const read = () => {
    const values = http.cookieJar().cookiesForURL(`${BASE_URL}/`)["XSRF-TOKEN"];
    return values && values.length ? values[0] : undefined;
  };
  if (read() === undefined) http.get(`${BASE_URL}/api/system/health`, { tags: { tipo: "preparo", grupo: "csrf" } });
  return read() || "";
}

function call(method, path, body, tipo, grupo) {
  const headers = { Accept: "application/json" };
  if (method !== "GET") {
    headers["Content-Type"] = "application/json";
    headers["X-XSRF-TOKEN"] = csrfToken();
  }
  const res = http.request(method, `${BASE_URL}${path}`, body === null ? null : JSON.stringify(body), {
    headers,
    tags: { tipo, grupo },
    responseType: "text",
  });
  if (res.status === 429) throttled.add(1, { grupo });
  return res;
}

function login(account) {
  const res = call("POST", "/api/sessions", { email: account.email, password: account.password }, "login", "login");
  check(res, { "login 204": (r) => r.status === 204 || r.status === 429 });
  return res.status === 204;
}

function think() {
  sleep(1 + Math.random() * 2);
}

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

// Visitor: list, detail and cover of a Product.
export function catalogo() {
  const list = call("GET", "/api/products?page=0", null, "comum", "catalogo");
  check(list, { "catalogo 200": (r) => r.status === 200 || r.status === 429 });
  const items = list.status === 200 ? list.json("items") : [];
  const id = items.length ? pick(items).id : pick(products);
  think();
  const detail = call("GET", `/api/products/${id}`, null, "comum", "catalogo");
  check(detail, { "detalhe 200": (r) => r.status === 200 || r.status === 429 });
  if (Math.random() < 0.5) {
    const cover = call("GET", `/api/products/${id}/cover`, null, "comum", "catalogo");
    check(cover, { "capa 200": (r) => r.status === 200 || r.status === 429 });
  }
  think();
}

// Per-VU state (a VU keeps its module variables across iterations).
let libraryIteration = 0;
let downloads = 0;
let loggedIn = false;

// Logged-in Customer: login, current session, Library and a few Downloads (each account owns one Product).
export function biblioteca() {
  const account = accounts[(exec.vu.idInTest - 1) % accounts.length];
  if (!loggedIn || libraryIteration % 40 === 0) loggedIn = login(account);
  libraryIteration += 1;
  const current = call("GET", "/api/sessions/current", null, "comum", "sessao");
  if (current.status === 401) loggedIn = false;
  check(current, { "sessao 200": (r) => r.status === 200 || r.status === 401 || r.status === 429 });
  const library = call("GET", "/api/library", null, "comum", "biblioteca");
  check(library, { "biblioteca 200": (r) => r.status === 200 || r.status === 401 || r.status === 429 });
  if (downloads < DOWNLOADS_PER_ACCOUNT && libraryIteration % 20 === 5) {
    const res = call("POST", `/api/library/${account.productId}/download`, null, "download", "download");
    check(res, { "download 200": (r) => r.status === 200 || r.status === 429 });
    downloads += 1;
  }
  think();
}

// Customer buys: login, create the Order of a Product the account does not own yet, read the Order back.
export function pedido() {
  const account = accounts[(exec.scenario.iterationInTest + VUS_LIBRARY) % accounts.length];
  const others = products.filter((p) => p !== account.productId);
  if (others.length === 0 || !login(account)) return;
  const created = call("POST", "/api/orders", { productId: pick(others), cpf: FICTITIOUS_CPF }, "pedido", "pedido_criacao");
  check(created, { "pedido criado": (r) => r.status === 200 || r.status === 201 || r.status === 429 });
  if (created.status === 200 || created.status === 201) {
    const read = call("GET", `/api/orders/${created.json("id")}`, null, "comum", "pedido_leitura");
    check(read, { "pedido lido": (r) => r.status === 200 || r.status === 429 });
  }
}

const ms = (value) => (value === undefined ? "-" : `${Math.round(value)} ms`);

export function handleSummary(data) {
  const lines = ["", "Resultado (validação do script quando local; a meta vale na homologação)", ""];
  lines.push("grupo               requisições   p95       p99");
  for (const [, grupo] of GROUPS) {
    const m = data.metrics[`http_req_duration{grupo:${grupo}}`];
    const n = data.metrics[`http_reqs{grupo:${grupo}}`];
    if (!m) continue;
    lines.push(
      `${grupo.padEnd(20)}${String(n ? n.values.count : "-").padEnd(14)}${ms(m.values["p(95)"]).padEnd(10)}${ms(m.values["p(99)"])}`,
    );
  }
  const common = data.metrics["http_req_duration{tipo:comum}"];
  if (common) lines.push("", `comuns (NFR-002): p95 ${ms(common.values["p(95)"])}, p99 ${ms(common.values["p(99)"])}`);
  const failed = data.metrics.http_req_failed;
  if (failed) lines.push(`taxa de erro: ${(failed.values.rate * 100).toFixed(3)}%`);
  const limited = data.metrics.respostas_429;
  lines.push(`respostas 429: ${limited ? limited.values.count : 0}`);
  const broken = Object.entries(data.metrics).filter(
    ([, m]) => m.thresholds && Object.values(m.thresholds).some((t) => !t.ok),
  );
  lines.push(broken.length ? `metas NÃO cumpridas: ${broken.map(([name]) => name).join(", ")}` : "metas cumpridas");
  lines.push("");
  return { stdout: lines.join("\n"), [RESULT_FILE]: JSON.stringify(data, null, 2) };
}
