import { deflateSync } from "node:zlib";

// Images built in memory for the e2e tests (no fixture files, no dependency).

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(data: Buffer): number {
  let c = 0xffffffff;
  for (const byte of data) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function pngChunk(type: string, data: Buffer): Buffer {
  const typeAndData = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(typeAndData));
  return Buffer.concat([length, typeAndData, crc]);
}

// A valid, opaque, single-colour 8-bit RGB PNG of width x height pixels.
export function buildPng(width: number, height: number): Buffer {
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header[8] = 8; // bit depth
  header[9] = 2; // colour type: RGB
  // compression, filter and interlace stay 0
  const row = Buffer.alloc(1 + width * 3); // filter byte 0 + pixels
  for (let x = 0; x < width; x++) {
    row[1 + x * 3] = 200;
    row[2 + x * 3] = 60;
    row[3 + x * 3] = 60;
  }
  const raw = Buffer.concat(Array.from({ length: height }, () => row));
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk("IHDR", header),
    pngChunk("IDAT", deflateSync(raw)),
    pngChunk("IEND", Buffer.alloc(0)),
  ]);
}

const WEBP_1X1_LOSSLESS = Buffer.from(
  "UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==",
  "base64",
);

function riffChunk(fourCc: string, payload: Buffer): Buffer {
  const header = Buffer.alloc(8);
  header.write(fourCc, 0, "ascii");
  header.writeUInt32LE(payload.length, 4);
  const pad = payload.length % 2 === 1 ? Buffer.alloc(1) : Buffer.alloc(0);
  return Buffer.concat([header, payload, pad]);
}

// A 1x1 lossless WebP in the extended format, with an EXIF chunk that carries the text "Exif".
// The server removes that chunk, so a served copy must not contain it.
export function buildWebpWithExif(): Buffer {
  // The original VP8L chunk: after the 12-byte RIFF header of the base image.
  const vp8l = WEBP_1X1_LOSSLESS.subarray(12);
  const vp8x = Buffer.alloc(10);
  vp8x[0] = 0x08; // EXIF flag
  vp8x.writeUIntLE(0, 4, 3); // canvas width - 1
  vp8x.writeUIntLE(0, 7, 3); // canvas height - 1
  const exif = Buffer.concat([
    Buffer.from("Exif\0\0", "ascii"),
    Buffer.from("e2e camera make and location", "ascii"),
  ]);
  const body = Buffer.concat([
    Buffer.from("WEBP", "ascii"),
    riffChunk("VP8X", vp8x),
    vp8l,
    riffChunk("EXIF", exif),
  ]);
  const header = Buffer.alloc(8);
  header.write("RIFF", 0, "ascii");
  header.writeUInt32LE(body.length, 4);
  return Buffer.concat([header, body]);
}
