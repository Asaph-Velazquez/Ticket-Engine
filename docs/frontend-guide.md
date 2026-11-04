# Consola interna React — Módulo 4

Responsable UI: Jeanette Verónica. QA/DevOps: Christopher Dominguez.

## Arranque exclusivamente en Docker

Desde la raíz del repositorio, después de preparar secretos y backend según
el README:

```powershell
docker compose up --build -d --wait
```

Abrir `http://localhost:8085`. El navegador usa el proxy del mismo origen para
acceder al backend; PostgreSQL y las llaves privadas no están disponibles en la
red del panel. Node, npm, Vite y las dependencias se ejecutan únicamente durante
las etapas Docker. La imagen final sirve archivos estáticos con Nginx, UID101,
filesystem de solo lectura y `/tmp` temporal.

La etapa `runtime` de `frontend/Dockerfile` es la imagen web. La etapa `test`
ejecuta Vitest:

```powershell
docker compose --profile tools run --build --rm frontend-tests
```

Dependencias directas y lockfile fijan React19.3.1, Vite8.3.5, plugin-react6.2.1,
Vitest5.1.0 y qrcode.react4.2.0. No ejecutar npm ni instalar Node en el host.

## Acceso y datos de sesión

La verificación pública está disponible sin autenticación. Para emitir o consultar
el registro, ingresar la **Clave administrativa** generada para el entorno, guardada
en `.secrets/admin-token`. Esa clave corresponde a administración interna del
Módulo4, no a un comprador ni a un JWT del Módulo1. No publicar el archivo ni
incorporarlo en el bundle o la configuración de Nginx.

El panel conserva la clave exclusivamente en memoria React. No usa cookies,
localStorage, sessionStorage ni URLs para almacenarla. Cerrar sesión limpia la
clave, formulario, boleto, registro y verificación, cancela solicitudes
administrativas pendientes y detiene el polling. Recargar la página exige volver
a ingresar. La vista Boletos actualiza una página de hasta20 registros cada cinco
segundos mientras la sesión está activa.

## Emitir y reintentar

1. En **Emisión**, completar evento, recinto, asiento, importe y fechas locales
   del navegador. El importe se convierte de forma exacta a unidades mínimas
   mediante aritmética entera; no se admiten más de dos decimales.
2. Revisar **Identificadores de integración**. El panel prepara UUID de pago,
   evento y comprador para la demostración; se pueden reemplazar por los del
   contrato. Para varios asientos del mismo evento, conservar su eventId.
3. Pulsar **Emitir boleto sandbox**. El backend debe tener habilitado el modo
   demo y responder con el boleto real persistido. El panel no simula una respuesta
   exitosa ni realiza un cobro monetario.
4. Si hay un problema de red, **Reenviar último pago** manda exactamente el body
   del último intento. El backend devuelve el mismo boleto y JWS si ya lo emitió.
5. Para emitir otro boleto, pulsar explícitamente **Nuevo pago**, seleccionar el
   nuevo asiento y revisar los datos. No se cambia silenciosamente el paymentId.

El backend rechaza otro contenido para un pago ya usado y evita vender de nuevo
el mismo asiento del evento. El panel no consulta disponibilidad ni sustituye al
Módulo2. Si `demoEnabled=false`, la emisión sandbox se deshabilita; la emisión
productiva se realiza mediante el webhook HMAC del Módulo3.

## Boleto HTML y QR

El pase muestra evento, recinto, fecha, asiento, UUID y el QR del JWS real. El QR
usa una zona de silencio de cuatro módulos y un canvas de768 píxeles para exportar
una imagen PNG nítida. **Descargar HTML** produce un documento autónomo con:

- Datos e identificadores originales del boleto.
- QR PNG embebido que contiene el JWS completo.
- Token original, SHA-256 y payload canónico, sin alterar bytes firmados.
- CSS de impresión y política CSP restrictiva, sin JavaScript ni recursos remotos.

Se escapan todos los valores externos antes de construir el HTML, incluidos los
metadatos y el payload. Abrir el archivo descargado y usar la función de impresión
del navegador para imprimirlo o guardarlo como PDF. El documento contiene datos
del boleto: entregarlo solo al destinatario autorizado.

El QR es **estático** y representa el JWS firmado. La protección dinámica anticopia
corresponde al Módulo5; la admisión, consumo único y auditoría corresponden al
Módulo6.

## Verificación independiente en el navegador

**Verificar este boleto** prepara el token seleccionado. También se puede pegar
un JWS en **Verificación** sin iniciar sesión. **Verificar firma local** descarga
únicamente `/public-key` y usa WebCrypto ECDSA P-256/SHA-256 sobre los bytes del JWS.
No envía el boleto a `/verify` ni toma la decodificación como prueba de autenticidad.

Se restringen algoritmo, tipo, `kid`, codificación base64url, formato canónico,
perfil de claims v1, emisor, audiencia, UUID, estado y vigencia. Un token alterado,
vencido o de otra llave produce un resultado inválido. El inspector JWS muestra
encabezado, payload y firma para diagnóstico; la inspección por sí sola no verifica
la firma.

WebCrypto requiere un origen seguro: HTTPS o `localhost`. Para acceder desde otra
computadora usar HTTPS del sistema global; el navegador puede bloquear esta API
en una dirección HTTP de la red local. Confiar en la llave descargada exige
confiar en ese origen y en su transporte. La verificación local no registra una
entrada ni comprueba que el boleto no se haya consumido previamente.

## Proxy y seguridad del servicio web

Nginx escucha8080 dentro del contenedor, publica la ruta local `/health` y enruta
`/api/`, `/verify`, `/public-key`, `/.well-known/jwks.json`, `/openapi.yaml` y
`/actuator/health` hacia el alias único `ticket-engine-api:8080`. El resolvedor DNS
Docker permite recrear el backend sin fijar una IP obsoleta ni confundirlo con
otro servicio llamado `app` de otro módulo.

El frontend no recibe secretos montados. Tiene límite de32KiB para bodies y
cabeceras CSP, `nosniff`, protección de frames, `no-store` y referencia restrictiva.
Las librerías, tipografías y scripts se sirven localmente, sin CDN. Los únicos
datos administrativos salen por HTTP hacia las rutas protegidas del mismo origen.

## Pruebas

Vitest cubre conversión exacta de importes, rechazo de formatos ambiguos, HTML
autónomo y escape XSS, QR embebido, conservación del JWS, firma WebCrypto real,
alteración de firma/payload, llaves distintas, vigencia y claims inválidos,
encabezados duplicados y falta de WebCrypto. La validación de navegador se ejecuta
aparte en Docker mediante el flujo QA del repositorio, con pruebas de emisión,
reintento, QR, exportación autónoma y viewport móvil/escritorio.
