# Contrato del backend — Ticket Engine v1

Arquitectura: Saul Asaph. Criptografía: Jonathan Samuel. Emisión y persistencia:
Sunem Gizeht. API y webhook: Ricardo Carmona. QA/DevOps: Christopher Dominguez.

El contrato completo está en [openapi.yaml](openapi.yaml) y se sirve por HTTP en
`/openapi.yaml`. El backend usa Java para REST, autenticación de transporte y
criptografía; Kotlin para DTOs, validación del negocio, emisión y JDBC. Cada
módulo conserva su propia base; la integración es exclusivamente HTTP.

## Módulos 3 y 2 → emisión

El boleto se emite al unir dos mensajes HTTP que comparten `reservationId`.
Pueden llegar en cualquier orden; el segundo en llegar dispara la firma. Ambos
quedan guardados de forma inmutable (`payment_confirmations`,
`reservation_details`) aunque falte su contraparte.

### Módulo 3: pago confirmado

`POST /webhooks/payments/confirmed` (alias `POST /api/tickets`):

```json
{
  "event": "payment.confirmed",
  "sourceModule": "M3_PAYMENTS",
  "targetModule": "M4_TICKET_ISSUANCE",
  "payload": {
    "paymentId": "pay_01J9X8K2M4N6P8Q0R2S4T6V8W0",
    "orderId": "ord_01J9X8J9A1B2C3D4E5F6G7H8J9",
    "reservationId": "res_778899",
    "buyerId": "usr_102938",
    "amountMinor": 125000,
    "currency": "MXN",
    "paymentStatus": "PAID",
    "paidAt": "2026-11-19T19:50:00Z"
  }
}
```

`event`, `sourceModule` y `targetModule` deben tener exactamente esos valores.
`paidAt` no puede estar más de 300 segundos en el futuro.

### Módulo 2: detalle de evento y asiento

`POST /webhooks/reservations/confirmed`:

```json
{
  "reservationId": "res_778899",
  "eventId": "id-del-evento",
  "seatId": "A-12",
  "eventName": "Noche de concierto",
  "venue": "Auditorio Central",
  "eventStartsAt": "2030-01-01T19:00:00Z",
  "eventEndsAt": "2030-01-01T22:00:00Z"
}
```

**`reservationId` es obligatorio**: es el campo que une este mensaje con el pago.

### Reglas comunes

Identificadores (`paymentId`, `orderId`, `reservationId`, `buyerId`, `eventId`,
`seatId`): `^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$`. Nombre y recinto: de 1 a 140
caracteres, sin controles. Montos: enteros en unidades mínimas de la moneda
ISO 4217, entre 0 y 9999999999999. Fechas normalizadas a segundos UTC; el fin
posterior al inicio y, para una reserva nueva, futuro y dentro de cinco años. No se
aceptan campos desconocidos, duplicados, nulos, tipos incompatibles ni JSON sobrante.

Cada módulo firma con **su propio secreto** HMAC:

| Módulo | Encabezados | Secreto |
|---|---|---|
| M3 | `X-Payment-Timestamp`, `X-Payment-Signature` | `.secrets/payment-hmac-secret` |
| M2 | `X-Reservation-Timestamp`, `X-Reservation-Signature` | `.secrets/reservation-hmac-secret` |

```text
signature = hex(HMAC-SHA256(
  UTF8(secreto),
  UTF8(timestamp + ".") || bytes_exactos_del_body
))
```

El secreto es texto UTF-8, no base64; se comparte por el gestor de secretos y
nunca va en el body. Ventana absoluta de 300 segundos. Se verifica antes de
deserializar; cualquier cambio de bytes requiere recalcular la firma.

Respuestas (iguales para ambos endpoints, cuerpo `{status, reservationId, ticket, replayed}`):

- `201 status=ISSUED, replayed=false`: este mensaje completó el par y emitió el boleto.
- `200 status=ISSUED, replayed=true`: mensaje repetido; mismo UUID y JWS exacto.
- `202 status=PENDING_RESERVATION` (M3) o `PENDING_PAYMENT` (M2), `ticket=null`:
  guardado; falta el mensaje del otro módulo.
