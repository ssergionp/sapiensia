// Minimal Supabase Storage stand-in for the CI end-to-end job (no npm packages).
// Supports only what SupabaseProductFileStorage uses: POST and DELETE on /storage/v1/object/<bucket>/<key>.
// Objects live in memory. Headers (the apikey) and bodies are never logged.
import { createServer } from 'node:http';

const port = Number(process.argv[2] ?? process.env.PORT ?? 54321);
const prefix = '/storage/v1/object/';
const objects = new Map();

function reply(res, status, body) {
  const json = JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(json) });
  res.end(json);
}

const server = createServer((req, res) => {
  const path = decodeURIComponent((req.url ?? '').split('?')[0]);
  if (!path.startsWith(prefix) || path.length === prefix.length || !['POST', 'DELETE'].includes(req.method ?? '')) {
    req.resume();
    return reply(res, 404, { message: 'not found' });
  }
  if (!req.headers['apikey']) {
    req.resume();
    return reply(res, 401, { message: 'missing apikey' });
  }
  const objectId = path.slice(prefix.length);

  if (req.method === 'DELETE') {
    req.resume();
    if (!objects.delete(objectId)) {
      return reply(res, 404, { message: 'not found' });
    }
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
    reply(res, 200, { Key: objectId });
  });
  req.on('error', () => reply(res, 400, { message: 'bad request' }));
});

server.listen(port, '127.0.0.1', () => console.log(`storage stub listening on 127.0.0.1:${port}`));
