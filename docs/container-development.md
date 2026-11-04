# Desarrollo y operación en Docker

Responsables: Christopher Dominguez (QA/DevOps) y Saul Asaph (arquitectura).

## Entorno

El host requiere Docker Desktop con contenedores Linux y Compose. Java 21,
Kotlin 2.1.21, Gradle 8.14.3 y Spring Boot 3.5.16 se compilan y ejecutan dentro
de Docker. No instalar compiladores ni gestores de paquetes en el host.

```powershell
docker compose --profile tools run --build --rm secrets-init
docker compose config --quiet
docker compose --profile tools run --rm tests
docker compose --profile tools run --build --rm frontend-tests
docker compose up --build -d --wait
docker compose exec -T app curl --fail --silent http://localhost:8080/actuator/health
```

La API queda en `http://localhost:8084`, puerto interno 8080. El puerto local se
puede cambiar con `TICKET_ENGINE_PORT`. El enlace se limita a `127.0.0.1`.

El panel React queda en `http://localhost:8085`, configurable con
`TICKET_ENGINE_WEB_PORT`. Su imagen se construye con Node dentro de Docker y sirve
los archivos estáticos mediante Nginx sin privilegios (UID 101). El filesystem es
de solo lectura, con `/tmp` temporal. No tiene secretos, llaves ni acceso a la red
de PostgreSQL. Las solicitudes API usan el proxy al backend en la misma red HTTP.

Abrir `.secrets/admin-token` localmente y pegar la clave en el formulario de acceso.
El panel conserva la clave únicamente en memoria hasta cerrar la sesión o recargar
la página. Nunca se integra una credencial de administración al bundle JavaScript.
La exportación descarga un HTML independiente del boleto, con QR del JWS firmado;
no exporta la credencial administrativa ni la llave privada. El QR estático de
demostración no reemplaza el QR dinámico que implementa el Módulo 5.

La configuración de desarrollo permite el emisor sandbox autenticado mediante
`TICKET_DEMO_ENABLED=true`. Para deshabilitarlo antes de un despliegue real:

```powershell
$env:TICKET_DEMO_ENABLED = 'false'
docker compose up -d --force-recreate app
```

El valor por defecto de Spring, fuera del Compose sandbox, es `false`. La ruta
de pagos reales siempre exige autenticación HMAC. El panel de demostración no
debe utilizarse como confirmación bancaria ni como sustituto del Módulo 3.

## Secretos y llaves

`secrets-init` construye un pequeño contenedor Alpine con OpenSSL. Genera secretos
aleatorios de 256 bits y dos parejas ECDSA P-256 independientes sin imprimir sus valores:

| Recurso | Ubicación | Consumidor |
|---|---|---|
| Contraseña bootstrap PostgreSQL | `.secrets/postgres-password` | `db`, `migrate`, base y migrador de pruebas |
| Contraseña del rol limitado | `.secrets/application-password` | migradores, `app`, `tests` |
| Clave de administración | `.secrets/admin-token` | `app`, `tests` |
| Secreto HMAC del Módulo 3 | `.secrets/payment-hmac-secret` | `app`, `tests`; configurar también en el emisor de pagos |
| Firma de la aplicación: privada PKCS#8 y pública SPKI | volumen Docker `ticket-engine_signing_keys` | inicializador y `app` |
| Firma de pruebas: pareja PKCS#8/SPKI independiente | volumen Docker `ticket-engine_test_signing_keys` | inicializador y `tests` |

Los secretos existentes no se sobrescriben, y un archivo vacío produce error.
La contraseña bootstrap anterior se conserva, incluso si el volumen PostgreSQL
ya contiene datos. Regenerar un archivo de contraseña no cambia por sí mismo las
credenciales de un PostgreSQL ya inicializado.

La llave privada se guarda con propietario UID/GID 10001 y modo `0400`. El volumen
se monta como solo lectura en la aplicación; no entra al host, Git ni la imagen.
La pública usa modo `0444`. Una llave privada presente nunca se reemplaza al
reiniciar. Si solo falta la pública, se deriva de la privada existente; una pública
sin privada o una pareja inconsistente impiden continuar.

