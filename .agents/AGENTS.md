# AGENTS.md — Mapa operativo de Ticket Engine

Este archivo define el contexto mínimo para trabajar en el Módulo 4: Motor de Emisión y Firma Digital de Boletos, construido con Java, Kotlin y Spring Boot.

## 1. Arranque obligatorio

1. Lee `.agents/feature_list.json` y selecciona una sola feature `pending` o la indicada por el usuario.
2. Antes de implementar, cambia el estado de la feature a `in_progress`.
3. Lee las instrucciones del agente correspondiente en `.agents/agents/`.
4. Revisa `git status --short` para no sobrescribir cambios locales.
5. Confirma el componente afectado: `crypto`, `application`, `domain`, `persistence`, `api`, `webhook` o `frontend`.

## 2. Mapa del repositorio

| Ruta | Responsabilidad |
|---|---|
| `src/main/java/com/ticketengine/crypto/` | Hash SHA-256, firma ECDSA y llave pública |
| `src/main/java/com/ticketengine/api/` | Controladores REST y verificación |
| `src/main/java/com/ticketengine/webhook/` | Recepción de pagos confirmados |
| `src/main/java/com/ticketengine/config/` | Configuración de Spring y seguridad |
| `src/main/kotlin/com/ticketengine/application/` | Orquestación de emisión |
| `src/main/kotlin/com/ticketengine/domain/` | Modelo y estados del boleto |
| `src/main/kotlin/com/ticketengine/dto/` | Objetos de entrada y respuesta |
| `src/main/kotlin/com/ticketengine/persistence/` | Entidades y repositorios |
| `src/test/` | Pruebas unitarias y de integración |
| `frontend/` | Panel React de demostración |
| `docs/` | Contratos API, arquitectura y especificaciones |

## 3. Reglas obligatorias

- Trabaja en una sola feature a la vez y respeta sus `allowed_paths`.
- No mezcles controladores, lógica de negocio, criptografía y persistencia en una misma clase.
- Usa ECDSA con curva `secp256r1` y `SHA256withECDSA`.
- Define una serialización canónica y estable antes de firmar el payload.
- La llave privada del evento nunca debe salir del servicio emisor, registrarse en logs ni guardarse en el repositorio.
- La llave pública puede exponerse para verificación.
- La emisión debe ser idempotente por `paymentId` para evitar boletos duplicados.
- Un boleto emitido no debe modificarse; cualquier cambio invalida su firma.
- Valida explícitamente firmas, estados, fechas e identificadores.
- No dejes secretos, archivos `.env`, logs temporales ni `System.out.println`.
- Toda feature completada debe tener pruebas y verificación documentada.
- Usa el estándar de commits definido en `.agents/rules/commit-standard.md`.

## 4. Flujo de emisión

```text
Pago confirmado → Validación → Payload canónico → SHA-256 → Firma ECDSA
→ JWT/JWS → Persistencia → Respuesta al consumidor
```

## 5. Comandos de verificación

Toda compilación, prueba y ejecución de Java, Kotlin, JavaScript y herramientas
de lenguaje debe hacerse exclusivamente dentro de Docker. No instales JDK,
Gradle, Kotlin, Node ni dependencias en el host. Usa el wrapper dentro del
contenedor cuando exista; de lo contrario, usa el Gradle fijado en Compose.

```bash
docker compose --profile tools run --rm tests
docker compose up --build -d --wait
```

Si el proyecto aún no tiene configuración de compilación, documenta la limitación y valida mediante las pruebas disponibles.

## 6. Estado de features

- `pending`: disponible para iniciar.
- `in_progress`: trabajo actualmente en desarrollo.
- `done`: entregables terminados y verificados.
- `blocked`: existe un impedimento real documentado.

Nunca dejes una feature en `pending` después de comenzar a trabajar en ella.
