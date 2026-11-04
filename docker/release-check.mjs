import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { createHash, webcrypto } from 'node:crypto';

const snapshotPath = '/results/release-snapshot.json';
const resultPath = '/results/release-result.json';
const metadataFields = [
  'ticketId', 'paymentId', 'eventId', 'buyerId', 'seatId', 'eventName', 'venue',
  'eventStartsAt', 'eventEndsAt', 'issuedAt', 'expiresAt', 'status', 'payloadHash', 'keyId'
];
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const sha256 = value => createHash('sha256').update(value, 'utf8').digest('hex');

class CheckError extends Error {
  constructor(code) { super(code); this.code = code; }
}
function requireCheck(condition, code) {
  if (!condition) throw new CheckError(code);
}

function baseUrl() {
  let target;
  try { target = new URL(process.env.API_BASE || 'http://ticket-engine-api:8080'); }
  catch { throw new CheckError('INVALID_SANDBOX_URL'); }
  requireCheck(target.protocol === 'http:' && target.hostname === 'ticket-engine-api' && target.port === '8080'
    && !target.username && !target.password && target.pathname === '/' && !target.search && !target.hash,
  'INVALID_SANDBOX_URL');
  return target;
}

function metadata(ticket) {
  const selected = {};
  for (const field of metadataFields) {
    requireCheck(typeof ticket?.[field] === 'string' && ticket[field].length > 0, 'INVALID_TICKET_METADATA');
    selected[field] = ticket[field];
  }
  requireCheck(UUID.test(selected.ticketId) && selected.status === 'ISSUED', 'INVALID_TICKET_ID_OR_STATE');
  requireCheck(typeof ticket.token === 'string' && ticket.token.length <= 24_000, 'INVALID_TICKET_TOKEN');
  requireCheck(typeof ticket.payloadJson === 'string' && sha256(ticket.payloadJson) === ticket.payloadHash,
    'INVALID_PAYLOAD_HASH');
  return selected;
}

function tokenHash(ticket) {
  requireCheck(typeof ticket?.token === 'string' && ticket.token.length > 0, 'MISSING_TICKET_TOKEN');
  return sha256(ticket.token);
}

function sameMetadata(ticket, expected) {
  const actual = metadata(ticket);
  return metadataFields.every(field => actual[field] === expected?.[field]);
}

function replayRequest(ticket) {
  let claims;
  try { claims = JSON.parse(ticket.payloadJson); }
  catch { throw new CheckError('INVALID_PAYLOAD_JSON'); }
  requireCheck(Number.isSafeInteger(claims.amountMinor) && claims.amountMinor >= 0
    && typeof claims.currency === 'string' && /^[A-Z]{3}$/.test(claims.currency), 'INVALID_PAYMENT_CLAIMS');
  return {
    paymentId: ticket.paymentId, eventId: ticket.eventId, buyerId: ticket.buyerId,
    seatId: ticket.seatId, eventName: ticket.eventName, venue: ticket.venue,
    eventStartsAt: ticket.eventStartsAt, eventEndsAt: ticket.eventEndsAt,
    amountMinor: claims.amountMinor, currency: claims.currency, paymentStatus: 'PAID'
  };
}

async function client() {
  const base = baseUrl();
  let adminToken;
  try { adminToken = (await readFile('/run/secrets/admin-token', 'utf8')).trim(); }
  catch { throw new CheckError('ADMIN_SECRET_UNAVAILABLE'); }
  requireCheck(adminToken.length > 0, 'ADMIN_SECRET_EMPTY');
  return async (path, { method = 'GET', body, auth = true, status = 200 } = {}) => {
    let response;
    for (let attempt = 0; attempt < 3; attempt++) {
      try {
        response = await fetch(new URL(path, base), {
          method,
          headers: {
            Accept: 'application/json',
            ...(auth ? { Authorization: `Bearer ${adminToken}` } : {}),
            ...(body !== undefined ? { 'Content-Type': 'application/json' } : {})
          },
          ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
          signal: AbortSignal.timeout(10_000),
          redirect: 'error'
        });
        if (![502, 503, 504].includes(response.status) || attempt === 2) break;
        await response.body?.cancel();
      } catch {
        if (attempt === 2) throw new CheckError('SANDBOX_HTTP_UNAVAILABLE');
      }
      await new Promise(resolve => setTimeout(resolve, (attempt + 1) * 300));
    }
    requireCheck(response?.status === status, `UNEXPECTED_HTTP_${response?.status || 'NONE'}`);
    try { return await response.json(); }
    catch { throw new CheckError('INVALID_HTTP_JSON'); }
  };
}

