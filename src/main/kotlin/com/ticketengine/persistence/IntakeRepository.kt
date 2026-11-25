package com.ticketengine.persistence

import com.ticketengine.dto.PaymentConfirmedPayload
import com.ticketengine.dto.ReservationDetailsMessage
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

data class StoredPayment(val payment: PaymentConfirmedPayload, val requestHash: String)
data class StoredReservation(val reservation: ReservationDetailsMessage, val requestHash: String)

/** Mensajes recibidos de M3 y M2 mientras se espera su contraparte. Solo SELECT/INSERT. */
@Repository
class IntakeRepository(private val jdbc: JdbcTemplate) {
    private val paymentMapper = RowMapper { rs, _ ->
        StoredPayment(
            PaymentConfirmedPayload(
                rs.getString("payment_id"), rs.getString("order_id"), rs.getString("reservation_id"),
                rs.getString("buyer_id"), rs.getLong("amount_minor"), rs.getString("currency"), "PAID",
                rs.getObject("paid_at", OffsetDateTime::class.java).toInstant()
            ), rs.getString("request_hash")
        )
    }
    private val reservationMapper = RowMapper { rs, _ ->
        StoredReservation(
            ReservationDetailsMessage(
                rs.getString("reservation_id"), rs.getString("event_id"), rs.getString("seat_id"),
                rs.getString("event_name"), rs.getString("venue"),
                rs.getObject("event_starts_at", OffsetDateTime::class.java).toInstant(),
                rs.getObject("event_ends_at", OffsetDateTime::class.java).toInstant()
            ), rs.getString("request_hash")
        )
    }

    /**
     * Serializa M2 y M3 de la misma reserva dentro de la transacción actual. Sin este candado,
     * dos mensajes simultáneos no verían el registro del otro y ninguno emitiría el boleto.
     */
    fun lockReservation(reservationId: String) {
        jdbc.queryForList("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?, 4))) AS l", reservationId)
    }

    fun paymentById(paymentId: String): StoredPayment? =
        jdbc.query("SELECT * FROM payment_confirmations WHERE payment_id = ?", paymentMapper, paymentId).firstOrNull()

    fun paymentByReservation(reservationId: String): StoredPayment? =
        jdbc.query("SELECT * FROM payment_confirmations WHERE reservation_id = ?", paymentMapper, reservationId).firstOrNull()

    fun reservationById(reservationId: String): StoredReservation? =
        jdbc.query("SELECT * FROM reservation_details WHERE reservation_id = ?", reservationMapper, reservationId).firstOrNull()

    /** false si ya existía un pago con ese paymentId o esa reserva. */
    fun insertPayment(p: PaymentConfirmedPayload, requestHash: String, receivedAt: Instant): Boolean =
        jdbc.update(
            """INSERT INTO payment_confirmations
                (payment_id,reservation_id,order_id,buyer_id,amount_minor,currency,paid_at,request_hash,received_at)
                VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING""".trimIndent(),
            p.paymentId, p.reservationId, p.orderId, p.buyerId, p.amountMinor, p.currency,
            p.paidAt.atOffset(ZoneOffset.UTC), requestHash, receivedAt.atOffset(ZoneOffset.UTC)
        ) == 1

    fun insertReservation(r: ReservationDetailsMessage, requestHash: String, receivedAt: Instant): Boolean =
        jdbc.update(
            """INSERT INTO reservation_details
                (reservation_id,event_id,seat_id,event_name,venue,event_starts_at,event_ends_at,request_hash,received_at)
                VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING""".trimIndent(),
            r.reservationId, r.eventId, r.seatId, r.eventName, r.venue,
            r.eventStartsAt.atOffset(ZoneOffset.UTC), r.eventEndsAt.atOffset(ZoneOffset.UTC),
            requestHash, receivedAt.atOffset(ZoneOffset.UTC)
        ) == 1
}
