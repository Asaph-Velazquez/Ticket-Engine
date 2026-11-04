# Ticket Engine — Módulo 4

Microservicio emisor de boletos inmutables con JWS ES256, Java/Kotlin y PostgreSQL
exclusivo. Java contiene criptografía, controladores y validación HTTP; Kotlin
contiene negocio, DTOs y persistencia.

## Puesta en marcha rápida

Solo se requiere Docker Desktop con contenedores Linux y Docker Compose.
No instalar Java, Kotlin, Gradle, Node ni dependencias de lenguaje en el host.

Desde PowerShell:

```powershell
cd C:\Users\samur\Downloads\Ticket-Engine
.\run.ps1
```

`run.ps1` inicializa los secretos y las llaves de desarrollo, construye las
imágenes y espera a que PostgreSQL, las migraciones, el backend y el panel estén
saludables. No imprime ningún secreto.

Opciones útiles:

```powershell
.\run.ps1 -NoBuild   # reutiliza las imágenes existentes
.\run.ps1 -Logs      # levanta el servicio y sigue los logs de app/web
.\run.ps1 -Down      # detiene los contenedores sin borrar volúmenes
```

Si se desea ejecutar los comandos manualmente, el equivalente es:

```powershell
docker compose --profile tools run --build --rm secrets-init
docker compose config --quiet
docker compose up --build -d --wait
```

Panel React: `http://localhost:8085`. API: `http://localhost:8084`. El puerto 8080
queda dentro de Docker. Abrir localmente `.secrets/admin-token` y pegar su valor
en el acceso administrativo del panel. La clave se mantiene solo en memoria del
navegador; no se incluye en JavaScript, Git, cookies ni almacenamiento persistente.

### Primer acceso y emisión de prueba

1. Abrir `.secrets/admin-token` con un editor local; el archivo se crea
   automáticamente y su valor no debe compartirse ni subirse a Git.
2. Abrir `http://localhost:8085` y pegar el token en el formulario de acceso.
3. Usar la emisión sandbox para crear un boleto de prueba.
4. Inspeccionar el JWS, verificar la firma y descargar el HTML con QR.

La llave ECDSA de desarrollo se conserva en el volumen Docker
`ticket-engine_signing_keys`. No se necesita proporcionar una llave manualmente.
No ejecutar `docker compose down -v` si se deben conservar los boletos o sus
firmas; ese comando elimina la base de datos, secretos y llaves de desarrollo.

Para regenerar deliberadamente un entorno limpio:

```powershell
docker compose down -v
Remove-Item -Recurse -Force .secrets
.\run.ps1
```

### Ejecutar la suite de pruebas

Todas las herramientas y dependencias se ejecutan dentro de contenedores:

```powershell
docker compose --profile tools run --build --rm tests
docker compose --profile tools run --build --rm frontend-tests
docker compose --profile tools build browser-tests
docker compose --profile tools run --no-deps --rm browser-tests
```

Las pruebas no usan la base de datos de desarrollo: crean una base PostgreSQL
aislada y llaves de prueba separadas.

Desde el panel se puede emitir un boleto sandbox, consultar los boletos guardados,
inspeccionar el JWS y verificarlo con la llave pública. El boleto se puede imprimir
o descargar como un archivo HTML independiente con QR del token firmado. El QR
de demostración es estático; el mecanismo dinámico anticopia pertenece al Módulo 5.

Las pruebas de navegador se ejecutan con Playwright dentro de Docker y guardan
capturas y el HTML exportado en `artifacts/qa`, excluido de Git y del build backend.
Después de ejecutar esas pruebas, el ejemplo listo para abrir está en
[`artifacts/qa/boleto-demo.html`](artifacts/qa/boleto-demo.html).

## API del emisor

| Método y ruta | Acceso | Uso |
|---|---|---|
| `POST /webhooks/payments/confirmed` | HMAC y timestamp | Confirmación del Módulo 3 |
| `POST /api/tickets` | HMAC y timestamp | Emisión REST equivalente |
| `POST /api/demo/tickets` | Bearer administrativo y sandbox habilitado | Demostración interna |
| `GET /api/tickets` | Bearer administrativo | Listado paginado |
| `GET /api/tickets/{ticketId}` | Bearer administrativo | Consulta de un boleto |
| `GET /api/system` | Bearer administrativo | Configuración pública del emisor y estado sandbox |
| `GET /public-key` | Público | Llave JWK sin material privado |
| `GET /.well-known/jwks.json` | Público | JWKS para verificación independiente |
| `POST /verify` | Público | Firma, contexto y vigencia del token |
| `GET /openapi.yaml` | Público | Contrato OpenAPI |
| `GET /actuator/health` | Público | Salud del servicio y PostgreSQL |

Los reintentos con el mismo `paymentId` y los mismos datos devuelven exactamente
el boleto emitido; datos diferentes o un asiento ya emitido producen conflicto.
La base rechaza la modificación o eliminación de los boletos.

El Compose local habilita la emisión sandbox. Para despliegues reales establecer
`TICKET_DEMO_ENABLED=false`, configurar TLS y compartir de forma segura el secreto
HMAC con el Módulo 3. La aplicación por sí sola tiene sandbox deshabilitado.

Para conectar el módulo a una red Docker compartida ya creada por el orquestador:

```powershell
docker compose -f docker-compose.yml -f docker-compose.integration.yml up --build -d --wait
```

La red externa predeterminada es `sistema-boletos` (configurable mediante
`TICKET_ENGINE_NETWORK`). Otros módulos consumen HTTP en
`http://ticket-engine-api:8080` o `http://ticket-engine:8080`; la base permanece
en su red privada. El override no crea ni administra la red del sistema global.

```powershell
docker compose down
```

Este comando conserva datos, credenciales y llaves. No eliminar sus volúmenes si
se deben conservar boletos o firmas verificables.

Documentación:

- [Operación, secretos, redes y pruebas](docs/container-development.md)
- [Especificación criptográfica](docs/crypto-specification.md)
- [Contrato backend](docs/backend-contract.md)
- [OpenAPI](docs/openapi.yaml)
- [Guía del panel y exportación HTML](docs/frontend-guide.md)
- [Arquitectura y responsabilidades](docs/architecture.md)
- [Evidencia de las pruebas backend](docs/backend-test-evidence.md)
- [Evidencia de escritorio y móvil](docs/frontend-test-evidence.md)
- [Verificación de persistencia tras recreación](docs/release-verification.md)

## Entrega verificada

El módulo cuenta con 42 pruebas backend, 10 pruebas del panel y dos recorridos
completos en navegador (escritorio y móvil) aprobados, ejecutados exclusivamente
en Docker. Se verificó que recrear PostgreSQL, el emisor y el panel conserva la
llave, el JWS y los datos originales, sin duplicar boletos al reintentar el pago.
Las revisiones independientes de backend, frontend e infraestructura fueron
aprobadas. Evidencia de la entrega del 4 de noviembre de 2026 en los documentos
enlazados arriba.
