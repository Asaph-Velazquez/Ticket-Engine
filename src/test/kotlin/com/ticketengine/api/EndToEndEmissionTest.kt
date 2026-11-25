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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    private val adminToken: String by lazy { secret("ticket.admin-token") }
    private val walletToken: String by lazy { secret("ticket.wallet-token") }
    private val paymentSecret: ByteArray by lazy { secret("ticket.webhook-secret").toByteArray(StandardCharsets.UTF_8) }
    private val reservationSecret: ByteArray by lazy {
        secret("ticket.reservation-webhook-secret").toByteArray(StandardCharsets.UTF_8)
    }

    @Test
    fun `M2 y M3 juntos emiten y persisten un JWS ES256 verificable con la llave publica`() {
        val sale = sale()
        val pending = sendReservation(sale.reservation)
        assertEquals(HttpStatus.ACCEPTED, pending.statusCode)
        assertEquals("PENDING_PAYMENT", body(pending).path("status").asText())
        assertTrue(body(pending).path("ticket").isNull)

        val issued = sendPayment(sale.payment)
        assertEquals(HttpStatus.CREATED, issued.statusCode)
        val response = body(issued)
        assertEquals("ISSUED", response.path("status").asText())
        assertFalse(response.path("replayed").asBoolean())
        val ticket = response.path("ticket")
        assertTrue(ticket.path("ticketId").asText().isNotBlank())
        assertEquals(sale.paymentPayload["orderId"], ticket.path("orderId").asText())
        assertEquals(sale.reservation["reservationId"], ticket.path("reservationId").asText())

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
        assertTrue(independentlyVerifies(token, jwk), "Un consumidor independiente debe verificar ES256")

        val payload = decoder.decode(parts[1])
        assertTrue(String(payload, StandardCharsets.UTF_8) == ticket.path("payloadJson").asText())
        assertEquals(
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)),
            ticket.path("payloadHash").asText()
        )
        val claims = json.readTree(payload)
        assertEquals(2, claims.path("schemaVersion").asInt())
        assertEquals(sale.reservation["eventId"], claims.path("eventId").asText())
        assertEquals(sale.reservation["seatId"], claims.path("seatId").asText())
        assertEquals(sale.paymentPayload["paymentId"], claims.path("paymentId").asText())
        assertEquals(sale.paymentPayload["buyerId"], claims.path("sub").asText())
        assertEquals(sale.paymentPayload["orderId"], claims.path("orderId").asText())
        assertEquals(sale.reservation["reservationId"], claims.path("reservationId").asText())

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
    fun `el pago puede llegar antes que la reserva y los reintentos son idempotentes`() {
        val sale = sale()
        val first = sendPayment(sale.payment)
        assertEquals(HttpStatus.ACCEPTED, first.statusCode)
        assertEquals("PENDING_RESERVATION", body(first).path("status").asText())
        assertEquals(HttpStatus.ACCEPTED, sendPayment(sale.payment).statusCode, "Reintento de M3 sin reserva sigue pendiente")

        val issued = sendReservation(sale.reservation)
        assertEquals(HttpStatus.CREATED, issued.statusCode)
        val ticket = body(issued).path("ticket")

        val replayReservation = sendReservation(sale.reservation)
        val replayPayment = sendPayment(sale.payment)
        listOf(replayReservation, replayPayment).forEach { replay ->
            assertEquals(HttpStatus.OK, replay.statusCode)
            assertTrue(body(replay).path("replayed").asBoolean())
            assertEquals(ticket.path("ticketId"), body(replay).path("ticket").path("ticketId"))
            assertTrue(ticket.path("token").asText() == body(replay).path("ticket").path("token").asText())
        }
    }

    @Test
    fun `datos distintos para el mismo pago o reserva producen conflicto`() {
        val sale = sale()
        assertEquals(HttpStatus.ACCEPTED, sendReservation(sale.reservation).statusCode)
        assertEquals(HttpStatus.CREATED, sendPayment(sale.payment).statusCode)

        val changedPayment = sendPayment(sale.paymentWith("buyerId" to "usr_otro"))
        assertEquals(HttpStatus.CONFLICT, changedPayment.statusCode)
        assertEquals("PAYMENT_CONFLICT", body(changedPayment).path("code").asText())

        val changedReservation = sendReservation(sale.reservation + ("seatId" to "Z-99"))
        assertEquals(HttpStatus.CONFLICT, changedReservation.statusCode)
        assertEquals("RESERVATION_CONFLICT", body(changedReservation).path("code").asText())

        val secondPayment = sendPayment(sale.paymentWith("paymentId" to "pay_${id()}"))
        assertEquals(HttpStatus.CONFLICT, secondPayment.statusCode)
        assertEquals("RESERVATION_ALREADY_PAID", body(secondPayment).path("code").asText())
    }

    @Test
    fun `dos reservas pagadas no emiten dos boletos para el mismo asiento del evento`() {
        val first = sale()
        assertEquals(HttpStatus.CREATED, issue(first).statusCode)
        val sameSeat = sale(eventId = first.reservation["eventId"] as String, seatId = first.reservation["seatId"] as String)
        assertEquals(HttpStatus.ACCEPTED, sendReservation(sameSeat.reservation).statusCode)
        val conflict = sendPayment(sameSeat.payment)
        assertEquals(HttpStatus.CONFLICT, conflict.statusCode)
        assertEquals("SEAT_ALREADY_ISSUED", body(conflict).path("code").asText())
        val otherEvent = sale(seatId = first.reservation["seatId"] as String)
        assertEquals(HttpStatus.CREATED, issue(otherEvent).statusCode)
    }

    @Test
    fun `reintentos concurrentes del mismo pago solo crean un boleto`() {
        val sale = sale()
        assertEquals(HttpStatus.ACCEPTED, sendReservation(sale.reservation).statusCode)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val responses = pool.invokeAll((1..4).map { Callable { sendPayment(sale.payment) } })
                .map { it.get(30, TimeUnit.SECONDS) }
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
    fun `M2 y M3 simultaneos siempre terminan con un boleto`() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            repeat(10) {
                val sale = sale()
                val responses = pool.invokeAll(listOf(
                    Callable { sendReservation(sale.reservation) }, Callable { sendPayment(sale.payment) }
                )).map { it.get(30, TimeUnit.SECONDS) }
                assertEquals(1, responses.count { it.statusCode == HttpStatus.CREATED }, "Exactamente un mensaje emite")
                assertEquals(1, responses.count { it.statusCode == HttpStatus.ACCEPTED }, "El otro queda registrado como pendiente")
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `webhooks exigen HMAC correcto reciente y con el secreto de cada modulo`() {
        val bytes = json.writeValueAsBytes(sale().payment)
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

        val reservation = json.writeValueAsBytes(sale().reservation)
        val unsigned = post("/webhooks/reservations/confirmed", reservation)
        assertEquals(HttpStatus.UNAUTHORIZED, unsigned.statusCode)
        assertEquals("INVALID_RESERVATION_AUTH", body(unsigned).path("code").asText())
        // El secreto de M3 no sirve para hacerse pasar por M2 (ni con encabezados de M2).
        val withPaymentSecret = hmacHeaders("X-Reservation", paymentSecret, timestamp, reservation)
        assertEquals(HttpStatus.UNAUTHORIZED, post("/webhooks/reservations/confirmed", reservation, withPaymentSecret).statusCode)
        val withPaymentHeaders = paymentHeaders(timestamp, reservation)
        assertEquals(HttpStatus.UNAUTHORIZED, post("/webhooks/reservations/confirmed", reservation, withPaymentHeaders).statusCode)
    }

    @Test
    fun `JSON malformado con HMAC autentico devuelve error de entrada`() {
        val bytes = "{\"event\":".toByteArray(StandardCharsets.UTF_8)
        val timestamp = Instant.now().epochSecond.toString()
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/tickets", bytes, paymentHeaders(timestamp, bytes)).statusCode)
        val headers = hmacHeaders("X-Reservation", reservationSecret, timestamp, bytes)
        assertEquals(HttpStatus.BAD_REQUEST, post("/webhooks/reservations/confirmed", bytes, headers).statusCode)
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
        val sale = sale()
        val base = json.writeValueAsString(sale.payment)
        val invalidBodies = listOf(
            (base.dropLast(1) + ",\"event\":\"payment.confirmed\"}").toByteArray(StandardCharsets.UTF_8),
            (base.dropLast(1) + ",\"unexpected\":true}").toByteArray(StandardCharsets.UTF_8),
            (base + " {}").toByteArray(StandardCharsets.UTF_8),
            json.writeValueAsBytes(sale.paymentWith("amountMinor" to null)),
            json.writeValueAsBytes(sale.paymentWith("unexpected" to 1)),
            json.writeValueAsBytes(sale.payment + ("payload" to null))
        )
        invalidBodies.forEachIndexed { index, bytes ->
            val timestamp = Instant.now().epochSecond.toString()
            val result = post("/api/tickets", bytes, paymentHeaders(timestamp, bytes))
            assertEquals(HttpStatus.BAD_REQUEST, result.statusCode, "JSON ambiguo autenticado $index")
        }
    }

    @Test
    fun `rechaza mensajes de pago no confirmados mal dirigidos o con datos invalidos`() {
        val sale = sale()
        val invalid = listOf(
            sale.payment + ("event" to "payment.failed"),
            sale.payment + ("sourceModule" to "M2_EVENTS"),
            sale.payment + ("targetModule" to "M5_WALLET"),
            sale.paymentWith("paymentStatus" to "PENDING"),
            sale.paymentWith("paymentId" to "pay 1"),
            sale.paymentWith("orderId" to ""),
            sale.paymentWith("reservationId" to "../res"),
            sale.paymentWith("buyerId" to "x".repeat(65)),
            sale.paymentWith("amountMinor" to -1L),
            sale.paymentWith("currency" to "PESOS"),
            sale.paymentWith("currency" to "ZZZ"),
            sale.paymentWith("amountMinor" to 1.5),
            sale.paymentWith("amountMinor" to "123"),
            sale.paymentWith("paymentStatus" to true),
            sale.paymentWith("paidAt" to "ayer"),
            sale.paymentWith("paidAt" to Instant.now().epochSecond),
            sale.paymentWith("paidAt" to Instant.now().plusSeconds(3600).toString())
        )
        invalid.forEachIndexed { index, message ->
            assertEquals(HttpStatus.BAD_REQUEST, sendPayment(message).statusCode, "Caso de pago $index")
        }
    }

    @Test
    fun `rechaza detalles de reserva invalidos o de eventos vencidos`() {
        val base = sale().reservation
        val invalid = listOf(
            base + ("reservationId" to ""),
            base + ("eventId" to "evento con espacio"),
            base + ("seatId" to ""),
            base + ("eventName" to 123),
            base + ("venue" to "linea\ncontrol"),
            base + ("eventStartsAt" to "ayer"),
            base + ("eventStartsAt" to Instant.now().epochSecond),
            base + ("eventEndsAt" to Instant.now().minusSeconds(3600).toString()),
            base + ("eventEndsAt" to base["eventStartsAt"]!!),
            base + ("extra" to true),
            base - "venue"
        )
        invalid.forEachIndexed { index, message ->
            assertEquals(HttpStatus.BAD_REQUEST, sendReservation(message).statusCode, "Caso de reserva $index")
        }
    }

    @Test
    fun `wallet del Modulo 5 recibe sus boletos con su formato y token propio`() {
        val buyerId = "usr_${id()}"
        val first = sale(buyerId = buyerId)
        val second = sale(buyerId = buyerId)
        val token1 = body(issue(first)).path("ticket")
        val token2 = body(issue(second)).path("ticket")
        assertEquals(HttpStatus.CREATED, issue(sale()).statusCode, "Boleto de otro comprador")

        val path = "/api/wallet/$buyerId/tickets"
        assertEquals(HttpStatus.UNAUTHORIZED, http.getForEntity(path, String::class.java).statusCode)
        assertEquals(HttpStatus.UNAUTHORIZED, bearerGet(path, adminToken).statusCode, "El token admin no abre la Wallet")
        assertEquals(HttpStatus.UNAUTHORIZED, bearerGet("/api/tickets", walletToken).statusCode, "El token Wallet no abre admin")

        val response = bearerGet(path, walletToken)
        assertEquals(HttpStatus.OK, response.statusCode)
        val wallet = body(response)
        val tickets = wallet.path("tickets")
        assertEquals(2, tickets.size())
        assertEquals(setOf(token1.path("ticketId").asText(), token2.path("ticketId").asText()),
            tickets.map { it.path("ticketId").asText() }.toSet())
        val item = tickets.first { it.path("ticketId").asText() == token1.path("ticketId").asText() }
        assertEquals(setOf("ticketId", "event", "date", "seat", "titular", "status", "signatureECDSA"),
            item.fieldNames().asSequence().toSet())
        assertEquals(first.reservation["eventName"], item.path("event").asText())
        assertEquals(first.reservation["seatId"], item.path("seat").asText())
        assertEquals(buyerId, item.path("titular").asText())
        assertEquals("ACTIVO", item.path("status").asText())
        val expectedDate = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.of("America/Mexico_City"))
            .format(Instant.parse(first.reservation["eventStartsAt"] as String))
        assertEquals(expectedDate, item.path("date").asText())
        assertEquals(token1.path("token").asText(), item.path("signatureECDSA").asText())

        val jwk = wallet.path("publicKeyJwk")
        assertEquals(setOf("kty", "crv", "x", "y"), jwk.fieldNames().asSequence().toSet())
        assertTrue(independentlyVerifies(item.path("signatureECDSA").asText(), jwk), "La Wallet verifica con publicKeyJwk")

        val empty = body(bearerGet("/api/wallet/usr_sin_boletos_${id()}/tickets", walletToken))
        assertEquals(0, empty.path("tickets").size())
        listOf("/api/wallet/usr%2F1/tickets", "/api/wallet;x=1/$buyerId/tickets", "/api/wallet/$buyerId/tickets/extra").forEach { variant ->
            val result = http.exchange(URI.create(http.rootUri + variant), HttpMethod.GET,
                HttpEntity<Any>(HttpHeaders().apply { setBearerAuth(walletToken) }), String::class.java)
            assertFalse(result.statusCode.is2xxSuccessful, "La credencial Wallet no abre $variant")
        }
    }

    @Test
    fun `consulta de boletos requiere credencial administrativa y pagina los resultados`() {
        assertEquals(HttpStatus.UNAUTHORIZED, http.getForEntity("/api/tickets", String::class.java).statusCode)
        assertEquals(HttpStatus.UNAUTHORIZED, http.getForEntity("/api/tickets/${UUID.randomUUID()}", String::class.java).statusCode)
        assertEquals(HttpStatus.UNAUTHORIZED, bearerGet("/api/tickets", "credencial-invalida-de-prueba").statusCode)
        assertEquals(HttpStatus.CREATED, issue(sale()).statusCode)
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
            HttpEntity(json.writeValueAsBytes(demoRequest()), headers), String::class.java
        )
        assertFalse(result.statusCode.is2xxSuccessful, "Una ruta matrix no autoriza la emisión demo")
    }

    @Test
    fun `demo requiere administrador aun estando habilitada y deriva orden y reserva`() {
        val bytes = json.writeValueAsBytes(demoRequest())
        assertEquals(HttpStatus.UNAUTHORIZED, post("/api/demo/tickets", bytes).statusCode)
        val created = post("/api/demo/tickets", bytes, adminHeaders())
        assertEquals(HttpStatus.CREATED, created.statusCode)
        assertTrue(body(created).path("ticket").path("reservationId").asText().startsWith("demo-res-"))
        assertTrue(post("/verify", json.writeValueAsBytes(mapOf("token" to body(created).path("ticket").path("token").asText())))
            .let { body(it).path("valid").asBoolean() })
    }

    @Test
    fun `verificacion publica rechaza un payload alterado y tokens malformados`() {
        val issued = issue(sale())
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
        val issued = issue(sale())
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
            claims + ("status" to "PENDING"),
            claims - "reservationId"
        )
        invalid.forEachIndexed { index, maliciousClaims ->
            val signed = crypto.sign(maliciousClaims)
            val result = post("/verify", json.writeValueAsBytes(mapOf("token" to signed.token())))
            assertEquals(HttpStatus.OK, result.statusCode)
            assertFalse(body(result).path("valid").asBoolean(), "Claims adversarios $index")
        }
    }

    /** Un par de mensajes M3 + M2 que se refieren a la misma reserva. */
    private class Sale(val paymentPayload: Map<String, Any?>, val reservation: Map<String, Any>) {
        val payment: Map<String, Any?> get() = linkedMapOf(
            "event" to "payment.confirmed", "sourceModule" to "M3_PAYMENTS",
            "targetModule" to "M4_TICKET_ISSUANCE", "payload" to paymentPayload
        )
        fun paymentWith(change: Pair<String, Any?>): Map<String, Any?> = payment + ("payload" to (paymentPayload + change))
    }

    private fun id() = UUID.randomUUID().toString().replace("-", "")

    private fun sale(
        eventId: String = "evt_${id()}",
        seatId: String = "A-${id().take(8)}",
        buyerId: String = "usr_${id()}"
    ): Sale {
        val reservationId = "res_${id()}"
        val start = Instant.now().plusSeconds(86_400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        return Sale(
            linkedMapOf(
                "paymentId" to "pay_${id()}",
                "orderId" to "ord_${id()}",
                "reservationId" to reservationId,
                "buyerId" to buyerId,
                "amountMinor" to 125_000L,
                "currency" to "MXN",
                "paymentStatus" to "PAID",
                "paidAt" to Instant.now().minusSeconds(5).toString()
            ),
            linkedMapOf(
                "reservationId" to reservationId,
                "eventId" to eventId,
                "seatId" to seatId,
                "eventName" to "Noche de concierto",
                "venue" to "Auditorio Central",
                "eventStartsAt" to start.toString(),
                "eventEndsAt" to start.plusSeconds(10_800).toString()
            )
        )
    }

    private fun demoRequest(): Map<String, Any> {
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

    /** M2 primero y luego M3; devuelve la respuesta del mensaje que emite el boleto. */
    private fun issue(sale: Sale): ResponseEntity<String> {
        assertEquals(HttpStatus.ACCEPTED, sendReservation(sale.reservation).statusCode)
        return sendPayment(sale.payment)
    }

    private fun sendPayment(message: Map<String, Any?>): ResponseEntity<String> {
        val bytes = json.writeValueAsBytes(message)
        return post("/webhooks/payments/confirmed", bytes, paymentHeaders(Instant.now().epochSecond.toString(), bytes))
    }

    private fun sendReservation(message: Map<String, Any?>): ResponseEntity<String> {
        val bytes = json.writeValueAsBytes(message)
        val headers = hmacHeaders("X-Reservation", reservationSecret, Instant.now().epochSecond.toString(), bytes)
        return post("/webhooks/reservations/confirmed", bytes, headers)
    }

    private fun paymentHeaders(timestamp: String, bytes: ByteArray) = hmacHeaders("X-Payment", paymentSecret, timestamp, bytes)

    private fun hmacHeaders(prefix: String, secret: ByteArray, timestamp: String, bytes: ByteArray): HttpHeaders {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        mac.update("$timestamp.".toByteArray(StandardCharsets.US_ASCII))
        val signature = HexFormat.of().formatHex(mac.doFinal(bytes))
        return HttpHeaders().apply {
            set("$prefix-Timestamp", timestamp)
            set("$prefix-Signature", signature)
        }
    }

    private fun independentlyVerifies(token: String, jwk: JsonNode): Boolean {
        val parts = token.split('.')
        val decoder = Base64.getUrlDecoder()
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
        return verifier.verify(decoder.decode(parts[2]))
    }

    private fun secret(name: String): String = Files.readString(Path.of("/run/secrets/$name")).trim()

    private fun adminHeaders() = HttpHeaders().apply { setBearerAuth(adminToken) }

    private fun adminGet(path: String): ResponseEntity<String> = bearerGet(path, adminToken)

    private fun bearerGet(path: String, token: String): ResponseEntity<String> =
        http.exchange(path, HttpMethod.GET, HttpEntity<Any>(HttpHeaders().apply { setBearerAuth(token) }), String::class.java)

    private fun post(path: String, bytes: ByteArray, headers: HttpHeaders = HttpHeaders()): ResponseEntity<String> {
        headers.contentType = MediaType.APPLICATION_JSON
        return http.exchange(path, HttpMethod.POST, HttpEntity(bytes, headers), String::class.java)
    }

    private fun body(response: ResponseEntity<String>): JsonNode = json.readTree(response.body)
}
