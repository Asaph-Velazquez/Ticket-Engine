# Contrato del backend — Ticket Engine v1

Arquitectura: Saul Asaph. Criptografía: Jonathan Samuel. Emisión y persistencia:
Sunem Gizeht. API y webhook: Ricardo Carmona. QA/DevOps: Christopher Dominguez.

El contrato completo está en [openapi.yaml](openapi.yaml) y se sirve por HTTP en
`/openapi.yaml`. El backend usa Java para REST, autenticación de transporte y
criptografía; Kotlin para DTOs, validación del negocio, emisión y JDBC. Cada
módulo conserva su propia base; la integración es exclusivamente HTTP.

## Módulo 3 → emisión

Enviar `POST /webhooks/payments/confirmed` o su alias `POST /api/tickets` con
`Content-Type: application/json` y los siguientes campos obligatorios:

```json
{
  "paymentId": "5b0cc2a7-d6b9-4c3d-a39e-42a5477d5671",
  "eventId": "97b11d5c-5d63-4787-abef-a35edfcabf93",
  "buyerId": "80863fb0-da9c-40e9-874c-6c2be473ec61",
  "seatId": "A-12",
  "eventName": "Noche de concierto",
  "venue": "Auditorio Central",
  "eventStartsAt": "2030-01-01T19:00:00Z",
  "eventEndsAt": "2030-01-01T22:00:00Z",
  "amountMinor": 125000,
  "currency": "MXN",
  "paymentStatus": "PAID"
}
```

UUIDs deben ser no cero. Asiento: identificador seguro de hasta64 caracteres.
Nombre y recinto: de1 a140 caracteres, sin controles. Montos: enteros en unidades
mínimas de la moneda ISO4217, entre0 y9999999999999. El estado debe ser `PAID`.
Fechas se normalizan a segundos UTC antes de compararlas, persistirlas y firmarlas.
El fin debe ser posterior al inicio; para una emisión nueva debe ser futuro y no
superar cinco años. No se aceptan campos desconocidos, duplicados, nulos en campos
obligatorios, tipos incompatibles ni JSON sobrante.

La autenticación exige estos encabezados:

```text
X-Payment-Timestamp: <epoch actual en segundos>
X-Payment-Signature: <64 caracteres hexadecimales>
```

```text
signature = hex(HMAC-SHA256(
  UTF8(ticket.webhook-secret),
  UTF8(X-Payment-Timestamp + ".") || bytes_exactos_del_body
))
```

El secreto se comparte con el Módulo3 por el gestor de secretos; es texto UTF-8,
no se decodifica como base64. No mandarlo en el body ni publicarlo al navegador.
El timestamp tiene una ventana absoluta de300 segundos. Cualquier cambio de
espacios, saltos de línea o bytes del body requiere recalcular el HMAC. Se
verifica antes de deserializar. No usar el secreto administrativo como HMAC.

Respuestas:

- `201 {ticket, replayed:false}`: nueva emisión confirmada y persistida.
- `200 {ticket, replayed:true}`: mismo pago y contenido normalizado; se devuelve el
  mismo UUID y JWS exacto, incluso si el evento ya venció.
- `409 PAYMENT_CONFLICT`: el pago ya existe con otro contenido.
- `409 SEAT_ALREADY_ISSUED`: otro pago ya emitió el mismo asiento para ese evento.
- `401 INVALID_PAYMENT_AUTH`: firma/timestamp ausentes, inválidos o vencidos.
- `400 INVALID_REQUEST`: JSON o negocio inválidos.
- `413 BODY_TOO_LARGE`: body supera32KiB, incluso por transferencia chunked.

La ventana temporal reduce replays de red; la restricción única `payment_id` y la
huella SHA-256 del request normalizado garantizan idempotencia tras reinicios y
con reintentos concurrentes. Se usa `INSERT ... ON CONFLICT(payment_id) DO NOTHING`
y lectura posterior bajo `READ_COMMITTED`, sin bloqueos distribuidos en memoria.
La restricción `(event_id, seat_id)` resuelve carreras de asignación de asiento.
La respuesta HTTP se envía después de confirmar la transacción de persistencia.

Esta versión emite **un boleto por paymentId**. Un pago de múltiples asientos
requiere acordar otro contrato con el Módulo3; no reutilizar el mismo pago con
distintos asientos. Este servicio confía en la confirmación autenticada del
Módulo3; no consulta una base ajena ni implementa la pasarela bancaria.

## Módulos 5 y 6 → llave pública y verificación

`GET /public-key` devuelve un JWK EC P-256; `GET /.well-known/jwks.json` devuelve
`{keys:[JWK]}`. `kid` es el thumbprint público SHA-256. No se publica la llave
privada ni se aceptan URLs de llave suministradas por el token.

El JWT tiene `alg=ES256`, `typ=JWT`, `kid` fijado al emisor. Perfil de claims:

```text
iss=ticket-engine                aud=ticket-wallet-access
sub=buyerId                      jti=ticketId
iat=issuedAt epoch seconds       nbf=issuedAt epoch seconds
exp=eventEndsAt epoch seconds    schemaVersion=1
status=ISSUED                    paymentId, eventId, seatId
eventName, venue                 eventStartsAt epoch seconds
amountMinor, currency
```

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

`POST /api/demo/tickets` usa el mismo DTO, exige Bearer y `ticket.demo-enabled=true`.
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
