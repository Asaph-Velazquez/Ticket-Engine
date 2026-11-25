package com.ticketengine.application

import com.ticketengine.crypto.TicketCryptoService
import com.ticketengine.domain.TicketConflictException
import com.ticketengine.domain.TicketNotFoundException
import com.ticketengine.domain.TicketValidationException
import com.ticketengine.dto.EXTERNAL_ID
import com.ticketengine.dto.EmissionResult
import com.ticketengine.dto.SystemResponse
import com.ticketengine.dto.TicketIssueRequest
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
    fun emit(rawRequest: TicketIssueRequest): EmissionResult {
        val request = normalizeAndValidate(rawRequest)
        val requestHash = crypto.sha256Hex(crypto.canonicalJson(requestFields(request)).toByteArray(StandardCharsets.UTF_8))
        repository.byPaymentId(request.paymentId)?.let { return replay(it, requestHash) }
        val now = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        if (request.eventEndsAt <= now || request.eventEndsAt > now.plus(5 * 366L, ChronoUnit.DAYS)) {
            throw TicketValidationException("El evento debe vencer en el futuro y dentro de cinco años")
        }
        val ticketId = UUID.randomUUID()
        val claims: Map<String, Any> = mapOf(
            "iss" to issuer, "aud" to audience, "sub" to request.buyerId, "jti" to ticketId.toString(),
            "iat" to now.epochSecond, "nbf" to now.epochSecond, "exp" to request.eventEndsAt.epochSecond,
            "schemaVersion" to 2L, "status" to "ISSUED", "paymentId" to request.paymentId,
            "orderId" to request.orderId!!, "reservationId" to request.reservationId!!,
            "eventId" to request.eventId, "seatId" to request.seatId, "eventName" to request.eventName,
            "venue" to request.venue, "eventStartsAt" to request.eventStartsAt.epochSecond,
            "amountMinor" to request.amountMinor, "currency" to request.currency
        )
        val signed = crypto.sign(claims)
        val ticket = TicketResponse(ticketId, request.paymentId, request.eventId, request.buyerId,
            request.seatId, request.eventName, request.venue, request.eventStartsAt, request.eventEndsAt,
            now, request.eventEndsAt, "ISSUED", signed.token(), signed.payloadHash(), crypto.keyId(), signed.payloadJson(),
            request.orderId, request.reservationId)
        try {
            if (repository.insert(ticket, requestHash, request.amountMinor, request.currency)) {
                return EmissionResult(ticket, false)
            }
        } catch (ex: DuplicateKeyException) {
            // (event_id, seat_id) o reservation_id ya tienen boleto con otro pago.
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
        val version = integer(claims, "schemaVersion")
        val expected = when (version) {
            1L -> CLAIMS_V1
            2L -> CLAIMS_V2
            else -> invalidClaims()
        }
        if (claims.keys != expected || claims["iss"] != issuer || claims["aud"] != audience || claims["status"] != "ISSUED") invalidClaims()
        val jti = claims["jti"] as? String ?: invalidClaims()
        if (!isCanonicalUuid(jti)) invalidClaims()
        // v1 (boletos previos a la integración M2/M3) usaba UUID; v2 usa los identificadores de cada módulo.
        val idKeys = if (version == 1L) listOf("sub", "paymentId", "eventId")
            else listOf("sub", "paymentId", "eventId", "orderId", "reservationId")
        for (key in idKeys) {
            val text = claims[key] as? String ?: invalidClaims()
            val ok = if (version == 1L) isCanonicalUuid(text) else ID.matches(text)
            if (!ok) invalidClaims()
        }
        val now = clock.instant().epochSecond
        val issued = integer(claims, "iat")
        val notBefore = integer(claims, "nbf")
        val expires = integer(claims, "exp")
        val starts = integer(claims, "eventStartsAt")
        if (issued < 0 || starts < 0 || issued > now || notBefore != issued || expires <= now || expires <= issued
            || expires <= starts || expires - issued > 5 * 366L * 86400) invalidClaims()
        val seat = claims["seatId"] as? String ?: invalidClaims()
        if (!ID.matches(seat)) invalidClaims()
        for (key in listOf("eventName", "venue")) {
            val text = claims[key] as? String ?: invalidClaims()
            if (text.isBlank() || text.length > 140 || hasControl(text)) invalidClaims()
        }
        if (integer(claims, "amountMinor") !in 0..9_999_999_999_999) invalidClaims()
        val currency = claims["currency"] as? String ?: invalidClaims()
        if (!CURRENCY.matches(currency)) invalidClaims()
        try { Currency.getInstance(currency) } catch (ex: IllegalArgumentException) { invalidClaims() }
    }

    private fun isCanonicalUuid(text: String): Boolean {
        val id = try { UUID.fromString(text) } catch (ex: IllegalArgumentException) { return false }
        return id != UUID(0, 0) && id.toString() == text
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

    private fun normalizeAndValidate(raw: TicketIssueRequest): TicketIssueRequest {
        if (validator.validate(raw).isNotEmpty()) throw TicketValidationException("Solicitud de emisión inválida")
        if (raw.orderId == null || raw.reservationId == null) throw TicketValidationException("Se requieren orderId y reservationId")
        if (hasControl(raw.eventName) || hasControl(raw.venue)) throw TicketValidationException("Textos inválidos")
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

    private fun requestFields(request: TicketIssueRequest): Map<String, Any> = mapOf(
        "paymentId" to request.paymentId, "orderId" to request.orderId!!, "reservationId" to request.reservationId!!,
        "eventId" to request.eventId, "buyerId" to request.buyerId, "seatId" to request.seatId,
        "eventName" to request.eventName, "venue" to request.venue, "eventStartsAt" to request.eventStartsAt.epochSecond,
        "eventEndsAt" to request.eventEndsAt.epochSecond, "amountMinor" to request.amountMinor,
        "currency" to request.currency, "paymentStatus" to request.paymentStatus
    )

    private fun hasControl(text: String): Boolean = text.codePoints().anyMatch { Character.isISOControl(it) }

    companion object {
        private val ID = Regex(EXTERNAL_ID)
        private val CURRENCY = Regex("[A-Z]{3}")
        private val CLAIMS_V1 = setOf("iss", "aud", "sub", "jti", "iat", "nbf", "exp", "schemaVersion", "status", "paymentId",
            "eventId", "seatId", "eventName", "venue", "eventStartsAt", "amountMinor", "currency")
        private val CLAIMS_V2 = CLAIMS_V1 + setOf("orderId", "reservationId")
    }
}
