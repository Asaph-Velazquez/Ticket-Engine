import { test, expect } from '@playwright/test';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { PNG } from 'pngjs';
import jsQR from 'jsqr';

const output = process.env.QA_OUTPUT_DIR || '/results';

function decodeQr(dataUrl, expectedToken) {
  if (!/^data:image\/png;base64,[A-Za-z0-9+/]+={0,2}$/.test(dataUrl)) {
    throw new Error('El QR debe exportarse como una imagen PNG autónoma.');
  }
  const bytes = Buffer.from(dataUrl.split(',')[1], 'base64');
  const png = PNG.sync.read(bytes);
  const qr = jsQR(new Uint8ClampedArray(png.data), png.width, png.height, { inversionAttempts: 'dontInvert' });
  expect(Boolean(qr), 'El PNG debe ser un QR decodificable').toBeTruthy();
  expect(qr?.data === expectedToken, 'El QR conserva exactamente el JWS emitido').toBeTruthy();
  return bytes;
}

// React receives a native input event. Sensitive values never enter fill() action logs.
async function setInputWithoutLogging(locator, value) {
  try {
    await locator.evaluate((element, text) => {
      const prototype = element.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      const setter = Object.getOwnPropertyDescriptor(prototype, 'value')?.set;
      if (!setter) throw new Error('Control de texto no disponible');
      setter.call(element, text);
      element.dispatchEvent(new Event('input', { bubbles: true }));
      element.dispatchEvent(new Event('change', { bubbles: true }));
    }, value);
  } catch {
    throw new Error('No se pudo completar el control de texto; su contenido se mantiene oculto.');
  }
}

async function noOverflow(page) {
  const fits = await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1);
  expect(fits, 'La interfaz no debe presentar desbordamiento horizontal').toBeTruthy();
}

async function scrollToTop(page) {
  await page.evaluate(() => new Promise(resolve => {
    window.scrollTo(0, 0);
    requestAnimationFrame(() => requestAnimationFrame(resolve));
  }));
}

async function textValue(locator) {
  return locator.evaluate(element => String('value' in element ? element.value : element.textContent).trim());
}

async function revealJws(page) {
  const disclosure = page.getByRole('button', { name: /^Inspeccionar JWS/ });
  if (await disclosure.getAttribute('aria-expanded') === 'false') await disclosure.click();
}

function emissionResponse(response) {
  return new URL(response.url()).pathname === '/api/demo/tickets' && response.request().method() === 'POST';
}

