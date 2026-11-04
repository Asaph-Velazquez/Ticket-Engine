import { webcrypto } from "node:crypto";
import { beforeAll, describe, expect, it } from "vitest";
import { decodeBase64Url, encodeBase64Url, verifyTicket } from "./verify.js";

const subtle = webcrypto.subtle;
const now = 1_800_000_000;
const claims = {
  iss: "ticket-engine",
  aud: "ticket-wallet-access",
  sub: "12345678-1234-4234-8234-123456789012",
  jti: "22345678-1234-4234-8234-123456789012",
  iat: now - 100,
  nbf: now - 100,
  exp: now + 7200,
  schemaVersion: 1,
  status: "ISSUED",
  paymentId: "32345678-1234-4234-8234-123456789012",
  eventId: "42345678-1234-4234-8234-123456789012",
  seatId: "A-12",
  eventName: "Noche de concierto",
  venue: "Auditorio",
  eventStartsAt: now + 3600,
  amountMinor: 10000,
  currency: "MXN",
};
let keys, jwk;
const encode = (text) => encodeBase64Url(new TextEncoder().encode(text));
const canonical = (object) =>
  JSON.stringify(
    Object.fromEntries(
      Object.keys(object).sort().map((key) => [key, object[key]]),
    ),
  );
async function sign(
  payload = claims,
  header = { alg: "ES256", kid: "test-key", typ: "JWT" },
  raw,
  rawHeader,
) {
  const input = `${encode(rawHeader ?? canonical(header))}.${encode(raw ?? canonical(payload))}`;
  const signature = await subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    keys.privateKey,
    new TextEncoder().encode(input),
  );
  return `${input}.${encodeBase64Url(new Uint8Array(signature))}`;
}
beforeAll(async () => {
  keys = await subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  );
  jwk = {
    ...(await subtle.exportKey("jwk", keys.publicKey)),
    kid: "test-key",
    alg: "ES256",
    use: "sig",
  };
});

describe("Verificación ES256 independiente", () => {
  it("verifica firma raw64 con WebCrypto y entrega claims auténticos", async () => {
    const token = await sign();
    expect(decodeBase64Url(token.split(".")[2])).toHaveLength(64);
    const result = await verifyTicket(token, jwk, { subtle, now });
    expect(result.valid).toBe(true);
    expect(result.payload.jti).toBe(claims.jti);
  });
  it("rechaza payload o firma alterados y otra llave", async () => {
    const token = await sign();
    const parts = token.split(".");
    await expect(
      verifyTicket(
        `${parts[0]}.${encode(canonical({ ...claims, seatId: "B-1" }))}.${parts[2]}`,
        jwk,
        { subtle, now },
      ),
    ).rejects.toThrow("firma");
    const signature = decodeBase64Url(parts[2]);
    signature[0] ^= 1;
    await expect(
      verifyTicket(
        `${parts[0]}.${parts[1]}.${encodeBase64Url(signature)}`,
        jwk,
        { subtle, now },
      ),
    ).rejects.toThrow();
    const another = await subtle.generateKey(
      { name: "ECDSA", namedCurve: "P-256" },
      true,
      ["sign", "verify"],
    );
    const otherJwk = {
      ...jwk,
      ...(await subtle.exportKey("jwk", another.publicKey)),
    };
    await expect(
      verifyTicket(token, otherJwk, { subtle, now }),
    ).rejects.toThrow();
  });
  it("rechaza claims vencidos y contextos distintos aun con firma válida", async () => {
    for (const changed of [
      { exp: now },
      { nbf: now + 60 },
      { iat: now + 60 },
      { iss: "otro" },
      { aud: "otra" },
      { schemaVersion: 2 },
      { status: "CANCELLED" },
      { sub: "1-1-1-1-1" },
      { amountMinor: -1 },
      { currency: "ZZZ" },
      { extra: "dato" },
    ]) {
      await expect(
        verifyTicket(await sign({ ...claims, ...changed }), jwk, {
          subtle,
          now,
        }),
      ).rejects.toThrow();
    }
  });
  it("rechaza alg none, kid no fijado, encabezados duplicados y payload ambiguo", async () => {
    for (const header of [
      { alg: "none", kid: "test-key", typ: "JWT" },
      { alg: "ES256", kid: "otro", typ: "JWT" },
      {
        alg: "ES256",
        kid: "test-key",
        typ: "JWT",
        jku: "https://attacker.invalid",
      },
    ]) {
      await expect(
        verifyTicket(await sign(claims, header), jwk, { subtle, now }),
      ).rejects.toThrow();
    }
    await expect(
      verifyTicket(
        await sign(
          claims,
          undefined,
          canonical(claims).replace("{", '{"iss":"false",'),
        ),
        jwk,
        { subtle, now },
      ),
    ).rejects.toThrow();
    await expect(
      verifyTicket(await sign(claims, undefined, JSON.stringify(claims)), jwk, {
        subtle,
        now,
      }),
    ).rejects.toThrow();
    await expect(
      verifyTicket(await sign(claims, undefined, undefined,
        '{"alg":"ES256","alg":"ES256","kid":"test-key","typ":"JWT"}'), jwk, { subtle, now }),
    ).rejects.toThrow();
  });
  it("requiere WebCrypto y codificación compacta válida", async () => {
    await expect(verifyTicket(await sign(), jwk, { subtle: null, now })).rejects.toThrow("HTTPS");
    await expect(
      verifyTicket("a.b.c.d", jwk, { subtle, now }),
    ).rejects.toThrow();
    await expect(
      verifyTicket("=".repeat(24001), jwk, { subtle, now }),
    ).rejects.toThrow();
    expect(() => decodeBase64Url("eA==")).toThrow();
    expect(() => decodeBase64Url("eB")).toThrow();
  });
});
