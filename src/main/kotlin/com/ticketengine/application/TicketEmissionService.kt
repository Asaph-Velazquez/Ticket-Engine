package com.ticketengine.application

import com.ticketengine.crypto.TicketCryptoService
import com.ticketengine.domain.TicketConflictException
import com.ticketengine.domain.TicketNotFoundException
import com.ticketengine.domain.TicketValidationException
import com.ticketengine.dto.EmissionResult
import com.ticketengine.dto.PaymentConfirmationRequest
import com.ticketengine.dto.SystemResponse
import com.ticketengine.dto.TicketPage
import com.ticketengine.dto.TicketResponse
import com.ticketengine.dto.VerificationResponse
import com.ticketengine.persistence.StoredTicket
import com.ticketengine.persistence.TicketRepository
import jakarta.validation.Validator
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Currency
import java.util.UUID

@Service
class TicketEmissionService(
    private val repository: TicketRepository,
    private val crypto: TicketCryptoService,
    private val validator: Validator,
    private val clock: Clock,
    @Value("\${ticket.issuer:ticket-engine}") private val issuer: String,
    @Value("\${ticket.audience:ticket-wallet-access}") private val audience: String,
    @Value("\${ticket.demo-enabled:false}") private val demoEnabled: Boolean
) {
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun emit(rawRequest: PaymentConfirmationRequest): EmissionResult {
        val request = normalizeAndValidate(rawRequest)
        val requestHash = crypto.sha256Hex(crypto.canonicalJson(requestFields(request)).toByteArray(StandardCharsets.UTF_8))
        repository.byPaymentId(request.paymentId)?.let { return replay(it, requestHash) }
        val now = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        if (request.eventEndsAt <= now || request.eventEndsAt > now.plus(5 * 366L, ChronoUnit.DAYS)) {
            throw TicketValidationException("El evento debe vencer en el futuro y dentro de cinco años")
        }
        val ticketId = UUID.randomUUID()
        val claims: Map<String, Any> = mapOf(
            "iss" to issuer, "aud" to audience, "sub" to request.buyerId.toString(), "jti" to ticketId.toString(),
            "iat" to now.epochSecond, "nbf" to now.epochSecond, "exp" to request.eventEndsAt.epochSecond,
            "schemaVersion" to 1L, "status" to "ISSUED", "paymentId" to request.paymentId.toString(),
            "eventId" to request.eventId.toString(), "seatId" to request.seatId, "eventName" to request.eventName,
            "venue" to request.venue, "eventStartsAt" to request.eventStartsAt.epochSecond,
            "amountMinor" to request.amountMinor, "currency" to request.currency
        )
        val signed = crypto.sign(claims)
        val ticket = TicketResponse(ticketId, request.paymentId, request.eventId, request.buyerId,
            request.seatId, request.eventName, request.venue, request.eventStartsAt, request.eventEndsAt,
            now, request.eventEndsAt, "ISSUED", signed.token(), signed.payloadHash(), crypto.keyId(), signed.payloadJson())
        try {
            if (repository.insert(ticket, requestHash, request.amountMinor, request.currency)) {
                return EmissionResult(ticket, false)
            }
        } catch (ex: DuplicateKeyException) {
            throw TicketConflictException("SEAT_ALREADY_ISSUED", "El asiento ya tiene un boleto para este evento")
        }
        // PostgreSQL ON CONFLICT waits for the first transaction; READ_COMMITTED sees its committed row here.
        val existing = repository.byPaymentId(request.paymentId)
            ?: throw IllegalStateException("No se pudo recuperar el boleto confirmado")
        return replay(existing, requestHash)
    }

    @Transactional(readOnly = true)
    fun get(ticketId: UUID): TicketResponse = repository.byId(ticketId) ?: throw TicketNotFoundException()

    @Transactional(readOnly = true)
    fun list(limit: Int, offset: Int): TicketPage {
        if (limit !in 1..100 || offset < 0) throw TicketValidationException("Paginación inválida")
        return repository.page(limit, offset)
    }

    fun system(): SystemResponse = SystemResponse(issuer, audience, crypto.keyId(), demoEnabled)

    fun verify(token: String): VerificationResponse {
        val claims = try {
            crypto.verify(token)
        } catch (ex: IllegalArgumentException) {
            return VerificationResponse(false, "INVALID_SIGNATURE_OR_FORMAT")
        }
        return try {
            validateClaims(claims)
            VerificationResponse(true, "VALID", claims)
        } catch (ex: TicketValidationException) {
            VerificationResponse(false, "INVALID_CLAIMS_OR_VALIDITY")
        }
    }

    private fun validateClaims(claims: Map<String, Any>) {
        val expected = setOf("iss", "aud", "sub", "jti", "iat", "nbf", "exp", "schemaVersion", "status", "paymentId",
            "eventId", "seatId", "eventName", "venue", "eventStartsAt", "amountMinor", "currency")
        if (claims.keys != expected || claims["iss"] != issuer || claims["aud"] != audience || claims["status"] != "ISSUED"
            || integer(claims, "schemaVersion") != 1L) invalidClaims()
        for (key in listOf("sub", "jti", "paymentId", "eventId")) {
            val text = claims[key] as? String ?: invalidClaims()
            val id = try { UUID.fromString(text) } catch (ex: IllegalArgumentException) { invalidClaims() }
            if (id == UUID(0, 0) || id.toString() != text) invalidClaims()
        }
        val now = clock.instant().epochSecond
        val issued = integer(claims, "iat")
        val notBefore = integer(claims, "nbf")
        val expires = integer(claims, "exp")
        val starts = integer(claims, "eventStartsAt")
        if (issued < 0 || starts < 0 || issued > now || notBefore != issued || expires <= now || expires <= issued
            || expires <= starts || expires - issued > 5 * 366L * 86400) invalidClaims()
        val seat = claims["seatId"] as? String ?: invalidClaims()
        if (!SEAT.matches(seat)) invalidClaims()
        for (key in listOf("eventName", "venue")) {
            val text = claims[key] as? String ?: invalidClaims()
            if (text.isBlank() || text.length > 140 || hasControl(text)) invalidClaims()
        }
        if (integer(claims, "amountMinor") !in 0..9_999_999_999_999) invalidClaims()
        val currency = claims["currency"] as? String ?: invalidClaims()
        if (!CURRENCY.matches(currency)) invalidClaims()
        try { Currency.getInstance(currency) } catch (ex: IllegalArgumentException) { invalidClaims() }
    }

    private fun integer(claims: Map<String, Any>, field: String): Long {
        val value = claims[field]
        if (value !is Int && value !is Long) invalidClaims()
        return (value as Number).toLong()
    }

    private fun invalidClaims(): Nothing = throw TicketValidationException("Claims inválidos")

    private fun replay(existing: StoredTicket, requestHash: String): EmissionResult {
        if (existing.requestHash != requestHash) {
            throw TicketConflictException("PAYMENT_CONFLICT", "El pago ya fue utilizado con datos diferentes")
        }
        return EmissionResult(existing.ticket, true)
    }

    private fun normalizeAndValidate(raw: PaymentConfirmationRequest): PaymentConfirmationRequest {
        if (validator.validate(raw).isNotEmpty()) throw TicketValidationException("Solicitud de pago inválida")
        if (raw.paymentId == UUID(0, 0) || raw.eventId == UUID(0, 0) || raw.buyerId == UUID(0, 0)
            || hasControl(raw.eventName) || hasControl(raw.venue)) throw TicketValidationException("Identificadores o textos inválidos")
        try { Currency.getInstance(raw.currency) } catch (ex: IllegalArgumentException) {
            throw TicketValidationException("Moneda ISO 4217 inválida")
        }
        val request = raw.copy(eventStartsAt = raw.eventStartsAt.truncatedTo(ChronoUnit.SECONDS),
            eventEndsAt = raw.eventEndsAt.truncatedTo(ChronoUnit.SECONDS))
        if (request.eventStartsAt.epochSecond < 0 || request.eventEndsAt <= request.eventStartsAt) {
            throw TicketValidationException("Fechas del evento inválidas")
        }
        return request
    }

    private fun requestFields(request: PaymentConfirmationRequest): Map<String, Any> = mapOf(
        "paymentId" to request.paymentId.toString(), "eventId" to request.eventId.toString(),
        "buyerId" to request.buyerId.toString(), "seatId" to request.seatId, "eventName" to request.eventName,
        "venue" to request.venue, "eventStartsAt" to request.eventStartsAt.epochSecond,
        "eventEndsAt" to request.eventEndsAt.epochSecond, "amountMinor" to request.amountMinor,
        "currency" to request.currency, "paymentStatus" to request.paymentStatus
    )

    private fun hasControl(text: String): Boolean = text.codePoints().anyMatch { Character.isISOControl(it) }

    companion object {
        private val SEAT = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")
        private val CURRENCY = Regex("[A-Z]{3}")
    }
}
