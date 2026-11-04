import { formatDate } from "./money.js";

export function escapeHtml(value) {
  return String(value ?? "").replace(
    /[&<>"']/g,
    (character) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        character
      ],
  );
}

export function buildTicketHtml(ticket, qrDataUrl) {
  if (!/^data:image\/png;base64,[A-Za-z0-9+/]+={0,2}$/.test(qrDataUrl))
    throw new Error("No se pudo preparar el QR PNG del boleto.");
  const e = escapeHtml;
  const fields = [
    ["Boleto", ticket.ticketId],
    ["Pago", ticket.paymentId],
    ["Evento", ticket.eventId],
    ["Comprador", ticket.buyerId],
    ["Emitido", formatDate(ticket.issuedAt)],
    ["Válido hasta", formatDate(ticket.expiresAt)],
    ["Llave pública (kid)", ticket.keyId],
  ];
  return `<!doctype html>
<html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
<meta name="referrer" content="no-referrer"><title>Boleto ${e(ticket.ticketId)}</title>
<style>*{box-sizing:border-box}body{margin:0;padding:32px;background:#f1f5f3;color:#15332d;font:16px/1.5 system-ui,sans-serif}.ticket{max-width:800px;margin:auto;background:white;border:1px solid #cbd9d3;border-radius:16px;overflow:hidden}header{background:#15332d;color:white;padding:28px}header small{letter-spacing:3px}h1{font-size:30px;margin:12px 0}.body{padding:28px}.admission{display:flex;flex-wrap:wrap;gap:28px;align-items:center;border-bottom:2px dashed #b7cdc3;padding-bottom:26px}.qr{width:200px;height:200px}.seat{font-size:36px;font-weight:700;margin:0}dl{display:grid;grid-template-columns:180px minmax(0,1fr);gap:8px}dt{font-weight:600}dd{margin:0;overflow-wrap:anywhere}pre{white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-all;padding:16px;background:#f4f7f5;font:12px/1.6 monospace;border-radius:8px}.note{color:#536960;font-size:13px}@media(max-width:540px){body{padding:12px}.body,header{padding:20px}dl{grid-template-columns:1fr}dd{margin-bottom:12px}}@media print{body{background:white;padding:0}.ticket{border:1px solid #aaa;max-width:none}header{background:white;color:#15332d}.admission{break-inside:avoid}pre{font-size:9px}h2{break-after:avoid}*{print-color-adjust:exact}}</style></head>
<body><article class="ticket"><header><small>TICKET ENGINE · MÓDULO 04</small><h1>${e(ticket.eventName)}</h1><div>${e(ticket.venue)}</div></header>
<div class="body"><section class="admission"><img class="qr" src="${e(qrDataUrl)}" alt="Código QR del JWS firmado" width="200" height="200"><div><div>ASIENTO</div><p class="seat">${e(ticket.seatId)}</p><p>${e(formatDate(ticket.eventStartsAt))}</p><strong>${e(ticket.status)}</strong></div></section>
<h2>Datos del boleto</h2><dl>${fields.map(([label, value]) => `<dt>${e(label)}</dt><dd>${e(value)}</dd>`).join("")}</dl>
<h2>JWS original</h2><pre>${e(ticket.token)}</pre><h2>SHA-256 del payload</h2><pre>${e(ticket.payloadHash)}</pre><h2>Payload canónico original</h2><pre>${e(ticket.payloadJson)}</pre>
<p class="note">Documento autónomo para imprimir desde el navegador. El QR contiene el JWS firmado y es estático; la protección dinámica anticopia corresponde al Módulo 5. La firma y la vigencia deben verificarse al ingresar.</p></div></article></body></html>`;
}

export function downloadTicketHtml(ticket, canvas) {
  if (!canvas)
    throw new Error("El QR todavía no está listo. Intenta de nuevo.");
  const content = buildTicketHtml(ticket, canvas.toDataURL("image/png"));
  const url = URL.createObjectURL(
    new Blob([content], { type: "text/html;charset=utf-8" }),
  );
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = `boleto-${String(ticket.ticketId).replace(/[^A-Za-z0-9-]/g, "")}.html`;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
