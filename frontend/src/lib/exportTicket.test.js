import { describe, expect, it } from "vitest";
import { buildTicketHtml, escapeHtml } from "./exportTicket.js";

const png = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB";
const ticket = {
  ticketId: "b1082c24-3f18-4f77-bf08-59e407135a1d",
  paymentId: "payment-1",
  eventId: "event-1",
  buyerId: "buyer-1",
  eventName: "Concierto",
  venue: "Auditorio",
  seatId: "A-12",
  status: "ISSUED",
  eventStartsAt: "2030-01-01T19:00:00Z",
  issuedAt: "2026-11-04T00:00:00Z",
  expiresAt: "2030-01-01T22:00:00Z",
  token: "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln",
  payloadHash: "a".repeat(64),
  keyId: "public-key",
  payloadJson: '{"eventName":"Concierto"}',
};

describe("Boleto HTML autónomo", () => {
  it("conserva JWS, hash, QR embebido y todos los identificadores", () => {
    const html = buildTicketHtml(ticket, png);
    for (const field of [
      "ticketId",
      "paymentId",
      "eventId",
      "buyerId",
      "token",
      "payloadHash",
      "keyId",
    ])
      expect(html).toContain(ticket[field]);
    expect(html).toContain(png);
    expect(html).toContain(escapeHtml(ticket.payloadJson));
    expect(html).toContain(
      "default-src &#39;none&#39;".replaceAll("&#39;", "'"),
    );
    expect(html).not.toMatch(/<script|<link|<iframe|src=["']https?:/i);
    expect(html).toContain("@media print");
  });
  it("escapa todas las superficies de metadatos incluyendo token y payload", () => {
    const attack =
      '</pre><img src=x onerror="alert(1)"><script>alert(1)</script>&\'';
    const poisoned = Object.fromEntries(
      Object.keys(ticket).map((key) => [key, attack]),
    );
    const html = buildTicketHtml(poisoned, png);
    expect(html).not.toContain("<script>");
    expect(html).not.toContain("<img src=x");
    expect(html).not.toContain("</pre><img");
    expect(html).toContain(escapeHtml(attack));
    expect(html).not.toMatch(/<[^>]+onerror=/i);
  });
  it("rechaza URLs de QR que podrían ejecutar o descargar contenido externo", () => {
    for (const invalid of [
      "javascript:alert(1)",
      "https://attacker.invalid/qr.png",
      'data:image/svg+xml,<svg onload="alert(1)">',
      'data:image/png;base64,abc" onerror="x',
    ]) {
      expect(() => buildTicketHtml(ticket, invalid)).toThrow();
    }
  });
});