Las pruebas nunca montan `signing_keys`: sus fixtures se firman con
`test_signing_keys`, que la aplicación no acepta. Esto evita que un boleto de
prueba pueda verificarse offline con la llave pública del emisor de desarrollo.

La clave administrativa se obtiene abriendo el archivo local `.secrets/admin-token`
en un editor del propietario del proyecto. Introducirla en el panel; no enviarla
por correo, registrarla en logs ni agregarla a comandos guardados en el historial.
El inicializador no inserta credenciales fijas de demostración.

Compose local monta los secretos desde archivos. Los usuarios autorizados del host
y quienes controlan Docker pueden leerlos. En Linux estos archivos tienen permiso
de lectura necesario para consumidores con distintos UID; restringir el directorio
`.secrets` con ACL del host. En producción usar el gestor de secretos de la plataforma
y TLS. Respaldar de forma cifrada `postgres_data`, las credenciales y `signing_keys`.
La rotación de firma requiere conservar las llaves públicas históricas y un key ring;
no reemplazar la pareja mientras existan boletos que dependan de ella.

## Migraciones y persistencia inmutable

`migrate` es un contenedor de una sola ejecución. Se conecta como bootstrap y
crea `ticket_engine_app` sin privilegios de superusuario, creación de bases o roles,
replicación ni bypass de RLS. Solo se monta el secreto bootstrap en los contenedores
administrativos; `app` recibe únicamente la credencial del rol limitado.

El runner `/migration/migrate.sh` lee `DB/migrations/*.sql` en orden lexicográfico,
mantiene la tabla `schema_migrations` y calcula SHA-256 de cada archivo. El chequeo
de versión/checksum y su aplicación se ejecutan en la misma transacción protegida
por un lock advisory. Una migración ya aplicada con otro checksum detiene el arranque:
los cambios posteriores deben agregarse en un nuevo archivo versionado.

El servicio arranca después de que la migración termine correctamente. `tickets`
solo concede `SELECT` e `INSERT` a la aplicación. No hay permisos de UPDATE,
DELETE, TRUNCATE, TRIGGER ni creación en `public`. Los triggers además rechazan
UPDATE/DELETE/TRUNCATE en operaciones SQL normales del propietario. Un administrador
de PostgreSQL siempre puede cambiar el esquema: la protección no pretende resistir
a un administrador comprometido.

Las restricciones únicas sobre `payment_id` y `(event_id, seat_id)` resuelven
reintentos y carreras entre instancias. El servicio conserva el JWS original en
lugar de firmar de nuevo en un replay. No hay tablas compartidas con otros módulos.
El SQLite preexistente `DB/ticket-engine.sqlite` se conserva y no participa en el
servicio ni en la imagen.

## Pruebas aisladas

```powershell
docker compose --profile tools run --rm tests
```

El servicio `tests` ejecuta `gradle clean build`. El código se monta de solo lectura,
y compilación/cachés permanecen en volúmenes Docker. Los reportes JUnit están en
`ticket-engine_test_build`, ruta `build/test-results/test`. El directorio `build`
es subdirectorio del volumen para permitir `clean` y Kotlin escribe temporales en
`/tmp`, sin crear archivos de tooling en el host.

`test-db` usa una red privada distinta y almacenamiento tmpfs, base
`ticket_engine_test`. `test-migrate` aplica el mismo SQL y los tests conectan como
`ticket_engine_app`. No consultan ni modifican la base de desarrollo `ticket_engine`.
El secreto bootstrap se monta exclusivamente en `tests` como
`/run/secrets/test.bootstrap.password` para verificar también los triggers desde
una conexión administrativa de prueba.

La base temporal permanece mientras `test-db` siga ejecutándose; al retirar ese
contenedor sus datos desaparecen. Las pruebas usan identificadores aleatorios y
no desactivan restricciones ni borran boletos. Para detener solo el entorno de prueba:

```powershell
docker compose --profile tools stop test-db
docker compose --profile tools rm -f test-db test-migrate
```

El Dockerfile genera el JAR con `bootJar`; construir una imagen no ejecuta por sí
solo la suite de integración. Correr `tests` antes de aceptar cambios.