- `409 PAYMENT_CONFLICT`: el pago ya existe con otro contenido.
- `409 RESERVATION_CONFLICT`: la reserva ya existe con otro contenido.
- `409 RESERVATION_ALREADY_PAID`: otro `paymentId` ya pagó esa reserva.
- `409 SEAT_ALREADY_ISSUED`: otro pago ya emitió ese asiento del evento.
- `401 INVALID_PAYMENT_AUTH` / `INVALID_RESERVATION_AUTH`: firma o timestamp inválidos.
- `400 INVALID_REQUEST`: JSON o negocio inválidos.
- `413 BODY_TOO_LARGE`: body mayor a 32 KiB, incluso chunked.

Si la emisión falla (por ejemplo, asiento ya emitido) se revierte también el
mensaje recibido, para que el módulo emisor pueda corregir y reenviar.

Los mensajes de la misma reserva se serializan con
`pg_advisory_xact_lock(reservationId)`, de modo que si M2 y M3 llegan al mismo
tiempo uno ve al otro y se emite exactamente un boleto. La idempotencia se basa en
la huella SHA-256 de cada mensaje normalizado, `payment_id` único,
`reservation_id` único y `(event_id, seat_id)` único. La respuesta HTTP se envía
después de confirmar la transacción.

Se emite **un boleto por `paymentId` / `reservationId`**. Este servicio confía en
los mensajes autenticados; no consulta bases ajenas ni la pasarela bancaria.

## Módulo 5 → Wallet

`GET /api/wallet/{buyerId}/tickets` con `Authorization: Bearer <ticket.wallet-token>`
(`.secrets/wallet-token`, distinto del token administrativo; ninguno abre las rutas
del otro). Devuelve hasta 200 boletos ordenados por fecha del evento:

```json
{
  "tickets": [
    {
      "ticketId": "3f6c1d2e-...",
      "event": "Noche de concierto",
      "date": "01/01/2030",
      "seat": "A-12",
      "titular": "usr_102938",
      "status": "ACTIVO",
      "signatureECDSA": "<JWS ES256 completo>"
    }
  ],
  "publicKeyJwk": { "kty": "EC", "crv": "P-256", "x": "...", "y": "..." }
}
```

- `date`: fecha de inicio en `America/Mexico_City` (`ticket.wallet-time-zone`), `dd/MM/yyyy`.
- `titular`: `buyerId` del Módulo 3; el nombre se resuelve con el Módulo 1.
- `status`: `ACTIVO` mientras no termine el evento; `VENCIDO` después.
- `signatureECDSA`: el JWS compacto completo (`header.payload.firma`). La Wallet
  verifica `SHA256withECDSA` en formato P1363 sobre `header.payload` con `publicKeyJwk`.
- `skSession` no se entrega: la clave de sesión del QR dinámico es del Módulo 5.

## Módulos 5 y 6 → llave pública y verificación

`GET /public-key` devuelve un JWK EC P-256; `GET /.well-known/jwks.json` devuelve
`{keys:[JWK]}`. `kid` es el thumbprint público SHA-256. No se publica la llave
privada ni se aceptan URLs de llave suministradas por el token.

El JWT tiene `alg=ES256`, `typ=JWT`, `kid` fijado al emisor. Perfil de claims:

```text
iss=ticket-engine                aud=ticket-wallet-access
sub=buyerId                      jti=ticketId (UUID)
iat=issuedAt epoch seconds       nbf=issuedAt epoch seconds
exp=eventEndsAt epoch seconds    schemaVersion=2
status=ISSUED                    paymentId, orderId, reservationId
eventId, seatId
eventName, venue                 eventStartsAt epoch seconds
amountMinor, currency
```

Los boletos v1 (previos a la integración, sin orderId/reservationId y con UUID)
siguen verificando.

