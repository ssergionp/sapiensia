// Prints the current 6-digit TOTP code (RFC 6238: SHA1, 30 s) of the demo Administrator.
import { createHmac } from "node:crypto";
import { pathToFileURL } from "node:url";

// Same value as DemoAccounts.ADMIN_TOTP_SECRET (backend). Fictitious, demo only.
// nosemgrep: generic.secrets.security.detected-generic-secret.detected-generic-secret -- fictitious demo TOTP secret, local profile only
export const ADMIN_TOTP_SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"; // gitleaks:allow

const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

function base32Decode(text) {
  let bits = "";
  for (const c of text.replace(/=+$/, "").toUpperCase()) {
    const v = ALPHABET.indexOf(c);
    if (v < 0) throw new Error(`invalid Base32 character: ${c}`);
    bits += v.toString(2).padStart(5, "0");
  }
  const bytes = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) bytes.push(Number.parseInt(bits.slice(i, i + 8), 2));
  return Buffer.from(bytes);
}

export function totpCode(secretBase32, step) {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(step));
  const hmac = createHmac("sha1", base32Decode(secretBase32)).update(counter).digest();
  const offset = hmac[hmac.length - 1] & 0x0f;
  const binary = hmac.readUInt32BE(offset) & 0x7fffffff;
  return String(binary % 1_000_000).padStart(6, "0");
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  console.log(totpCode(ADMIN_TOTP_SECRET, Math.floor(Date.now() / 30000)));
}
