# Verificación de persistencia después de recrear contenedores

Responsables: Christopher Dominguez (QA/DevOps) y Saul Asaph (arquitectura).

## Alcance

`docker/release-check.mjs` utiliza solo Node nativo dentro de `node:22-alpine`.
Consulta la API REST y la llave pública; no accede a PostgreSQL, a la credencial
HMAC ni a la llave privada. Se conecta exclusivamente al alias Docker
`http://ticket-engine-api:8080` y exige que la emisión sandbox esté habilitada.

El modo `snapshot` selecciona un boleto «Noche de concierto» / «Auditorio Central»
generado por QA navegador y captura su identificador, metadatos, total de boletos,
`kid` y SHA-256 del token. Deriva la solicitud original con los datos del boleto y
el monto/moneda del payload. No almacena el token completo ni el secreto
administrativo en `release-snapshot.json`.

Después de recrear los servicios conservando sus volúmenes, `verify` comprueba:

- Misma identidad de llave pública, boleto, huella del JWS y metadatos.
- Mismo total de boletos antes del reenvío.
- Reenvío del pago original con HTTP 200 y `replayed=true`, conservando el boleto.
- Firma ES256 verificada independientemente mediante WebCrypto de Node y JWK
  pública, sin doble hash y con la firma JOSE de 64 bytes.
- Contexto y fechas básicos de claims, además de respuesta válida de `/verify`.
- Mismo total y boleto intacto después del reenvío y la verificación.

Las solicitudes tienen timeout de diez segundos y hasta tres intentos acotados
ante fallos transitorios de red o HTTP 502/503/504. No se imprimen secretos,
cuerpos HTTP ni JWS: el éxito muestra únicamente `PASS` y el UUID del boleto.
Los fallos muestran un código seguro y terminan con código de salida 1.

## Procedimiento reproducible

Requisitos: stack local saludable, secretos ya generados y un boleto limpio de
QA navegador disponible. Detener otras emisiones mientras se compara el total.
Estos comandos son para PowerShell en la raíz del repositorio y no requieren
instalar Node en el host:

```powershell
docker run --rm --network ticket-engine-integration --mount "type=bind,source=${PWD}/docker/release-check.mjs,target=/check.mjs,readonly" --mount "type=bind,source=${PWD}/.secrets/admin-token,target=/run/secrets/admin-token,readonly" --mount "type=bind,source=${PWD}/artifacts/qa,target=/results" node:22-alpine node /check.mjs snapshot
docker compose up -d --force-recreate --wait db migrate app web
docker run --rm --network ticket-engine-integration --mount "type=bind,source=${PWD}/docker/release-check.mjs,target=/check.mjs,readonly" --mount "type=bind,source=${PWD}/.secrets/admin-token,target=/run/secrets/admin-token,readonly" --mount "type=bind,source=${PWD}/artifacts/qa,target=/results" node:22-alpine node /check.mjs verify
```

Ajustar el nombre de red si el orquestador cambió `TICKET_ENGINE_NETWORK`. El script
acepta `API_BASE` únicamente con el alias y puerto anteriores. No usar producción,
`down -v` ni eliminar credenciales o volúmenes entre los dos modos. El reenvío usa
el endpoint administrativo de demostración y debe recuperar el boleto existente,
sin emitir otro.

## Artefactos

- `artifacts/qa/release-snapshot.json`: referencia de comparación sin JWS completo
  ni secretos administrativos.
- `artifacts/qa/release-result.json`: UUID, fecha y comprobaciones booleanas. En
  un fallo también incluye un código seguro; `passed` solo es verdadero cuando
  terminaron todas las comprobaciones.

El script comprueba la continuidad de datos y firma; la recreación de contenedores
la ejecuta y documenta el operador por separado. No demuestra recuperación ante
pérdida de volúmenes, un backup externo ni integración con otros microservicios.

## Resultado

**PASS — 4 de noviembre de 2026.** El agente principal ejecutó realmente
`snapshot`, recreó `db`, `migrate`, `app` y `web` con el comando anterior conservando
los volúmenes, confirmó la migración con código de salida 0 y los servicios
saludables, y ejecutó `verify`.
Ambos modos terminaron correctamente para el boleto
`3cec6328-3c79-47b8-97f0-af7026ad2a73`.

`artifacts/qa/release-result.json` contiene `passed: true` y todas las
comprobaciones booleanas en verdadero: contexto sandbox, `kid`, huella del token,
metadatos, totales antes y después del reenvío, idempotencia, firma independiente
WebCrypto, respuesta válida del servidor y boleto final sin cambios.

La evidencia confirma continuidad de boletos y llaves después de esa recreación
con los mismos volúmenes. El autor de este documento registró los resultados
comunicados por el agente principal, sin repetir la ejecución.
