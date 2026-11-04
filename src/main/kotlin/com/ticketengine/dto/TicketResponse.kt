package com.ticketengine.dto

import java.time.Instant
import java.util.UUID

data class TicketResponse(
    val ticketId: UUID,
    val paymentId: UUID,
    val eventId: UUID,
    val buyerId: UUID,
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
    val payloadJson: String
)

data class EmissionResult(val ticket: TicketResponse, val replayed: Boolean)
data class TicketPage(val items: List<TicketResponse>, val total: Long, val limit: Int, val offset: Int)
data class VerificationRequest(@field:jakarta.validation.constraints.NotBlank @field:jakarta.validation.constraints.Size(max = 24000) val token: String)
data class VerificationResponse(val valid: Boolean, val reason: String, val payload: Map<String, Any>? = null)
data class SystemResponse(val issuer: String, val audience: String, val keyId: String, val demoEnabled: Boolean)
