# Verificación del panel React en navegador

Responsable de interfaz: Jeanette Verónica. QA y contenedores: Christopher
Dominguez. El navegador, Node y todas las dependencias se ejecutan en Docker.

## Alcance y seguridad

Las pruebas usan Chromium de `mcr.microsoft.com/playwright:v1.64.1-noble` y
`@playwright/test` 1.64.1. Las versiones de `pngjs` 7.0.0 y `jsqr` 1.4.0 se
verificaron en el registro npm desde el contenedor y permiten comprobar el QR
contra el JWS real, sin simular respuestas exitosas de la API.

El único secreto de QA es `/run/secrets/admin-token`, montado de solo lectura.
No se monta la llave privada, la credencial HMAC ni un rol administrador de base.
El token se mantiene fuera de logs y se introduce sin llamadas Playwright `fill`
que pudieran incluirlo en errores. Trazas, videos y capturas automáticas en fallos
están desactivados. Las capturas explícitas se toman con la sesión ya iniciada y
ocultan cualquier campo password que pudiera estar presente.

Los proyectos usan pantallas de escritorio de 1440×1000 y móvil de 390×844.
La configuración permite únicamente `http://localhost:8080` o
`http://127.0.0.1:8080` dentro del contenedor de QA. **Las pruebas emiten boletos
sandbox reales** en la base local; nunca deben dirigirse a producción.

WebCrypto exige un contexto seguro. Compose configura `browser-tests` con
`network_mode: service:web`: comparte el espacio de red del servicio web y accede
por loopback nativo, sin banderas que alteren el tratamiento de orígenes inseguros
ni polyfills criptográficos. Antes de iniciar sesión o emitir, la prueba exige
que `isSecureContext` y `crypto.subtle` estén disponibles. La UI usada por personas
en `http://localhost:8085` también utiliza loopback seguro. En producción remota
se requiere HTTPS. `--no-sandbox` se limita al proceso Chromium de QA que ejecuta
como root en su contenedor.

## Ejecución

El recorrido de escritorio y el móvil comprueban:

- Inicio de sesión contra la API real y emisión administrativa sandbox.
- Reenvío del mismo pago con el mismo identificador y JWS, seguido de selección
  del boleto en el listado real.
- Comparación del JWS, payload y metadatos mostrados con la respuesta emitida.
- Decodificación independiente del QR PNG tanto de la pantalla como del HTML.
- Descarga y apertura del boleto HTML: contenido conservado, texto hostil escapado,
  sin scripts, manejadores inline, recursos externos ni secreto administrativo.
- Firma válida y payload alterado rechazado mediante WebCrypto, sin POST `/verify`.
- Ausencia de desbordamiento horizontal y errores JavaScript; cierre de sesión
  que retira el detalle privado y deja vacíos localStorage y sessionStorage.
- Segundo boleto de presentación con nombre «Noche de concierto», recinto
  «Auditorio Central» y asiento A-12 de un evento UUID nuevo. Se conserva aparte
  del caso de seguridad que contiene texto hostil.

```powershell
docker compose --profile tools build browser-tests
docker compose up -d --wait
docker compose --profile tools run --no-deps --rm browser-tests
```

Los resultados, capturas y boletos HTML exportados se escriben en `artifacts/qa/`.
Son datos del sandbox; los HTML contienen el JWS del boleto de prueba y no una
credencial administrativa. El reporte JSON no incluye trazas ni cuerpos HTTP.

Cada proyecto de pantalla emite dos boletos sandbox (cuatro en total por ejecución
completa): uno para seguridad y otro de presentación. El ejemplo legible se guarda
como `boleto-demo.html` en escritorio y `mobile-boleto-demo.html` en móvil. Las
capturas `desktop-demo.png` (1440×1000) y `mobile-demo.png` (390×844) muestran la
consola con ese ejemplo, y `desktop-boleto.png` / `mobile-boleto.png` capturan el pase.

## Resultado

Ejecución coordinada por el agente principal el **4 de noviembre de 2026**:
**2 recorridos aprobados en 5.8 segundos**, escritorio y móvil, mediante el
comando Docker anterior. El agente principal ejecutó las pruebas; el autor de
este documento registró los resultados comunicados sin repetir la suite.

Ambos recorridos completaron autenticación, emisión real, reintento idempotente,
selección en el registro, decodificación del QR, exportación autónoma con texto
hostil escapado, firma válida y manipulación rechazada por WebCrypto, ejemplo
limpio, cierre de sesión y comprobaciones de almacenamiento. No hubo errores
JavaScript ni desbordamiento horizontal. El contexto seguro se comprobó antes
de emitir usando loopback nativo compartido con `web`.

Artefactos en `artifacts/qa/`:

| Archivo | Contenido |
|---|---|
| `boleto-demo.html` | Boleto de presentación de escritorio, «Noche de concierto» |
| `mobile-boleto-demo.html` | Boleto de presentación de móvil |
| `desktop-demo.png` | Consola de escritorio, 1440×1000, sin inspector expandido |
| `mobile-demo.png` | Consola móvil, 390×844, sin inspector expandido |
| `desktop-boleto.png`, `mobile-boleto.png` | Pase limpio seleccionado |
| `desktop-demo-qr.png`, `mobile-demo-qr.png` | QR PNG de los boletos de presentación |
| `desktop-ticket.html`, `mobile-ticket.html` | Exportaciones del caso de seguridad con texto hostil escapado |
| `desktop.png`, `mobile.png` | Capturas completas del recorrido de seguridad |
| `desktop-qr.png`, `mobile-qr.png` | QR PNG del recorrido de seguridad |
| `playwright-report.json` | Resultado estructurado de ambos proyectos |

Esta evidencia cubre el panel y su API/PostgreSQL locales. El QR es estático y no
representa la protección dinámica anticopia del Módulo 5. No se probó la
integración con los demás microservicios, cobros reales ni validación en puertas.
