# Firma digital de boletos — ES256

Responsable: Jonathan Samuel. QA: Christopher Dominguez.

## Algoritmo y formato interoperable

`TicketCryptoService` usa `java.security`, curva **secp256r1 (P-256)** y
`SHA256withECDSA`. Se valida el conjunto completo de parámetros EC, el escalar
privado, el punto público y que los dos archivos formen una pareja. Se crea un
objeto `Signature` por operación para permitir uso concurrente.

El token tiene formato JWS compacto / JWT con encabezado protegido exactamente:

```json
{"alg":"ES256","kid":"<huella JWK SHA-256>","typ":"JWT"}
```

Se firma `ASCII(base64url(header) + "." + base64url(payload))` con
`SHA256withECDSA`, que calcula SHA-256 internamente **una sola vez**. El proveedor
JCA devuelve DER; el servicio convierte estrictamente a la firma JOSE de 64 bytes
`R || S` (32 bytes por entero). Verificar realiza la conversión inversa y rechaza
enteros cero, negativos, fuera del orden y representaciones no válidas.

`payloadHash` es `hex(SHA-256(UTF8(payloadJson)))`, útil para la auditoría del
contenido inmutable. No se pasa este digest a `SHA256withECDSA`: produciría doble
hash y rompería ES256. La firma JWS protege tanto el encabezado como el payload.

Referencias: [RFC 7515, firma JWS](https://www.rfc-editor.org/rfc/rfc7515.html),
[RFC 7518, ES256](https://www.rfc-editor.org/rfc/rfc7518.html),
[algoritmos Java 21](https://docs.oracle.com/en/java/javase/21/docs/specs/security/standard-names.html).

## Serialización canónica del perfil Ticket Engine v1

`canonicalJson(Map<String,Object>)` y `sign(...)` aplican el mismo perfil:

- Objetos con llaves de texto ordenadas lexicográficamente por unidades UTF-16.
- Texto Unicode válido, sin normalización que pudiera cambiar identificadores.
- Enteros de 64 bits, booleanos, listas ordenadas y objetos anidados.
- No se permiten `null`, flotantes, decimales, enteros arbitrariamente grandes
  ni objetos serializados por reflexión. Montos monetarios deben usar unidades
  mínimas enteras definidas por el contrato del módulo.
- JSON UTF-8 compacto sin espacios; se usa el escape JSON estándar de Jackson.
- Payload máximo: 16 KiB. Token máximo: 24 000 caracteres. Anidación máxima: 12.

Este es un perfil restringido propio, no una implementación general de RFC 8785.
El verificador exige el mismo payload canónico; no reserializa el token antes de
verificar su firma. Un consumidor externo debe verificar los bytes exactos del
signing input mediante una implementación ES256 estándar.

## Verificación y validación de negocio

`verify(token)` comprueba firma, algoritmo, `kid`, `typ`, estructura compacta,
base64url sin relleno y con codificación canónica, JSON sin campos duplicados,
ausencia de JSON sobrante, límites y serialización del payload. Se rechazan
encabezados adicionales como `jku`, `x5u`, `crit` o `b64`.

El servicio criptográfico **no decide la vigencia ni el contexto de uso**.
La capa de aplicación debe exigir y validar `iss`, `aud`, `sub`, `jti`, `iat`,
`nbf`, `exp`, identificadores y estado de pago, usando su `Clock` inyectado.
Comprobar solo la firma no autoriza el acceso a un evento.

## Llaves y recuperación

Producción carga una llave privada **PKCS#8 PEM** (`BEGIN PRIVATE KEY`) y una
llave pública **SubjectPublicKeyInfo PEM** (`BEGIN PUBLIC KEY`) mediante
`load(Path, Path)`. No genera otra llave si faltan archivos ni los reemplaza
durante un reinicio. Deben montarse como secretos fuera de la imagen y Git.
Se rechazan curvas distintas, llaves mezcladas y formatos incompatibles.

No hay getters ni exportación de la llave privada. La API JWK contiene solo
`kty`, `crv`, `alg`, `use`, `kid`, `x`, `y`, `key_ops`; nunca contiene `d`.
El `kid` es el thumbprint SHA-256 base64url del objeto público `{crv,kty,x,y}`
conforme a [RFC 7638](https://www.rfc-editor.org/rfc/rfc7638.html).

Se deben respaldar los secretos y mantener las llaves públicas históricas antes
de rotar una llave con boletos vigentes. Esta clase gestiona una sola pareja;
la rotación requiere un key ring explícito, sin invalidar boletos emitidos.
Las pruebas generan parejas efímeras únicamente dentro de sus contenedores.

## API Java para la capa Kotlin

```java
new TicketCryptoService(PrivateKey privateKey, PublicKey publicKey);
TicketCryptoService.load(Path privatePkcs8Pem, Path publicSpkiPem);
String keyId();
Map<String, Object> publicJwk();
String sha256Hex(byte[] payload);
String canonicalJson(Map<String, Object> claims);
SignedTicket sign(Map<String, Object> claims);
Map<String, Object> verify(String token);
// SignedTicket record: token(), payloadJson(), payloadHash()
```

`verify` devuelve `IllegalArgumentException` con mensaje genérico si el token es
inválido. La capa HTTP debe traducirlo a su error de validación sin exponer llaves
ni token en logs. Los errores de configuración de llave deben impedir el arranque.

## Pruebas en Docker

```powershell
docker compose --profile tools run --rm tests gradle --no-daemon --project-cache-dir /tmp/gradle-project test --tests '*crypto*'
```

Se cubren vector SHA-256 `abc`, canonicalización, payload no soportado, formato
ES256 y verificación independiente con JCA `SHA256withECDSAinP1363Format`, firmas
y payload alterados, algoritmo no permitido, otra llave, curva incorrecta, JWK
público, restauración PEM, JSON/base64 malformados y uso concurrente.

Resultado realizado: **13 pruebas aprobadas**, `BUILD SUCCESSFUL` dentro del
contenedor Gradle. No se instalaron ni ejecutaron herramientas de lenguaje en
el host. `git diff --check` también pasó.
