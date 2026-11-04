# Verificación HTTP del backend

Responsable QA: Christopher Dominguez. Contratos y revisión: Saul Asaph y Ricardo
Carmona. Pruebas de criptografía: Jonathan Samuel. La implementación de servicios
y persistencia corresponde a Sunem Gizeht.

## Alcance

`EndToEndEmissionTest` arranca Spring Boot en un puerto aleatorio y utiliza
`TestRestTemplate` para solicitudes HTTP reales. Usa PostgreSQL Compose de pruebas,
las credenciales montadas como secretos y la llave ECDSA de pruebas. No simula la
firma, la base ni el webhook y no instala dependencias en el host.

Se cubren:

- Emisión por pago autenticado mediante HMAC sobre timestamp y bytes exactos.
- Verificación ES256 independiente con JCA P1363 y la llave JWK publicada.
- Hash SHA-256 del payload decodificado y consulta del mismo token persistido.
- Reintento idempotente entre los endpoints de emisión y webhook.
- Conflictos por cambiar un pago existente o duplicar un asiento del mismo evento.
- Cuatro solicitudes concurrentes del mismo pago, con una sola creación.
- Rechazo de HMAC ausente, incorrecto, vencido, futuro o con cuerpo alterado.
- JSON malformado, pago no confirmado, identificadores y fechas inválidos.
- JSON autenticado con campos duplicados o desconocidos, JSON sobrante, valores
  nulos y coerciones de tipos ambiguas; todos se rechazan con 400.
- Cuerpo de 40 KiB rechazado con 413 tanto con `Content-Length` como con envío
  HTTP/1.1 chunked de longitud desconocida, con timeout de diez segundos.
- Autenticación administrativa, paginación y consulta de identificador inexistente.
- Regresión de rutas con parámetros matrix y separadores codificados: no permiten
  listar ni emitir boletos omitiendo la autenticación.
- Emisión de demostración protegida por credencial y bandera de configuración.
- `DemoDisabledEndToEndTest`: incluso una credencial administrativa válida recibe
  403 con el sandbox deshabilitado, sin aumentar la cantidad de boletos.
- Rechazo de tokens malformados y payloads alterados por el verificador público.
- Firmas auténticas con expiración, emisor, audiencia, fecha de emisión,
  identificador o estado inválidos. Solo estos fixtures adversarios usan el bean
  criptográfico para firmar; su validación se comprueba por HTTP.

Las pruebas generan identificadores aleatorios y conservan los registros de su
base exclusiva; no borran ni actualizan boletos. Nunca imprimen credenciales,
encabezados de autenticación ni tokens.

## Comando reproducible

Ejecutar exclusivamente con Docker desde la raíz del repositorio, después de
generar los secretos siguiendo la guía de contenedores:

```powershell
docker compose --profile tools run --rm tests gradle --no-daemon --project-cache-dir /tmp/gradle-project test --tests '*EndToEndEmissionTest'
docker compose --profile tools run --rm tests gradle --no-daemon --project-cache-dir /tmp/gradle-project test --tests '*DemoDisabledEndToEndTest'
```

## Resultado

Ejecución integrada coordinada por el agente principal el **3 de noviembre de
2026**, exclusivamente dentro de Docker:

```powershell
docker compose --profile tools run --rm tests
```

Resultado final: **BUILD SUCCESSFUL en 34 segundos, 42 pruebas aprobadas**:

| Conjunto | Pruebas aprobadas |
|---|---:|
| Base de contenedores e interoperabilidad | 3 |
| `EndToEndEmissionTest` | 14 |
| `DemoDisabledEndToEndTest` | 1 |
| Servicio de emisión y validación | 9 |
| Núcleo criptográfico | 13 |
| Restricciones e inmutabilidad PostgreSQL | 2 |

Los casos HTTP de concurrencia, rutas matrix, firmas auténticas con claims
inválidos, JSON estricto, límites de cuerpo y sandbox deshabilitado pasaron en
esta ejecución, posterior a la separación de llaves de pruebas y aplicación.
El agente principal también construyó la imagen backend y verificó el despliegue
Docker: migración principal con código de salida 0 y servicios `app` y `db`
saludables.

La ejecución, la compilación y sus resultados fueron comunicados por el agente
principal que coordinó la verificación; el autor de este documento no repitió
la suite. Este documento no sustituye los XML JUnit del volumen Docker de
resultados.

Los resultados verifican el backend aislado y su PostgreSQL de pruebas. No incluyen
la integración real con los microservicios 1, 2, 3, 5 o 6 ni la interfaz React.
