package com.ticketengine.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.core.type.TypeReference
import com.ticketengine.crypto.TicketCryptoService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.TestPropertySource
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import java.time.Duration
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real HTTP, real PostgreSQL, and an independent JCA verifier. No signing mocks. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = ["ticket.demo-enabled=true"])
class EndToEndEmissionTest @Autowired constructor(
    private val http: TestRestTemplate,
    private val json: ObjectMapper,
    private val crypto: TicketCryptoService
) {
    private val adminToken: String by lazy {
        Files.readString(Path.of("/run/secrets/ticket.admin-token")).trim()
    }
    private val webhookSecret: ByteArray by lazy {
        Files.readString(Path.of("/run/secrets/ticket.webhook-secret")).trim().toByteArray(StandardCharsets.UTF_8)
    }

    @Test
    fun `pago confirmado emite y persiste un JWS ES256 verificable con la llave publica`() {
        val payment = payment()
        val issued = signedPost("/webhooks/payments/confirmed", payment)
        assertEquals(HttpStatus.CREATED, issued.statusCode)
        val response = body(issued)
        assertFalse(response.path("replayed").asBoolean())
        val ticket = response.path("ticket")
        assertTrue(ticket.path("ticketId").asText().isNotBlank())

        val token = ticket.path("token").asText()
        val parts = token.split('.')
        assertEquals(3, parts.size)
        val decoder = Base64.getUrlDecoder()
        val header = json.readTree(decoder.decode(parts[0]))
        assertEquals("ES256", header.path("alg").asText())
        assertEquals("JWT", header.path("typ").asText())
        val jwkResponse = http.getForEntity("/public-key", String::class.java)
        assertEquals(HttpStatus.OK, jwkResponse.statusCode)
        val jwk = body(jwkResponse)
        assertEquals("P-256", jwk.path("crv").asText())
        assertEquals("EC", jwk.path("kty").asText())
        assertEquals(header.path("kid").asText(), jwk.path("kid").asText())
        assertFalse(jwk.has("d"))
        val parameters = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(
            BigInteger(1, decoder.decode(jwk.path("x").asText())),
            BigInteger(1, decoder.decode(jwk.path("y").asText()))
        )
        val publicKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, parameters))
        val verifier = Signature.getInstance("SHA256withECDSAinP1363Format")
        verifier.initVerify(publicKey)
        verifier.update("${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.US_ASCII))
        assertTrue(verifier.verify(decoder.decode(parts[2])), "Un consumidor independiente debe verificar ES256")

        val payload = decoder.decode(parts[1])
        assertTrue(String(payload, StandardCharsets.UTF_8) == ticket.path("payloadJson").asText())
        assertEquals(
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)),
            ticket.path("payloadHash").asText()
        )
        val payloadJson = json.readTree(payload)
        assertEquals(payment["eventId"], payloadJson.path("eventId").asText())
        assertEquals(payment["seatId"], payloadJson.path("seatId").asText())

        val persisted = adminGet("/api/tickets/${ticket.path("ticketId").asText()}")
        assertEquals(HttpStatus.OK, persisted.statusCode)
        assertTrue(body(persisted).path("token").asText() == token, "La consulta conserva el JWS emitido")
        val verification = post("/verify", json.writeValueAsBytes(mapOf("token" to token)))
        assertEquals(HttpStatus.OK, verification.statusCode)
        assertTrue(body(verification).path("valid").asBoolean())

        val keysResponse = http.getForEntity("/.well-known/jwks.json", String::class.java)
        assertEquals(HttpStatus.OK, keysResponse.statusCode)
        assertTrue(body(keysResponse).path("keys").any { it.path("kid").asText() == jwk.path("kid").asText() })
    }

    @Test
    fun `reintentar el mismo pago devuelve el mismo boleto y un cambio causa conflicto`() {
        val payment = payment()
        val first = signedPost("/webhooks/payments/confirmed", payment)
        val retry = signedPost("/api/tickets", payment)
        assertEquals(HttpStatus.CREATED, first.statusCode)
        assertEquals(HttpStatus.OK, retry.statusCode)
        val original = body(first).path("ticket")
        val replay = body(retry)
        assertTrue(replay.path("replayed").asBoolean())
        assertEquals(original.path("ticketId"), replay.path("ticket").path("ticketId"))
        assertTrue(original.path("token").asText() == replay.path("ticket").path("token").asText())
        assertEquals(
            HttpStatus.CONFLICT,
            signedPost("/api/tickets", payment + ("buyerId" to UUID.randomUUID().toString())).statusCode
        )
    }

    @Test
    fun `dos pagos no emiten dos boletos para el mismo asiento del evento`() {
        val payment = payment()
        assertEquals(HttpStatus.CREATED, signedPost("/api/tickets", payment).statusCode)
        val anotherPayment = payment + ("paymentId" to UUID.randomUUID().toString())
        assertEquals(HttpStatus.CONFLICT, signedPost("/api/tickets", anotherPayment).statusCode)
        val anotherEvent = anotherPayment + ("eventId" to UUID.randomUUID().toString())
        assertEquals(HttpStatus.CREATED, signedPost("/api/tickets", anotherEvent).statusCode)
    }

    @Test
    fun `solicitudes concurrentes del mismo pago solo crean un boleto`() {
        val payment = payment()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = pool.invokeAll((1..4).map {
                Callable { signedPost("/webhooks/payments/confirmed", payment) }
            })
            val responses = futures.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, responses.count { it.statusCode == HttpStatus.CREATED })
            assertEquals(3, responses.count { it.statusCode == HttpStatus.OK })
            val tickets = responses.map { body(it).path("ticket") }
            assertEquals(1, tickets.map { it.path("ticketId").asText() }.toSet().size)
            assertEquals(1, tickets.map { it.path("token").asText() }.toSet().size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `webhook exige autenticacion HMAC correcta y reciente sobre los bytes exactos`() {
        val bytes = json.writeValueAsBytes(payment())
        assertEquals(HttpStatus.UNAUTHORIZED, post("/webhooks/payments/confirmed", bytes).statusCode)
        assertEquals(HttpStatus.UNAUTHORIZED, post("/api/tickets", bytes).statusCode)
        val timestamp = Instant.now().epochSecond.toString()
        val invalid = paymentHeaders(timestamp, bytes).apply { set("X-Payment-Signature", "0".repeat(64)) }
        assertEquals(HttpStatus.UNAUTHORIZED, post("/webhooks/payments/confirmed", bytes, invalid).statusCode)
        val staleTimestamp = Instant.now().minusSeconds(601).epochSecond.toString()
        assertEquals(HttpStatus.UNAUTHORIZED, post("/webhooks/payments/confirmed", bytes, paymentHeaders(staleTimestamp, bytes)).statusCode)
        val futureTimestamp = Instant.now().plusSeconds(601).epochSecond.toString()
        assertEquals(HttpStatus.UNAUTHORIZED, post("/webhooks/payments/confirmed", bytes, paymentHeaders(futureTimestamp, bytes)).statusCode)
        val changedBytes = bytes + " ".toByteArray(StandardCharsets.UTF_8)
        assertEquals(HttpStatus.UNAUTHORIZED, post("/webhooks/payments/confirmed", changedBytes, paymentHeaders(timestamp, bytes)).statusCode)
    }

    @Test
    fun `JSON malformado con HMAC autentico devuelve error de entrada`() {
        val bytes = "{\"paymentId\":".toByteArray(StandardCharsets.UTF_8)
        val timestamp = Instant.now().epochSecond.toString()
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/tickets", bytes, paymentHeaders(timestamp, bytes)).statusCode)
    }

    @Test
    fun `limite de cuerpo rechaza contenido excesivo con longitud conocida y chunked`() {
        val bytes = json.writeValueAsBytes(mapOf("token" to "a".repeat(40 * 1024)))
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, post("/verify", bytes).statusCode)

        val publisher = HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(bytes) }
        assertEquals(-1L, publisher.contentLength(), "El publisher debe enviar longitud desconocida")
        val request = HttpRequest.newBuilder(URI.create(http.rootUri + "/verify"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .POST(publisher)
            .build()
        val client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build()
        try {
            val response = client.send(request, HttpResponse.BodyHandlers.discarding())
            assertEquals(HttpStatus.PAYLOAD_TOO_LARGE.value(), response.statusCode())
        } finally {
            client.close()
        }
    }

    @Test
    fun `JSON autenticado rechaza campos duplicados desconocidos sobrantes y null`() {
        val original = payment()
        val base = json.writeValueAsString(original)
        val invalidBodies = listOf(
            (base.dropLast(1) + ",\"paymentId\":\"${UUID.randomUUID()}\"}").toByteArray(StandardCharsets.UTF_8),
            (base.dropLast(1) + ",\"unexpected\":true}").toByteArray(StandardCharsets.UTF_8),
            (base + " {}").toByteArray(StandardCharsets.UTF_8),
            json.writeValueAsBytes(original + ("amountMinor" to null))
        )
        invalidBodies.forEachIndexed { index, bytes ->
            val timestamp = Instant.now().epochSecond.toString()
            val result = post("/api/tickets", bytes, paymentHeaders(timestamp, bytes))
            assertEquals(HttpStatus.BAD_REQUEST, result.statusCode, "JSON ambiguo autenticado $index")
        }
    }

    @Test
    fun `rechaza pago no confirmado identificadores invalidos y fechas incoherentes`() {
        val base = payment()
        val invalid = listOf(
            base + ("paymentStatus" to "PENDING"),
            base + ("paymentId" to "no-es-uuid"),
            base + ("eventId" to "no-es-uuid"),
            base + ("buyerId" to "no-es-uuid"),
            base + ("seatId" to ""),
            base + ("eventStartsAt" to "ayer"),
            base + ("eventEndsAt" to Instant.now().minusSeconds(3600).toString()),
            base + ("amountMinor" to -1L),
            base + ("currency" to "PESOS"),
            base + ("amountMinor" to 1.5),
            base + ("amountMinor" to "123"),
            base + ("eventName" to 123),
            base + ("paymentStatus" to true),
            base + ("eventStartsAt" to Instant.now().epochSecond)
        )
        invalid.forEachIndexed { index, request ->
            assertEquals(HttpStatus.BAD_REQUEST, signedPost("/api/tickets", request).statusCode, "Caso de validación $index")
        }
    }

    @Test
    fun `consulta de boletos requiere credencial administrativa y pagina los resultados`() {
        assertEquals(HttpStatus.UNAUTHORIZED, http.getForEntity("/api/tickets", String::class.java).statusCode)
        assertEquals(HttpStatus.UNAUTHORIZED, http.getForEntity("/api/tickets/${UUID.randomUUID()}", String::class.java).statusCode)
        val wrong = HttpHeaders().apply { setBearerAuth("credencial-invalida-de-prueba") }
        assertEquals(HttpStatus.UNAUTHORIZED, http.exchange("/api/tickets", HttpMethod.GET, HttpEntity<Any>(wrong), String::class.java).statusCode)
        assertEquals(HttpStatus.CREATED, signedPost("/api/tickets", payment()).statusCode)
        val pageResponse = adminGet("/api/tickets?limit=1&offset=0")
        assertEquals(HttpStatus.OK, pageResponse.statusCode)
        val page = body(pageResponse)
        assertEquals(1, page.path("limit").asInt())
        assertEquals(0, page.path("offset").asInt())
        assertEquals(1, page.path("items").size())
        assertTrue(page.path("total").asLong() >= 1)
        listOf("limit=0", "limit=1001", "offset=-1").forEach { query ->
            assertEquals(HttpStatus.BAD_REQUEST, adminGet("/api/tickets?$query").statusCode)
        }
        assertEquals(HttpStatus.NOT_FOUND, adminGet("/api/tickets/${UUID.randomUUID()}").statusCode)
    }

    @Test
    fun `segmentos matrix y su codificacion no omiten la autenticacion`() {
        listOf("/api;foo/tickets", "/api%3Bfoo/tickets").forEach { path ->
            val result = http.exchange(
                URI.create(http.rootUri + path), HttpMethod.GET, HttpEntity.EMPTY, String::class.java
            )
            assertFalse(result.statusCode.is2xxSuccessful, "No se permite acceso anónimo a $path")
        }
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val result = http.exchange(
            URI.create(http.rootUri + "/api;foo/demo/tickets"), HttpMethod.POST,
            HttpEntity(json.writeValueAsBytes(payment()), headers), String::class.java
        )
        assertFalse(result.statusCode.is2xxSuccessful, "Una ruta matrix no autoriza la emisión demo")
    }

    @Test
    fun `demo requiere administrador aun estando habilitada`() {
        val bytes = json.writeValueAsBytes(payment())
        assertEquals(HttpStatus.UNAUTHORIZED, post("/api/demo/tickets", bytes).statusCode)
        assertEquals(HttpStatus.CREATED, post("/api/demo/tickets", bytes, adminHeaders()).statusCode)
    }

    @Test
    fun `verificacion publica rechaza un payload alterado y tokens malformados`() {
        val issued = signedPost("/api/tickets", payment())
        assertEquals(HttpStatus.CREATED, issued.statusCode)
        val parts = body(issued).path("ticket").path("token").asText().split('.')
        val changed = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"alterado\"}".toByteArray())
        val tokens = listOf("${parts[0]}.$changed.${parts[2]}", "invalido", "a.b.c")
        tokens.forEach { token ->
            val result = post("/verify", json.writeValueAsBytes(mapOf("token" to token)))
            assertEquals(HttpStatus.OK, result.statusCode)
            assertFalse(body(result).path("valid").asBoolean())
        }
    }

    @Test
    fun `firma autentica no autoriza claims vencidos ni de otro contexto`() {
        val issued = signedPost("/api/tickets", payment())
        assertEquals(HttpStatus.CREATED, issued.statusCode)
        val token = body(issued).path("ticket").path("token").asText()
        val claims: Map<String, Any> = json.readValue(
            Base64.getUrlDecoder().decode(token.split('.')[1]),
            object : TypeReference<Map<String, Any>>() {}
        )
        val invalid = listOf(
            claims + ("exp" to Instant.now().minusSeconds(1).epochSecond),
            claims + ("iss" to "otro-emisor"),
            claims + ("aud" to "otra-audiencia"),
            claims + ("iat" to Instant.now().plusSeconds(600).epochSecond),
            claims + ("jti" to "no-es-uuid"),
            claims + ("status" to "PENDING")
        )
        invalid.forEachIndexed { index, maliciousClaims ->
            val signed = crypto.sign(maliciousClaims)
            val result = post("/verify", json.writeValueAsBytes(mapOf("token" to signed.token())))
            assertEquals(HttpStatus.OK, result.statusCode)
            assertFalse(body(result).path("valid").asBoolean(), "Claims adversarios $index")
        }
    }

    private fun payment(): Map<String, Any> {
        val start = Instant.now().plusSeconds(86_400)
        return linkedMapOf(
            "paymentId" to UUID.randomUUID().toString(),
            "eventId" to UUID.randomUUID().toString(),
            "buyerId" to UUID.randomUUID().toString(),
            "seatId" to "A-${UUID.randomUUID()}",
            "eventName" to "Concierto de integración",
            "venue" to "Auditorio de pruebas",
            "eventStartsAt" to start.toString(),
            "eventEndsAt" to start.plusSeconds(7200).toString(),
            "amountMinor" to 125_000L,
            "currency" to "MXN",
            "paymentStatus" to "PAID"
        )
    }

    private fun signedPost(path: String, payment: Map<String, Any>): ResponseEntity<String> {
        val bytes = json.writeValueAsBytes(payment)
        val timestamp = Instant.now().epochSecond.toString()
        return post(path, bytes, paymentHeaders(timestamp, bytes))
    }

    private fun paymentHeaders(timestamp: String, bytes: ByteArray): HttpHeaders {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(webhookSecret, "HmacSHA256"))
        mac.update("$timestamp.".toByteArray(StandardCharsets.US_ASCII))
        val signature = HexFormat.of().formatHex(mac.doFinal(bytes))
        return HttpHeaders().apply {
            set("X-Payment-Timestamp", timestamp)
            set("X-Payment-Signature", signature)
        }
    }

    private fun adminHeaders() = HttpHeaders().apply { setBearerAuth(adminToken) }

    private fun adminGet(path: String): ResponseEntity<String> =
        http.exchange(path, HttpMethod.GET, HttpEntity<Any>(adminHeaders()), String::class.java)

    private fun post(path: String, bytes: ByteArray, headers: HttpHeaders = HttpHeaders()): ResponseEntity<String> {
        headers.contentType = MediaType.APPLICATION_JSON
        return http.exchange(path, HttpMethod.POST, HttpEntity(bytes, headers), String::class.java)
    }

    private fun body(response: ResponseEntity<String>): JsonNode = json.readTree(response.body)
}
