// Minimal Asaas stand-in for the CI end-to-end job (no npm packages).
// Supports only what AsaasPixGateway uses: GET and POST /v3/customers, POST /v3/payments,
// GET /v3/payments/<id>, GET /v3/payments/<id>/pixQrCode and DELETE /v3/payments/<id>. Objects live in memory.
// GET /v3/payments/<id> reports RECEIVED until the e2e test calls POST /v3/__e2e/payments/<id>/refund (a stub-only
// route, not part of Asaas), which makes the payment REFUNDED, as a real refund would.
// The access_token header is checked against the fake CI key. Headers and bodies are never logged.
import { randomBytes } from 'node:crypto';
import { createServer } from 'node:http';

const port = Number(process.argv[2] ?? process.env.PORT ?? 54322);
const apiKey = 'ci-fake-asaas-key-not-real';
const customers = [];
// payment id -> { value, externalReference, status }; value and externalReference come from the request body
const payments = new Map();

function reply(res, status, body) {
  const json = JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(json) });
  res.end(json);
}

function newId(prefix) {
  return `${prefix}_${randomBytes(8).toString('hex')}`;
}

const server = createServer((req, res) => {
  const url = new URL(req.url ?? '/', 'http://localhost');
  const path = url.pathname;
  const method = req.method ?? '';
  if (!path.startsWith('/v3/')) {
    req.resume();
    return reply(res, 404, { errors: [{ code: 'not_found' }] });
  }
  if (req.headers['access_token'] !== apiKey) {
    req.resume();
    return reply(res, 401, { errors: [{ code: 'invalid_access_token' }] });
  }

  if (method === 'GET' && path === '/v3/customers') {
    req.resume();
    const ref = url.searchParams.get('externalReference');
    const data = customers.filter((c) => c.externalReference === ref).map((c) => ({ object: 'customer', id: c.id }));
    return reply(res, 200, { object: 'list', hasMore: false, totalCount: data.length, data });
  }

  const pixMatch = /^\/v3\/payments\/([A-Za-z0-9_]+)\/pixQrCode$/.exec(path);
  if (method === 'GET' && pixMatch) {
    req.resume();
    const id = pixMatch[1];
    if (!payments.has(id)) {
      return reply(res, 404, { errors: [{ code: 'invalid_action' }] });
    }
    return reply(res, 200, { encodedImage: 'AAAA', payload: `PIX-STUB-${id}`, expirationDate: '2099-01-01 00:00:00' });
  }

  const paymentMatch = /^\/v3\/payments\/([A-Za-z0-9_]+)$/.exec(path);
  if (method === 'GET' && paymentMatch) {
    req.resume();
    const id = paymentMatch[1];
    const payment = payments.get(id);
    if (!payment) {
      return reply(res, 404, { errors: [{ code: 'invalid_object' }] });
    }
    return reply(res, 200, {
      object: 'payment',
      id,
      status: payment.status,
      value: payment.value,
      billingType: 'PIX',
      externalReference: payment.externalReference,
      deleted: false,
    });
  }
  const refundMatch = /^\/v3\/__e2e\/payments\/([A-Za-z0-9_]+)\/refund$/.exec(path);
  if (method === 'POST' && refundMatch) {
    req.resume();
    const payment = payments.get(refundMatch[1]);
    if (!payment) {
      return reply(res, 404, { errors: [{ code: 'invalid_object' }] });
    }
    payment.status = 'REFUNDED';
    return reply(res, 200, { object: 'payment', id: refundMatch[1], status: 'REFUNDED' });
  }
  if (method === 'DELETE' && paymentMatch) {
    req.resume();
    if (!payments.delete(paymentMatch[1])) {
      return reply(res, 404, { errors: [{ code: 'invalid_action' }] });
    }
    return reply(res, 200, { deleted: true, id: paymentMatch[1] });
  }

  if (method === 'POST' && (path === '/v3/customers' || path === '/v3/payments')) {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', () => {
      let body;
      try {
        body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      } catch {
        return reply(res, 400, { errors: [{ code: 'invalid_object' }] });
      }
      if (path === '/v3/customers') {
        const id = newId('cus');
        customers.push({ id, externalReference: body.externalReference ?? null });
        return reply(res, 200, { object: 'customer', id });
      }
      const id = newId('pay');
      payments.set(id, { value: body.value ?? null, externalReference: body.externalReference ?? null, status: 'RECEIVED' });
      return reply(res, 200, { object: 'payment', id, status: 'PENDING', billingType: 'PIX' });
    });
    req.on('error', () => reply(res, 400, { errors: [{ code: 'invalid_object' }] }));
    return;
  }

  req.resume();
  return reply(res, 404, { errors: [{ code: 'not_found' }] });
});

server.listen(port, '127.0.0.1', () => console.log(`asaas stub listening on 127.0.0.1:${port}`));
