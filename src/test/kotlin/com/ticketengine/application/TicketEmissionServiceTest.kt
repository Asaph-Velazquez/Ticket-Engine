package com.ticketengine.application

import com.ticketengine.TicketEngineApplication
import com.ticketengine.crypto.TicketCryptoService
import com.ticketengine.domain.TicketConflictException
import com.ticketengine.domain.TicketValidationException
import com.ticketengine.dto.PaymentConfirmationRequest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(classes = [TicketEngineApplication::class, TicketEmissionServiceTest.FixedClockConfiguration::class])
class TicketEmissionServiceTest @Autowired constructor(
    private val service: TicketEmissionService,
    private val crypto: TicketCryptoService,
    private val jdbc: JdbcTemplate,
    private val testClock: MutableTestClock
) {
    private val now = Instant.parse("2030-01-01T00:00:00Z")

    @BeforeEach
    fun resetClock() {
        testClock.set(now)
    }

    @Test
    fun `emite token valido y replay conserva exactamente boleto y firma`() {
        val request = request()
        val first = service.emit(request)
        val repeated = service.emit(request)
        assertFalse(first.replayed)
        assertTrue(repeated.replayed)
        assertEquals(first.ticket, repeated.ticket)
        assertTrue(service.verify(first.ticket.token).valid)
        assertEquals(first.ticket, service.get(first.ticket.ticketId))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM tickets WHERE payment_id = ?", Int::class.java, request.paymentId))
    }

    @Test
    fun `normaliza fechas a segundos antes del fingerprint`() {
        val request = request()
        val first = service.emit(request.copy(eventStartsAt = request.eventStartsAt.plusNanos(500), eventEndsAt = request.eventEndsAt.plusNanos(500)))
        val again = service.emit(request)
        assertTrue(again.replayed)
        assertEquals(first.ticket.token, again.ticket.token)
        assertEquals(0, first.ticket.eventStartsAt.nano)
    }

    @Test
    fun `reintentar pago despues de vencer devuelve boleto historico pero verify rechaza vigencia`() {
        val request = request()
        val first = service.emit(request)
        testClock.set(request.eventEndsAt.plusSeconds(60))
        val replay = service.emit(request)
        assertTrue(replay.replayed)
        assertEquals(first.ticket, replay.ticket)
        val verification = service.verify(replay.ticket.token)
        assertFalse(verification.valid)
        assertNull(verification.payload)
    }

    @Test
    fun `mismo pago con contenido diferente produce conflicto`() {
        val request = request()
        service.emit(request)
        val failure = assertFailsWith<TicketConflictException> { service.emit(request.copy(amountMinor = request.amountMinor + 1)) }
        assertEquals("PAYMENT_CONFLICT", failure.code)
    }

    @Test
    fun `asiento vendido no se puede emitir con otro pago`() {
        val request = request()
        service.emit(request)
        val failure = assertFailsWith<TicketConflictException> { service.emit(request.copy(paymentId = UUID.randomUUID())) }
        assertEquals("SEAT_ALREADY_ISSUED", failure.code)
    }

    @Test
    fun `reintentos concurrentes emiten un solo boleto y el mismo JWS`() {
        val request = request()
        val pool = Executors.newFixedThreadPool(6)
        try {
            val jobs = (1..12).map { Callable { service.emit(request) } }
            val results = pool.invokeAll(jobs).map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, results.count { !it.replayed })
            assertEquals(1, results.map { it.ticket.ticketId }.toSet().size)
            assertEquals(1, results.map { it.ticket.token }.toSet().size)
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM tickets WHERE payment_id = ?", Int::class.java, request.paymentId))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `verify rechaza claims invalidos aunque la firma sea autentica`() {
        val valid = crypto.verify(service.emit(request()).ticket.token)
        val variations: List<Map<String, Any>> = listOf(
            mapOf("exp" to now.epochSecond), mapOf("nbf" to now.plusSeconds(60).epochSecond),
            mapOf("iat" to now.plusSeconds(60).epochSecond), mapOf("iss" to "otro-emisor"),
            mapOf("aud" to "otra-wallet"), mapOf("schemaVersion" to 2L), mapOf("status" to "CANCELLED"),
            mapOf("sub" to "1-1-1-1-1"), mapOf("jti" to UUID(0, 0).toString()),
            mapOf("eventId" to "not-a-uuid"), mapOf("currency" to "ZZZ"), mapOf("amountMinor" to -1L),
            mapOf("seatId" to "<script>"), mapOf("venue" to "linea\ncontrol"), mapOf("exp" to "2030"),
            mapOf("eventStartsAt" to now.plusSeconds(10000).epochSecond), mapOf("extra" to "unexpected")
        )
        variations.forEach { changes ->
            val token = crypto.sign(valid + changes).token()
            val response = service.verify(token)
            assertFalse(response.valid, "No debe aceptar claims alterados: ${changes.keys}")
            assertNull(response.payload)
        }
        assertFalse(service.verify(crypto.sign(valid - "sub").token()).valid)
    }

    @Test
    fun `rechaza solicitudes sin pago fechas vencidas campos control e identificadores vacios`() {
        val request = request()
        val invalid = listOf(
            request.copy(paymentStatus = "PENDING"), request.copy(paymentId = UUID(0, 0)),
            request.copy(buyerId = UUID(0, 0)), request.copy(eventId = UUID(0, 0)),
            request.copy(eventEndsAt = now.minusSeconds(1), eventStartsAt = now.minusSeconds(100)),
            request.copy(eventEndsAt = request.eventStartsAt), request.copy(eventEndsAt = now.plusSeconds(6L * 366 * 86400)),
            request.copy(eventName = "texto\u0000control"), request.copy(venue = "\ncontrol"),
            request.copy(currency = "ZZZ"), request.copy(amountMinor = -1), request.copy(seatId = "../asiento")
        )
        invalid.forEach { bad -> assertFailsWith<TicketValidationException> { service.emit(bad) } }
    }

    @Test
    fun `limita paginacion antes de consultar persistencia`() {
        assertFailsWith<TicketValidationException> { service.list(0, 0) }
        assertFailsWith<TicketValidationException> { service.list(101, 0) }
        assertFailsWith<TicketValidationException> { service.list(20, -1) }
        assertEquals(1, service.list(1, 0).limit)
    }

    private fun request() = PaymentConfirmationRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        "A-${UUID.randomUUID()}", "Concierto de integración", "Auditorio", now.plusSeconds(3600), now.plusSeconds(7200),
        125000L, "MXN", "PAID")

    class MutableTestClock : Clock() {
        private val value = AtomicReference(Instant.parse("2030-01-01T00:00:00Z"))
        fun set(instant: Instant) { value.set(instant) }
        override fun instant(): Instant = value.get()
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    }

    @TestConfiguration
    class FixedClockConfiguration {
        @Bean @Primary
        fun mutableTestClock() = MutableTestClock()
    }
}
