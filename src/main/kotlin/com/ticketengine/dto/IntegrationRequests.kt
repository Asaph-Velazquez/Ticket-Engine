package com.ticketengine.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant

const val EXTERNAL_ID = "[A-Za-z0-9][A-Za-z0-9._:-]{0,63}"

/** Mensaje del Módulo 3: `POST /webhooks/payments/confirmed`. */
data class PaymentConfirmedMessage(
    @field:Pattern(regexp = "payment\\.confirmed") val event: String,
    @field:Pattern(regexp = "M3_PAYMENTS") val sourceModule: String,
    @field:Pattern(regexp = "M4_TICKET_ISSUANCE") val targetModule: String,
    @field:Valid val payload: PaymentConfirmedPayload
)

data class PaymentConfirmedPayload(
    @field:Pattern(regexp = EXTERNAL_ID) val paymentId: String,
    @field:Pattern(regexp = EXTERNAL_ID) val orderId: String,
    @field:Pattern(regexp = EXTERNAL_ID) val reservationId: String,
    @field:Pattern(regexp = EXTERNAL_ID) val buyerId: String,
    @field:Min(0) @field:Max(9_999_999_999_999) val amountMinor: Long,
    @field:Pattern(regexp = "[A-Z]{3}") val currency: String,
    @field:Pattern(regexp = "PAID") val paymentStatus: String,
    val paidAt: Instant
)

/** Mensaje del Módulo 2: `POST /webhooks/reservations/confirmed`. */
data class ReservationDetailsMessage(
    @field:Pattern(regexp = EXTERNAL_ID) val reservationId: String,
    @field:Pattern(regexp = EXTERNAL_ID) val eventId: String,
    @field:Pattern(regexp = EXTERNAL_ID) val seatId: String,
    @field:NotBlank @field:Size(max = 140) val eventName: String,
    @field:NotBlank @field:Size(max = 140) val venue: String,
    val eventStartsAt: Instant,
    val eventEndsAt: Instant
)

/** Datos completos para firmar un boleto (pago de M3 + reserva de M2, o el sandbox). */
data class TicketIssueRequest(
    @field:Pattern(regexp = EXTERNAL_ID) val paymentId: String,
    @field:Pattern(regexp = EXTERNAL_ID) val eventId: String,
    @field:Pattern(regexp = EXTERNAL_ID) val buyerId: String,
    @field:Pattern(regexp = EXTERNAL_ID) val seatId: String,
    @field:NotBlank @field:Size(max = 140) val eventName: String,
    @field:NotBlank @field:Size(max = 140) val venue: String,
    val eventStartsAt: Instant,
    val eventEndsAt: Instant,
    @field:Min(0) @field:Max(9_999_999_999_999) val amountMinor: Long,
    @field:Pattern(regexp = "[A-Z]{3}") val currency: String,
    @field:Pattern(regexp = "PAID") val paymentStatus: String,
    @field:Pattern(regexp = EXTERNAL_ID) val orderId: String? = null,
    @field:Pattern(regexp = EXTERNAL_ID) val reservationId: String? = null
) {
    /** El sandbox puede omitir orden y reserva; se derivan del pago para conservar el perfil v2. */
    fun withSandboxDefaults(): TicketIssueRequest = copy(
        orderId = orderId ?: "demo-ord-$paymentId".take(64),
        reservationId = reservationId ?: "demo-res-$paymentId".take(64)
    )
}
