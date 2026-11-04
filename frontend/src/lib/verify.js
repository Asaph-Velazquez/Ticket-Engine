const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const ZERO_UUID = "00000000-0000-0000-0000-000000000000";
const expectedClaims = [
  "iss",
  "aud",
  "sub",
  "jti",
  "iat",
  "nbf",
  "exp",
  "schemaVersion",
  "status",
  "paymentId",
  "eventId",
  "seatId",
  "eventName",
  "venue",
  "eventStartsAt",
  "amountMinor",
  "currency",
].sort();
const validText = (value) =>
  typeof value === "string" &&
  value.trim().length > 0 &&
  value.length <= 140 &&
  !/[\u0000-\u001f\u007f-\u009f]/u.test(value);

export function decodeBase64Url(value) {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]+$/.test(value))
    throw new Error("Base64url inválido.");
  const raw = atob(value.replace(/-/g, "+").replace(/_/g, "/"));
  const bytes = Uint8Array.from(raw, (char) => char.charCodeAt(0));
  if (encodeBase64Url(bytes) !== value)
    throw new Error("Base64url no canónico.");
  return bytes;
}

export function encodeBase64Url(value) {
  return btoa(Array.from(value, (byte) => String.fromCharCode(byte)).join(""))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

function parsePart(value) {
  return JSON.parse(
    new TextDecoder("utf-8", { fatal: true }).decode(decodeBase64Url(value)),
  );
}

export function inspectToken(token) {
  if (typeof token !== "string" || token.length > 24000)
    throw new Error("Token demasiado grande.");
  const parts = token.split(".");
  if (parts.length !== 3) throw new Error("El JWS debe tener tres segmentos.");
  return {
    header: parsePart(parts[0]),
    payload: parsePart(parts[1]),
    signature: parts[2],
  };
}

function validateClaims(payload, now, issuer, audience) {
  if (
    !payload ||
    Array.isArray(payload) ||
    JSON.stringify(Object.keys(payload).sort()) !==
      JSON.stringify(expectedClaims) ||
    payload.iss !== issuer ||
    payload.aud !== audience ||
    payload.schemaVersion !== 1 ||
    payload.status !== "ISSUED"
  ) {
    throw new Error(
      "El perfil, emisor, audiencia o estado del boleto no corresponde.",
    );
  }
  for (const key of ["sub", "jti", "paymentId", "eventId"]) {
    if (
      typeof payload[key] !== "string" ||
      !UUID.test(payload[key]) ||
      payload[key] === ZERO_UUID
    ) {
      throw new Error("El boleto contiene un identificador inválido.");
    }
  }
  for (const key of ["iat", "nbf", "exp", "eventStartsAt", "amountMinor"]) {
    if (!Number.isSafeInteger(payload[key]) || payload[key] < 0)
      throw new Error("El boleto contiene valores numéricos inválidos.");
  }
  if (
    payload.iat > now ||
    payload.nbf !== payload.iat ||
    payload.nbf > now ||
    payload.exp <= now ||
    payload.exp <= payload.iat ||
    payload.exp <= payload.eventStartsAt ||
    payload.exp - payload.iat > 5 * 366 * 86400
  ) {
    throw new Error(
      "El boleto está vencido, aún no es válido o tiene fechas inconsistentes.",
    );
  }
  if (
    typeof payload.seatId !== "string" ||
    !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/.test(payload.seatId) ||
    !validText(payload.eventName) ||
    !validText(payload.venue) ||
    payload.amountMinor > 9_999_999_999_999 ||
    typeof payload.currency !== "string" ||
    !/^[A-Z]{3}$/.test(payload.currency)
  )
    throw new Error("Los datos del boleto son inválidos.");
  if (
    Intl.supportedValuesOf &&
    ![...Intl.supportedValuesOf("currency"), "XXX", "XTS"].includes(
      payload.currency,
    )
  ) {
    throw new Error("La moneda del boleto no es una moneda ISO reconocida.");
  }
}

export async function verifyTicket(token, jwk, options = {}) {
  const subtle = Object.hasOwn(options, "subtle") ? options.subtle : globalThis.crypto?.subtle;
  if (!subtle)
    throw new Error(
      "La verificación requiere HTTPS o localhost con WebCrypto disponible.",
    );
  const { header, payload } = inspectToken(token);
  if (
    !header ||
    Array.isArray(header) ||
    Object.keys(header).sort().join(",") !== "alg,kid,typ" ||
    header.alg !== "ES256" ||
    header.typ !== "JWT" ||
    header.kid !== jwk?.kid ||
    jwk.kty !== "EC" ||
    jwk.crv !== "P-256" ||
    jwk.alg !== "ES256" ||
    jwk.d !== undefined
  ) {
    throw new Error(
      "El algoritmo o la llave pública no corresponde al emisor.",
    );
  }
  const parts = token.split(".");
  const canonicalHeader = JSON.stringify({
    alg: header.alg,
    kid: header.kid,
    typ: header.typ,
  });
  if (
    canonicalHeader !==
    new TextDecoder("utf-8", { fatal: true }).decode(decodeBase64Url(parts[0]))
  ) {
    throw new Error(
      "El encabezado protegido no es canónico o contiene campos duplicados.",
    );
  }
  const signature = decodeBase64Url(parts[2]);
  if (signature.length !== 64)
    throw new Error("La firma ES256 debe tener 64 bytes.");
  const publicKey = await subtle.importKey(
    "jwk",
    jwk,
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["verify"],
  );
  const valid = await subtle.verify(
    { name: "ECDSA", hash: "SHA-256" },
    publicKey,
    signature,
    new TextEncoder().encode(`${parts[0]}.${parts[1]}`),
  );
  if (!valid)
    throw new Error(
      "La firma no coincide: el boleto fue alterado o usa otra llave.",
    );
  // En el perfil v1, los claims son escalares; JSON.stringify ordenado detecta duplicados/JSON no canónico.
  const canonical = JSON.stringify(
    Object.fromEntries(
      Object.keys(payload)
        .sort()
        .map((key) => [key, payload[key]]),
    ),
  );
  if (
    canonical !==
    new TextDecoder("utf-8", { fatal: true }).decode(decodeBase64Url(parts[1]))
  ) {
    throw new Error("El payload no usa la serialización canónica del módulo.");
  }
  validateClaims(
    payload,
    options.now ?? Math.floor(Date.now() / 1000),
    options.issuer ?? "ticket-engine",
    options.audience ?? "ticket-wallet-access",
  );
  return { valid: true, header, payload };
}
