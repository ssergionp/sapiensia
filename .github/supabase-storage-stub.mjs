// Minimal Supabase Storage stand-in for the CI end-to-end job (no npm packages).
// Supports only what SupabaseProductFileStorage uses: POST and DELETE on /storage/v1/object/<bucket>/<key> and GET on
// /storage/v1/object/authenticated/<bucket>/<key>. The content-type sent on POST is kept and returned on GET.
// Objects live in memory. Headers (the apikey) and bodies are never logged.
import { createServer } from 'node:http';

const port = Number(process.argv[2] ?? process.env.PORT ?? 54321);
const prefix = '/storage/v1/object/';
const objects = new Map();
const types = new Map();
const authenticated = 'authenticated/';

function reply(res, status, body) {
  const json = JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(json) });
  res.end(json);
}

const server = createServer((req, res) => {
  const path = decodeURIComponent((req.url ?? '').split('?')[0]);
  if (!path.startsWith(prefix) || path.length === prefix.length || !['POST', 'DELETE', 'GET'].includes(req.method ?? '')) {
    req.resume();
    return reply(res, 404, { message: 'not found' });
  }
  if (!req.headers['apikey']) {
    req.resume();
    return reply(res, 401, { message: 'missing apikey' });
  }

  if (req.method === 'GET') {
    req.resume();
    const id = path.slice(prefix.length);
    if (!id.startsWith(authenticated)) {
      return reply(res, 404, { message: 'not found' });
    }
    const body = objects.get(id.slice(authenticated.length));
    if (!body) {
      // like Supabase: HTTP 400 with the real status in the body
      return reply(res, 400, { statusCode: '404', error: 'not_found', message: 'Object not found' });
    }
    res.writeHead(200, {
      'content-type': types.get(id.slice(authenticated.length)) ?? 'application/octet-stream',
      'content-length': body.length,
    });
    return res.end(body);
  }

  const objectId = path.slice(prefix.length);

  if (req.method === 'DELETE') {
    req.resume();
    if (!objects.delete(objectId)) {
      return reply(res, 404, { message: 'not found' });
    }
    types.delete(objectId);
    return reply(res, 200, { message: 'deleted' });
  }

  // POST: the body may be chunked (no Content-Length), so collect it from the stream.
  const chunks = [];
  req.on('data', (chunk) => chunks.push(chunk));
  req.on('end', () => {
    if (req.headers['x-upsert'] === 'false' && objects.has(objectId)) {
      return reply(res, 409, { message: 'already exists' });
    }
    objects.set(objectId, Buffer.concat(chunks));
    types.set(objectId, req.headers['content-type'] ?? 'application/octet-stream');
    reply(res, 200, { Key: objectId });
  });
  req.on('error', () => reply(res, 400, { message: 'bad request' }));
});

server.listen(port, '127.0.0.1', () => console.log(`storage stub listening on 127.0.0.1:${port}`));
