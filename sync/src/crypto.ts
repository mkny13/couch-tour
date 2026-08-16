// Tokens and pairing codes are opaque random strings, hashed at rest — not JWTs. There's no
// third party to verify a JWT against here, and JWT revocation needs a denylist anyway, so a
// random string checked against a DB row is both simpler and instantly revocable.

const BASE32_NO_AMBIGUOUS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O, 1/I/L

export async function sha256Hex(input: string): Promise<string> {
  const bytes = new TextEncoder().encode(input);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** A 256-bit device token, `ct_<base64url>`. */
export function randomToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  const base64url = btoa(String.fromCharCode(...bytes))
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replaceAll("=", "");
  return `ct_${base64url}`;
}

/** An 8-character pairing code, no ambiguous characters — short enough to type by hand. */
export function randomPairingCode(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(8));
  return [...bytes].map((b) => BASE32_NO_AMBIGUOUS[b % BASE32_NO_AMBIGUOUS.length]).join("");
}

export function randomId(): string {
  return crypto.randomUUID();
}