async function count(api) {
  const page = await api('/api/tickets?limit=1&offset=0');
  requireCheck(Number.isSafeInteger(page.total) && page.total >= 0, 'INVALID_TICKET_TOTAL');
  return page.total;
}

function decodePart(value) {
  requireCheck(typeof value === 'string' && /^[A-Za-z0-9_-]+$/.test(value), 'INVALID_JWS_BASE64URL');
  const bytes = Buffer.from(value, 'base64url');
  requireCheck(bytes.toString('base64url') === value, 'NONCANONICAL_JWS_BASE64URL');
  return bytes;
}

async function independentSignature(token, jwk, snapshot) {
  requireCheck(jwk.kty === 'EC' && jwk.crv === 'P-256' && jwk.alg === 'ES256' && jwk.d === undefined,
    'INVALID_PUBLIC_JWK');
  const parts = token.split('.');
  requireCheck(parts.length === 3, 'INVALID_COMPACT_JWS');
  let header;
  let claims;
  try {
    const decoder = new TextDecoder('utf-8', { fatal: true });
    header = JSON.parse(decoder.decode(decodePart(parts[0])));
    claims = JSON.parse(decoder.decode(decodePart(parts[1])));
  } catch { throw new CheckError('INVALID_JWS_JSON'); }
  requireCheck(header.alg === 'ES256' && header.typ === 'JWT' && header.kid === snapshot.keyId,
    'INVALID_PROTECTED_HEADER');
  const signature = decodePart(parts[2]);
  requireCheck(signature.length === 64, 'INVALID_ES256_SIGNATURE_LENGTH');
  let verified = false;
  try {
    const key = await webcrypto.subtle.importKey('jwk', jwk, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
    verified = await webcrypto.subtle.verify(
      { name: 'ECDSA', hash: 'SHA-256' }, key, signature,
      new TextEncoder().encode(`${parts[0]}.${parts[1]}`)
    );
  } catch { throw new CheckError('WEBCRYPTO_VERIFICATION_FAILED'); }
  requireCheck(verified, 'INVALID_INDEPENDENT_SIGNATURE');
  const now = Math.floor(Date.now() / 1000);
  requireCheck(claims.jti === snapshot.ticketId && claims.paymentId === snapshot.request.paymentId
    && claims.iss === snapshot.system.issuer && claims.aud === snapshot.system.audience
    && claims.status === 'ISSUED' && Number.isSafeInteger(claims.exp) && claims.exp > now
    && Number.isSafeInteger(claims.nbf) && claims.nbf <= now, 'INVALID_RESTORED_CLAIMS');
}

async function snapshot() {
  const api = await client();
  const system = await api('/api/system');
  requireCheck(system.demoEnabled === true, 'SANDBOX_DISABLED');
  const page = await api('/api/tickets?limit=100&offset=0');
  requireCheck(Array.isArray(page.items) && Number.isSafeInteger(page.total) && page.total > 0,
    'NO_SANDBOX_TICKETS');
  const candidate = page.items.find(ticket => ticket.eventName === 'Noche de concierto'
    && ticket.venue === 'Auditorio Central' && ticket.status === 'ISSUED');
  requireCheck(candidate && UUID.test(candidate.ticketId), 'CLEAN_DEMO_TICKET_NOT_FOUND');
  const ticket = await api(`/api/tickets/${candidate.ticketId}`);
  const jwk = await api('/public-key', { auth: false });
  requireCheck(typeof jwk.kid === 'string' && jwk.kid === ticket.keyId && jwk.kid === system.keyId,
    'PUBLIC_KEY_ID_MISMATCH');
  const data = {
    version: 1,
    capturedAt: new Date().toISOString(),
    ticketId: ticket.ticketId,
    total: page.total,
    keyId: jwk.kid,
    tokenSha256: tokenHash(ticket),
    metadata: metadata(ticket),
    system: { issuer: system.issuer, audience: system.audience },
    request: replayRequest(ticket)
  };
  requireCheck(typeof data.system.issuer === 'string' && typeof data.system.audience === 'string',
    'INVALID_SYSTEM_CONTEXT');
  requireCheck(await count(api) === page.total, 'TICKET_TOTAL_CHANGED_DURING_SNAPSHOT');
  await mkdir('/results', { recursive: true });
  await writeFile(snapshotPath, JSON.stringify(data, null, 2) + '\n', { mode: 0o600 });
  console.log(`PASS ${ticket.ticketId}`);
}

async function verify() {
  let baseline;
  try { baseline = JSON.parse(await readFile(snapshotPath, 'utf8')); }
  catch { throw new CheckError('SNAPSHOT_UNAVAILABLE'); }
  requireCheck(baseline.version === 1 && UUID.test(baseline.ticketId)
    && Number.isSafeInteger(baseline.total) && baseline.total > 0
    && /^[0-9a-f]{64}$/.test(baseline.tokenSha256)
    && baseline.request?.paymentId === baseline.metadata?.paymentId, 'INVALID_SNAPSHOT');
  const result = {
    ticketId: baseline.ticketId,
    checkedAt: new Date().toISOString(),
    passed: false,
    sandboxEnabled: false,
    keyIdPreserved: false,
    tokenPreserved: false,
    metadataPreserved: false,
    totalPreservedBeforeReplay: false,
    replayIdempotent: false,
    signatureVerifiedIndependently: false,
    serverVerificationPassed: false,
    totalPreservedAfterReplay: false,
    finalTicketUnchanged: false
  };
  try {
    const api = await client();
    const system = await api('/api/system');
    requireCheck(system.demoEnabled === true, 'SANDBOX_DISABLED');
    result.sandboxEnabled = true;
    const jwk = await api('/public-key', { auth: false });
    requireCheck(jwk.kid === baseline.keyId && system.keyId === baseline.keyId, 'SIGNING_KEY_CHANGED');
    result.keyIdPreserved = true;
    const ticket = await api(`/api/tickets/${baseline.ticketId}`);
    requireCheck(tokenHash(ticket) === baseline.tokenSha256, 'PERSISTED_TOKEN_CHANGED');
    result.tokenPreserved = true;
    requireCheck(sameMetadata(ticket, baseline.metadata), 'PERSISTED_METADATA_CHANGED');
    result.metadataPreserved = true;
    requireCheck(await count(api) === baseline.total, 'TOTAL_CHANGED_BEFORE_REPLAY');
    result.totalPreservedBeforeReplay = true;
    const replay = await api('/api/demo/tickets', { method: 'POST', body: baseline.request });
    requireCheck(replay.replayed === true && replay.ticket?.ticketId === baseline.ticketId
      && tokenHash(replay.ticket) === baseline.tokenSha256 && sameMetadata(replay.ticket, baseline.metadata),
    'REPLAY_CHANGED_TICKET');
    result.replayIdempotent = true;
    await independentSignature(ticket.token, jwk, baseline);
    result.signatureVerifiedIndependently = true;
    const checked = await api('/verify', { method: 'POST', body: { token: ticket.token }, auth: false });
    requireCheck(checked.valid === true, 'SERVER_VERIFICATION_REJECTED');
    result.serverVerificationPassed = true;
    requireCheck(await count(api) === baseline.total, 'TOTAL_CHANGED_AFTER_REPLAY');
    result.totalPreservedAfterReplay = true;
    const finalTicket = await api(`/api/tickets/${baseline.ticketId}`);
    requireCheck(tokenHash(finalTicket) === baseline.tokenSha256 && sameMetadata(finalTicket, baseline.metadata),
      'TICKET_CHANGED_AFTER_VERIFICATION');
    result.finalTicketUnchanged = true;
    result.passed = true;
  } catch (error) {
    result.errorCode = error instanceof CheckError ? error.code : 'RELEASE_CHECK_FAILED';
    throw error;
  } finally {
    await mkdir('/results', { recursive: true });
    await writeFile(resultPath, JSON.stringify(result, null, 2) + '\n', { mode: 0o600 });
  }
  console.log(`PASS ${baseline.ticketId}`);
}

try {
  const mode = process.argv[2];
  requireCheck(mode === 'snapshot' || mode === 'verify', 'EXPECTED_SNAPSHOT_OR_VERIFY');
  await (mode === 'snapshot' ? snapshot() : verify());
} catch (error) {
  console.error(`FAIL ${error instanceof CheckError ? error.code : 'RELEASE_CHECK_FAILED'}`);
  process.exitCode = 1;
}