test('emisión real, replay, QR, HTML autónomo y verificación local en el sandbox', async ({ page, context }, testInfo) => {
  await mkdir(output, { recursive: true });
  const errors = [];
  const remoteVerifications = [];
  page.on('pageerror', () => errors.push('Error JavaScript de página'));
  page.on('request', request => {
    if (new URL(request.url()).pathname === '/verify') remoteVerifications.push(request.method());
  });
  const adminToken = (await readFile('/run/secrets/admin-token', 'utf8')).trim();
  if (!adminToken) throw new Error('Falta el secreto administrativo de QA.');

  await page.goto('/');
  expect(
    await page.evaluate(() => globalThis.isSecureContext && Boolean(globalThis.crypto?.subtle)),
    'QA debe usar el loopback seguro nativo con WebCrypto, antes de emitir boletos'
  ).toBeTruthy();
  const login = page.getByTestId('login-form');
  await expect(login).toBeVisible();
  await setInputWithoutLogging(page.getByLabel(/^Clave administrativa/), adminToken);
  await page.getByRole('button', { name: 'Entrar a la consola', exact: true }).click();
  await expect(page.getByTestId('logout')).toBeVisible();
  await noOverflow(page);

  await page.getByRole('button', { name: 'Emisión', exact: true }).click();
  const form = page.getByTestId('emission-form');
  await expect(form).toBeVisible();
  const eventName = 'Concierto QA <img src=x onerror=window.__qaInjected=1>';
  const venue = 'Auditorio QA & pruebas';
  const seat = `QA-${randomUUID().slice(0, 8)}`;
  const paymentId = randomUUID();
  const eventId = randomUUID();
  const buyerId = randomUUID();
  const start = new Date(Date.now() + 86_400_000);
  const end = new Date(start.getTime() + 7_200_000);
  await form.getByLabel('Nombre del evento', { exact: true }).fill(eventName);
  await form.getByLabel('Recinto', { exact: true }).fill(venue);
  await form.getByLabel('Asiento', { exact: true }).fill(seat);
  await form.getByLabel('Inicio del evento', { exact: true }).fill(start.toISOString().slice(0, 16));
  await form.getByLabel('Fin del evento', { exact: true }).fill(end.toISOString().slice(0, 16));
  await form.getByLabel(/^Importe/).fill('1250.00');
  const currency = form.getByRole('combobox', { name: /^Moneda/ });
  if (await currency.evaluate(element => element.tagName === 'SELECT')) await currency.selectOption('MXN');
  else await currency.fill('MXN');
  await form.getByText('Identificadores de integración', { exact: true }).click();
  await form.getByLabel('ID del pago', { exact: true }).fill(paymentId);
  await form.getByLabel('ID del evento', { exact: true }).fill(eventId);
  await form.getByLabel('ID del comprador', { exact: true }).fill(buyerId);

  const [createdResponse] = await Promise.all([
    page.waitForResponse(emissionResponse),
    page.getByRole('button', { name: 'Emitir boleto sandbox', exact: true }).click()
  ]);
  expect(createdResponse.status()).toBe(201);
  const created = await createdResponse.json();
  expect(created.replayed).toBe(false);
  const ticket = created.ticket;
  expect(Boolean(ticket?.ticketId)).toBeTruthy();
  expect(ticket.paymentId).toBe(paymentId);
  expect(ticket.eventId).toBe(eventId);
  expect(ticket.buyerId).toBe(buyerId);
  expect(ticket.seatId).toBe(seat);
  await expect(page.getByTestId('ticket-id')).toContainText(ticket.ticketId);
  await revealJws(page);
  expect((await textValue(page.getByTestId('ticket-jws'))) === ticket.token).toBeTruthy();
  expect((await textValue(page.getByTestId('ticket-payload'))) === ticket.payloadJson).toBeTruthy();
  expect(await page.evaluate(() => window.__qaInjected ?? 0)).toBe(0);

  await page.getByRole('button', { name: 'Emisión', exact: true }).click();
  const [replayResponse] = await Promise.all([
    page.waitForResponse(emissionResponse),
    page.getByRole('button', { name: 'Reenviar último pago', exact: true }).click()
  ]);
  expect(replayResponse.status()).toBe(200);
  const replay = await replayResponse.json();
  expect(replay.replayed).toBe(true);
  expect(replay.ticket.ticketId).toBe(ticket.ticketId);
  expect(replay.ticket.token === ticket.token, 'El reintento devuelve el mismo JWS').toBeTruthy();

  await page.getByRole('button', { name: 'Boletos', exact: true }).click();
  await page.getByRole('button', { name: `Ver boleto ${ticket.ticketId}`, exact: true }).click();
  await expect(page.getByTestId('ticket-id')).toContainText(ticket.ticketId);
  await revealJws(page);
  expect((await textValue(page.getByTestId('ticket-jws'))) === ticket.token).toBeTruthy();
  const canvas = page.getByTestId('qr-code').locator('canvas');
  await expect(canvas).toBeVisible();
  const qrDataUrl = await canvas.evaluate(element => element.toDataURL('image/png'));
  const qrBytes = decodeQr(qrDataUrl, ticket.token);
  await writeFile(`${output}/${testInfo.project.name}-qr.png`, qrBytes);
  await noOverflow(page);
  await scrollToTop(page);
  await page.screenshot({
    path: `${output}/${testInfo.project.name}.png`,
    fullPage: true,
    mask: [page.locator('input[type="password"]')]
  });

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: 'Descargar HTML', exact: true }).click()
  ]);
  expect(download.suggestedFilename()).toBe(`boleto-${ticket.ticketId}.html`);
  const htmlPath = `${output}/${testInfo.project.name}-ticket.html`;
  await download.saveAs(htmlPath);
  const html = await readFile(htmlPath, 'utf8');
  expect(html.includes(adminToken), 'La exportación nunca incluye la clave administrativa').toBe(false);
  expect(/<script\b/i.test(html), 'El HTML exportado no contiene scripts').toBe(false);

  const exported = await context.newPage();
  const exportNetwork = [];
  exported.on('pageerror', () => errors.push('Error JavaScript de exportación'));
  exported.on('request', request => {
    if (['http:', 'https:'].includes(new URL(request.url()).protocol)) exportNetwork.push('Recurso externo');
  });
  await exported.goto(pathToFileURL(htmlPath).href);
  await expect(exported.getByRole('heading', { name: eventName, exact: true })).toBeVisible();
  await expect(exported.getByText(ticket.ticketId, { exact: true })).toBeVisible();
  expect((await exported.locator('pre').nth(0).textContent()) === ticket.token).toBeTruthy();
  expect((await exported.locator('pre').nth(1).textContent()) === ticket.payloadHash).toBeTruthy();
  expect((await exported.locator('pre').nth(2).textContent()) === ticket.payloadJson).toBeTruthy();
  await expect(exported.locator('script,iframe,object,embed,[onerror],[onload],[onclick]')).toHaveCount(0);
  await expect(exported.locator('[href^="javascript:"],[src^="javascript:"]')).toHaveCount(0);
  expect(await exported.evaluate(() => window.__qaInjected ?? 0)).toBe(0);
  const exportedQr = await exported.getByRole('img', { name: 'Código QR del JWS firmado', exact: true }).getAttribute('src');
  decodeQr(exportedQr || '', ticket.token);
  expect(exportNetwork.length, 'El HTML exportado no descarga recursos externos').toBe(0);
  await noOverflow(exported);
  await exported.close();

  await page.getByRole('button', { name: 'Verificar este boleto', exact: true }).click();
  const tokenInput = page.getByLabel(/^JWS para verificar/);
  await expect(tokenInput).toBeVisible();
  await setInputWithoutLogging(tokenInput, ticket.token);
  await page.getByRole('button', { name: 'Verificar firma local', exact: true }).click();
  await expect(page.getByTestId('verification-result')).toHaveAttribute('data-valid', 'true');

  const parts = ticket.token.split('.');
  const changedPayload = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'));
  changedPayload.seatId = 'QA-ALTERADO';
  const altered = `${parts[0]}.${Buffer.from(JSON.stringify(changedPayload)).toString('base64url')}.${parts[2]}`;
  await setInputWithoutLogging(tokenInput, altered);
  await page.getByRole('button', { name: 'Verificar firma local', exact: true }).click();
  await expect(page.getByTestId('verification-result')).toHaveAttribute('data-valid', 'false');
  expect(remoteVerifications.length, 'La firma se verifica localmente con WebCrypto').toBe(0);
  await noOverflow(page);

  // A second, human-readable sandbox ticket is kept separate from the hostile-text fixture.
  await page.getByRole('button', { name: 'Emisión', exact: true }).click();
  await page.getByRole('button', { name: 'Nuevo pago', exact: true }).click();
  const cleanForm = page.getByTestId('emission-form');
  await cleanForm.getByLabel('Nombre del evento', { exact: true }).fill('Noche de concierto');
  await cleanForm.getByLabel('Recinto', { exact: true }).fill('Auditorio Central');
  await cleanForm.getByLabel('Asiento', { exact: true }).fill('A-12');
  const integration = cleanForm.getByText('Identificadores de integración', { exact: true });
  if (!await integration.evaluate(element => element.closest('details').open)) await integration.click();
  await cleanForm.getByLabel('ID del pago', { exact: true }).fill(randomUUID());
  await cleanForm.getByLabel('ID del evento', { exact: true }).fill(randomUUID());
  await cleanForm.getByLabel('ID del comprador', { exact: true }).fill(randomUUID());
  await integration.click();
  const [cleanResponse] = await Promise.all([
    page.waitForResponse(emissionResponse),
    page.getByRole('button', { name: 'Emitir boleto sandbox', exact: true }).click()
  ]);
  expect(cleanResponse.status()).toBe(201);
  const clean = (await cleanResponse.json()).ticket;
  expect(clean.eventName).toBe('Noche de concierto');
  await expect(page.getByTestId('ticket-id')).toContainText(clean.ticketId);
  const cleanQr = await page.getByTestId('qr-code').locator('canvas').evaluate(element => element.toDataURL('image/png'));
  await writeFile(`${output}/${testInfo.project.name}-demo-qr.png`, decodeQr(cleanQr, clean.token));
  const [cleanDownload] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: 'Descargar HTML', exact: true }).click()
  ]);
  const cleanFilename = testInfo.project.name === 'desktop' ? 'boleto-demo.html' : 'mobile-boleto-demo.html';
  await cleanDownload.saveAs(`${output}/${cleanFilename}`);
  const cleanHtml = await readFile(`${output}/${cleanFilename}`, 'utf8');
  expect(cleanHtml.includes(clean.token), 'El ejemplo limpio conserva su JWS real').toBeTruthy();
  expect(cleanHtml.includes(adminToken), 'El ejemplo no contiene la clave administrativa').toBe(false);
  expect(/<script\b/i.test(cleanHtml)).toBe(false);
  const cleanInspector = page.getByRole('button', { name: /^Inspeccionar JWS/ });
  if (await cleanInspector.getAttribute('aria-expanded') === 'true') await cleanInspector.click();
  await noOverflow(page);
  await scrollToTop(page);
  await page.screenshot({
    path: `${output}/${testInfo.project.name}-demo.png`,
    fullPage: false,
    mask: [page.locator('input[type="password"]')]
  });
  await page.getByRole('article', { name: 'Boleto emitido', exact: true }).screenshot({
    path: `${output}/${testInfo.project.name}-boleto.png`
  });

  await page.getByTestId('logout').click();
  await expect(page.getByTestId('login-form')).toBeVisible();
  await expect(page.getByTestId('ticket-id')).toHaveCount(0);
  await expect(page.getByTestId('ticket-jws')).toHaveCount(0);
  const storage = await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length }));
  expect(storage).toEqual({ local: 0, session: 0 });
  expect((await page.content()).includes(adminToken)).toBe(false);
  expect(errors.length, 'No debe haber errores JavaScript durante el recorrido').toBe(0);
});