`POST /verify` con `{ "token": "<JWS>" }` comprueba firma, encabezados, perfil,
emisor, audiencia, UUIDs, campos, estado y vigencia; no necesita consultar PostgreSQL.
Devuelve `200 {valid:true,reason:"VALID",payload:{...}}` para un boleto válido.
Para un token inválido devuelve `valid:false` y `payload:null` con razón
`INVALID_SIGNATURE_OR_FORMAT` o `INVALID_CLAIMS_OR_VALIDITY`.

Una firma válida por sí sola no autoriza entrada. El verificador offline del
Módulo6 debe descargar y fijar la llave por un canal confiable, conservarla
localmente, restringir algoritmo y `kid`, verificar los bytes exactos del JWS y
aplicar estas mismas reglas de claims/tiempo con un reloj confiable. El Módulo6
gestiona consumo único, puerta, evento esperado y auditoría. Un JWS estático no
reemplaza el QR dinámico anticopia del Módulo5.

ECDSA puede tener firmas matemáticamente equivalentes; la identidad es `jti` y
`paymentId`, nunca la firma o una cadena de token nueva. Se guarda y recupera el
JWS original. La [especificación criptográfica](crypto-specification.md) describe
canonicalización y formato ES256 sin doble hash.

## Administración y demo

`GET /api/tickets?limit=20&offset=0`, `GET /api/tickets/{ticketId}` y
`GET /api/system` exigen `Authorization: Bearer <ticket.admin-token>`. El token
es opaco, de al menos32 bytes, se compara mediante digest de tamaño fijo y no es
un JWT del comprador. Estas rutas son internas; una integración de usuarios debe
validar autorización y propiedad mediante el Módulo1 antes de entregar boletos.

`POST /api/demo/tickets` recibe un DTO plano con todos los campos (orderId y
reservationId opcionales, derivados del paymentId si faltan), exige Bearer y `ticket.demo-enabled=true`.
Es una simulación administrativa de pago confirmado para el panel React. El valor
por defecto en la aplicación es `false`; producción debe mantenerlo deshabilitado.
Este endpoint no valida una transacción de dinero real.

La autenticación es deny-by-default: solo rutas públicas exactas por método pasan
sin Bearer; la emisión normal exige además su HMAC en el controlador. Variantes
con parámetros matrix, codificación alternativa y rutas desconocidas no eluden
el filtro. Las respuestas incluyen `Cache-Control:no-store`, `nosniff`,
`X-Frame-Options:DENY` y política de referencia restrictiva. No se registran tokens,
secretos ni bodies sensibles. Usar HTTPS del sistema global fuera del entorno local.

## Persistencia inmutable

La migración [001-tickets.sql](../DB/migrations/001-tickets.sql) es ejecutada
por un contenedor de migraciones con credencial bootstrap. La aplicación usa
`ticket_engine_app`, que solo tiene `SELECT` e `INSERT` en `tickets` y no puede
crear objetos en el esquema ni alterar la tabla. Triggers rechazan `UPDATE`,
`DELETE` y `TRUNCATE` incluso cuando los ejecuta el propietario de forma normal.
Un administrador privilegiado que desactive triggers o altere el esquema queda
fuera de esta barrera: limitar esa credencial al migrador y proteger respaldos.

Se persisten payload canónico, SHA-256, token original, `kid`, huella del request,
identificadores y fechas. No existen endpoints de modificación o eliminación.
No borrar boletos para reintentar pagos; usar la operación idempotente.

## Verificación Docker

```powershell
docker compose --profile tools run --rm secrets-init
docker compose --profile tools run --rm tests
docker compose up --build -d --wait
```

Pruebas en PostgreSQL de test aislado cubren emisión y reintentos, concurrencia,
conflicto de asiento, replay tras vencimiento con reloj inyectado, claims firmados
inválidos, HTTP/HMAC, límites y bloqueo de mutaciones tanto con runtime como con
el propietario. No requieren instalar JDK, Kotlin ni Gradle en el host.
