package com.ticketengine.dto

import java.time.Instant
import java.util.UUID

data class TicketResponse(
    val ticketId: UUID,
    val paymentId: String,
    val eventId: String,
    val buyerId: String,
    val seatId: String,
    val eventName: String,
    val venue: String,
    val eventStartsAt: Instant,
    val eventEndsAt: Instant,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val status: String,
    val token: String,
    val payloadHash: String,
    val keyId: String,
    val payloadJson: String,
    /** Nulos solo en boletos emitidos antes de la integración M2/M3. */
    val orderId: String? = null,
    val reservationId: String? = null
)

data class EmissionResult(val ticket: TicketResponse, val replayed: Boolean)

/**
 * Respuesta a M2 y M3. `status` es ISSUED cuando ya existe el boleto, o
 * PENDING_RESERVATION / PENDING_PAYMENT mientras falta el mensaje del otro módulo.
 */
data class IntakeResult(
    val status: String,
    val reservationId: String,
    val ticket: TicketResponse?,
    val replayed: Boolean
)

data class TicketPage(val items: List<TicketResponse>, val total: Long, val limit: Int, val offset: Int)
data class VerificationRequest(@field:jakarta.validation.constraints.NotBlank @field:jakarta.validation.constraints.Size(max = 24000) val token: String)
data class VerificationResponse(val valid: Boolean, val reason: String, val payload: Map<String, Any>? = null)
data class SystemResponse(val issuer: String, val audience: String, val keyId: String, val demoEnabled: Boolean)

/** Formato acordado con el Módulo 5 (Wallet). */
data class WalletResponse(val tickets: List<WalletTicket>, val publicKeyJwk: Map<String, Any>)

data class WalletTicket(
    val ticketId: String,
    val event: String,
    val date: String,
    val seat: String,
    val titular: String,
    val status: String,
    /** JWS ES256 completo; la Wallet lo verifica con publicKeyJwk. */
    val signatureECDSA: String
)
