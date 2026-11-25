package com.ticketengine.application

import com.ticketengine.crypto.TicketCryptoService
import com.ticketengine.domain.TicketConflictException
import com.ticketengine.domain.TicketValidationException
import com.ticketengine.dto.IntakeResult
import com.ticketengine.dto.PaymentConfirmedMessage
import com.ticketengine.dto.PaymentConfirmedPayload
import com.ticketengine.dto.ReservationDetailsMessage
import com.ticketengine.dto.TicketIssueRequest
import com.ticketengine.persistence.IntakeRepository
import jakarta.validation.Validator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.Currency

/**
 * Une el pago confirmado del Módulo 3 con el detalle de reserva del Módulo 2 por `reservationId`.
 * Los mensajes pueden llegar en cualquier orden; el boleto se emite cuando están ambos.
 */
@Service
class IntegrationIntakeService(
    private val intake: IntakeRepository,
    private val emission: TicketEmissionService,
    private val crypto: TicketCryptoService,
    private val validator: Validator,
    private val clock: Clock
) {
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun receivePayment(message: PaymentConfirmedMessage): IntakeResult {
        if (validator.validate(message).isNotEmpty()) throw TicketValidationException("Mensaje de pago inválido")
        val payment = message.payload.copy(paidAt = message.payload.paidAt.truncatedTo(ChronoUnit.SECONDS))
        try { Currency.getInstance(payment.currency) } catch (ex: IllegalArgumentException) {
            throw TicketValidationException("Moneda ISO 4217 inválida")
        }
        val now = clock.instant()
        if (payment.paidAt.epochSecond < 0 || payment.paidAt.isAfter(now.plusSeconds(300))) {
            throw TicketValidationException("paidAt no puede estar en el futuro")
        }
        val hash = hash(paymentFields(payment))
        intake.lockReservation(payment.reservationId)
        if (!intake.insertPayment(payment, hash, now)) {
            val existing = intake.paymentById(payment.paymentId)
            if (existing == null) {
                throw TicketConflictException("RESERVATION_ALREADY_PAID", "La reserva ya tiene otro pago confirmado")
            }
            if (existing.requestHash != hash) {
                throw TicketConflictException("PAYMENT_CONFLICT", "El pago ya fue recibido con datos diferentes")
            }
        }
        val reservation = intake.reservationById(payment.reservationId)?.reservation
            ?: return IntakeResult("PENDING_RESERVATION", payment.reservationId, null, false)
        return issue(payment, reservation)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun receiveReservation(raw: ReservationDetailsMessage): IntakeResult {
        if (validator.validate(raw).isNotEmpty() || hasControl(raw.eventName) || hasControl(raw.venue)) {
            throw TicketValidationException("Detalle de reserva inválido")
        }
        val reservation = raw.copy(eventStartsAt = raw.eventStartsAt.truncatedTo(ChronoUnit.SECONDS),
            eventEndsAt = raw.eventEndsAt.truncatedTo(ChronoUnit.SECONDS))
        val now = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        if (reservation.eventStartsAt.epochSecond < 0 || reservation.eventEndsAt <= reservation.eventStartsAt) {
            throw TicketValidationException("Fechas del evento inválidas")
        }
        val hash = hash(reservationFields(reservation))
        intake.lockReservation(reservation.reservationId)
        val existing = intake.reservationById(reservation.reservationId)
        if (existing == null) {
            // Solo una reserva nueva exige evento futuro; un reintento idéntico se acepta aunque ya haya vencido.
            if (reservation.eventEndsAt <= now || reservation.eventEndsAt > now.plus(5 * 366L, ChronoUnit.DAYS)) {
                throw TicketValidationException("El evento debe vencer en el futuro y dentro de cinco años")
            }
            intake.insertReservation(reservation, hash, now)
        } else if (existing.requestHash != hash) {
            throw TicketConflictException("RESERVATION_CONFLICT", "La reserva ya fue recibida con datos diferentes")
        }
        val payment = intake.paymentByReservation(reservation.reservationId)?.payment
            ?: return IntakeResult("PENDING_PAYMENT", reservation.reservationId, null, false)
        return issue(payment, reservation)
    }

    private fun issue(payment: PaymentConfirmedPayload, reservation: ReservationDetailsMessage): IntakeResult {
        val result = emission.emit(TicketIssueRequest(
            paymentId = payment.paymentId, eventId = reservation.eventId, buyerId = payment.buyerId,
            seatId = reservation.seatId, eventName = reservation.eventName, venue = reservation.venue,
            eventStartsAt = reservation.eventStartsAt, eventEndsAt = reservation.eventEndsAt,
            amountMinor = payment.amountMinor, currency = payment.currency, paymentStatus = payment.paymentStatus,
            orderId = payment.orderId, reservationId = payment.reservationId
        ))
        return IntakeResult("ISSUED", payment.reservationId, result.ticket, result.replayed)
    }

    private fun hash(fields: Map<String, Any>): String =
        crypto.sha256Hex(crypto.canonicalJson(fields).toByteArray(StandardCharsets.UTF_8))

    private fun paymentFields(p: PaymentConfirmedPayload): Map<String, Any> = mapOf(
        "paymentId" to p.paymentId, "orderId" to p.orderId, "reservationId" to p.reservationId, "buyerId" to p.buyerId,
        "amountMinor" to p.amountMinor, "currency" to p.currency, "paymentStatus" to p.paymentStatus,
        "paidAt" to p.paidAt.epochSecond
    )

    private fun reservationFields(r: ReservationDetailsMessage): Map<String, Any> = mapOf(
        "reservationId" to r.reservationId, "eventId" to r.eventId, "seatId" to r.seatId, "eventName" to r.eventName,
        "venue" to r.venue, "eventStartsAt" to r.eventStartsAt.epochSecond, "eventEndsAt" to r.eventEndsAt.epochSecond
    )

    private fun hasControl(text: String): Boolean = text.codePoints().anyMatch { Character.isISOControl(it) }
}
