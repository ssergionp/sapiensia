// Minimal Supabase Storage stand-in for the CI end-to-end job (no npm packages).
// Supports only what SupabaseProductFileStorage uses:
//   POST and DELETE  /storage/v1/object/<bucket>/<key>
//   GET              /storage/v1/object/authenticated/<bucket>/<key>
//   POST             /storage/v1/object/sign/<bucket>/<key>              (apikey; body {"expiresIn": seconds})
//   GET              /storage/v1/object/sign/<bucket>/<key>?token=...[&download=<name>]   (no apikey; the token is the credential)
// The content-type sent on POST is kept and returned on GET. Objects and signed tokens live in memory.
// Headers (the apikey), bodies, tokens and file names are never logged.
import { randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import { pathToFileURL } from 'node:url';

const prefix = '/storage/v1/object/';
const authenticated = 'authenticated/';
const sign = 'sign/';

function reply(res, status, body) {
  const json = JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(json) });
  res.end(json);
}

// like Supabase: HTTP 400 with the real status in the body
const notFound = (res) => reply(res, 400, { statusCode: '404', error: 'not_found', message: 'Object not found' });

export function startStub({ port = 0, now = () => Date.now() } = {}) {
  const objects = new Map();
  const types = new Map();
  const tokens = new Map(); // token -> { id, expiresAt }

  const server = createServer((req, res) => {
    const url = new URL(req.url ?? '', 'http://stub');
    let path;
    try {
      path = decodeURIComponent(url.pathname);
    } catch {
      req.resume();
      return reply(res, 400, { message: 'bad request' });
    }
    if (!path.startsWith(prefix) || path.length === prefix.length || !['POST', 'DELETE', 'GET'].includes(req.method ?? '')) {
      req.resume();
      return reply(res, 404, { message: 'not found' });
    }
    const id = path.slice(prefix.length);

    // Signed download: the token is the credential, so no apikey here.
    if (req.method === 'GET' && id.startsWith(sign)) {
      req.resume();
      const objectId = id.slice(sign.length);
      const issued = tokens.get(url.searchParams.get('token') ?? '');
      if (!issued || issued.id !== objectId) {
        return reply(res, 400, { statusCode: '400', error: 'InvalidJWT', message: 'invalid signature' });
      }
      if (now() >= issued.expiresAt) {
        return reply(res, 400, { statusCode: '400', error: 'InvalidJWT', message: '"exp" claim timestamp check failed' });
      }
      const body = objects.get(objectId);
      if (!body) {
        return notFound(res);
      }
      const headers = {
        'content-type': types.get(objectId) ?? 'application/octet-stream',
        'content-length': body.length,
      };
      const name = url.searchParams.get('download');
      if (name !== null) {
        if (/["\\/\x00-\x1f\x7f]/.test(name)) {
          return reply(res, 400, { message: 'invalid file name' });
        }
        // Node rejects non-ASCII header text: an ASCII fallback plus the RFC 5987 form for the real name.
        headers['content-disposition'] = /^[\x20-\x7e]*$/.test(name)
          ? `attachment; filename="${name}"`
          : `attachment; filename="${name.replace(/[^\x20-\x7e]/g, '_')}"; filename*=UTF-8''${encodeURIComponent(name)}`;
      }
      res.writeHead(200, headers);
      return res.end(body);
    }

    if (!req.headers['apikey']) {
      req.resume();
      return reply(res, 401, { message: 'missing apikey' });
    }

    if (req.method === 'GET') {
      req.resume();
      if (!id.startsWith(authenticated)) {
        return reply(res, 404, { message: 'not found' });
      }
      const body = objects.get(id.slice(authenticated.length));
      if (!body) {
        return notFound(res);
      }
      res.writeHead(200, {
        'content-type': types.get(id.slice(authenticated.length)) ?? 'application/octet-stream',
        'content-length': body.length,
      });
      return res.end(body);
    }

    if (req.method === 'DELETE') {
      req.resume();
      if (!objects.delete(id)) {
        return reply(res, 404, { message: 'not found' });
      }
      types.delete(id);
      return reply(res, 200, { message: 'deleted' });
    }

    // POST: the body may be chunked (no Content-Length), so collect it from the stream.
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('error', () => reply(res, 400, { message: 'bad request' }));
    req.on('end', () => {
      const data = Buffer.concat(chunks);
      if (id.startsWith(sign)) {
        let expiresIn;
        try {
          expiresIn = JSON.parse(data.toString('utf8')).expiresIn;
        } catch {
          expiresIn = undefined;
        }
        if (!Number.isInteger(expiresIn) || expiresIn <= 0) {
          return reply(res, 400, { message: 'invalid expiresIn' });
        }
        const objectId = id.slice(sign.length);
        if (!objects.has(objectId)) {
          return notFound(res);
        }
        const token = randomBytes(24).toString('base64url');
        tokens.set(token, { id: objectId, expiresAt: now() + expiresIn * 1000 });
        return reply(res, 200, { signedURL: `/object/sign/${objectId}?token=${token}` });
      }
      if (req.headers['x-upsert'] === 'false' && objects.has(id)) {
        return reply(res, 409, { message: 'already exists' });
      }
      objects.set(id, data);
      types.set(id, req.headers['content-type'] ?? 'application/octet-stream');
      reply(res, 200, { Key: id });
    });
  });

  return new Promise((resolve) => server.listen(port, '127.0.0.1', () => resolve(server)));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const port = Number(process.argv[2] ?? process.env.PORT ?? 54321);
  await startStub({ port });
  console.log(`storage stub listening on 127.0.0.1:${port}`);
}
