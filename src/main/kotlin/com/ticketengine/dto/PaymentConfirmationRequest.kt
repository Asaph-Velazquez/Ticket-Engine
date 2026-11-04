package com.ticketengine.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class PaymentConfirmationRequest(
    val paymentId: UUID,
    val eventId: UUID,
    val buyerId: UUID,
    @field:Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,63}") val seatId: String,
    @field:NotBlank @field:Size(max = 140) val eventName: String,
    @field:NotBlank @field:Size(max = 140) val venue: String,
    val eventStartsAt: Instant,
    val eventEndsAt: Instant,
    @field:Min(0) @field:Max(9_999_999_999_999) val amountMinor: Long,
    @field:Pattern(regexp = "[A-Z]{3}") val currency: String,
    @field:Pattern(regexp = "PAID") val paymentStatus: String
)