## Pruebas y compilación del panel

```powershell
docker compose --profile tools run --build --rm frontend-tests
docker compose build web
docker compose up -d --wait web
docker compose exec -T web wget --quiet --spider http://127.0.0.1:8080/health
```

`frontend-tests` construye el target `test` del Dockerfile del panel y ejecuta
`npm run test` dentro de Node. No usa credenciales, base de datos ni red durante
la ejecución de las pruebas. El target `runtime` compila React y sirve únicamente
los assets de producción; el host no necesita Node ni npm. La instalación de
dependencias se reproduce con el lock de npm dentro del build Docker.

Los screenshots y boletos HTML de las pruebas de navegador se guardan en
`artifacts/qa`, excluido de Git, para su inspección visual. No deben contener claves
administrativas ni material privado. Los resultados se conservan fuera de los
contenedores para que el equipo pueda revisarlos sin instalar un navegador de QA.

Con backend y panel saludables, ejecutar:

```powershell
docker compose --profile tools run --build --rm browser-tests
```

El runner utiliza Playwright con Chromium dentro de su propia imagen Docker,
comparte únicamente el espacio de red del servicio `web` y accede a
`http://localhost:8080`. Así WebCrypto utiliza el contexto seguro de loopback de
Chromium, sin excepciones para orígenes HTTP inseguros. El runner recibe solo el secreto administrativo
necesario para probar el panel. No monta llaves de firma, secretos HMAC ni datos de
PostgreSQL. Las capturas se toman después del acceso; se desactivan trazas, videos
y capturas automáticas de errores para evitar registrar credenciales del formulario.

## Integración y redes

`db` pertenece únicamente a `database`, red interna sin puerto publicado. Su volumen
`postgres_data` pertenece exclusivamente a Ticket Engine. `app` pertenece también
a `integration`. Ningún módulo externo debe conectarse al PostgreSQL del emisor.

En un Compose global, conservar la red privada del módulo y dar a la API un alias
único. La configuración base ya asigna `ticket-engine-api` y `ticket-engine`,
consumidos por HTTP en `http://ticket-engine-api:8080` o `http://ticket-engine:8080`.
El proxy del panel usa `ticket-engine-api`, evitando colisiones con otros servicios
llamados `app` en una red compartida.
Si otros servicios se llaman `db`, asignar al PostgreSQL un alias único como
`ticket-engine-db` y configurar `SPRING_DATASOURCE_URL` acorde. No compartir sus
credenciales, volúmenes ni tablas.

Para proyectos Compose separados, el orquestador crea la red compartida. Este
módulo incluye `docker-compose.integration.yml`, que declara la red como externa:

```yaml
networks:
  integration:
    external: true
    name: sistema-boletos
```

```powershell
docker compose -f docker-compose.yml -f docker-compose.integration.yml config --quiet
docker compose -f docker-compose.yml -f docker-compose.integration.yml up --build -d --wait
```

La red debe existir antes de ejecutar `up`; este módulo no la crea ni administra
los contenedores de otros módulos. El nombre se puede cambiar mediante
`TICKET_ENGINE_NETWORK`. Los alias HTTP únicos de la configuración base se
conservan al aplicar el override. Usar los mismos dos archivos al detener el
despliegue integrado para que Compose conserve la red externa.

`docker compose down` conserva los datos y las llaves. No ejecutar `down -v` si es
necesario conservar boletos o capacidad de verificar las firmas existentes.

## Verificación de infraestructura realizada

- `docker compose config --quiet`: válido.
- `docker compose --profile tools run --build --rm secrets-init`: secretos y llaves
  disponibles, sin mostrar sus valores.
- `test-migrate`: migración `001-tickets` aplicada en PostgreSQL de pruebas.
- Segunda ejecución `test-migrate`: migración reconocida, checksum validado, sin
  reaplicar ni modificar registros existentes.
- Lectura de metadatos desde UID 10001: privada `400 10001 10001`, pública
  `444 10001 10001`; privada legible y sin permiso de escritura.
- Las verificaciones funcionales completas del backend se registran con su entrega
  y QA integral. No se ejecutaron lenguajes ni instalaciones fuera de Docker.
