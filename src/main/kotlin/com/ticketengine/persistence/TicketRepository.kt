package com.ticketengine.persistence

import com.ticketengine.dto.TicketPage
import com.ticketengine.dto.TicketResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class StoredTicket(val ticket: TicketResponse, val requestHash: String)

@Repository
class TicketRepository(private val jdbc: JdbcTemplate) {
    private val mapper = RowMapper { rs: ResultSet, _: Int ->
        StoredTicket(
            TicketResponse(
                rs.getObject("ticket_id", UUID::class.java), rs.getObject("payment_id", UUID::class.java),
                rs.getObject("event_id", UUID::class.java), rs.getObject("buyer_id", UUID::class.java),
                rs.getString("seat_id"), rs.getString("event_name"), rs.getString("venue"),
                rs.getObject("event_starts_at", OffsetDateTime::class.java).toInstant(),
                rs.getObject("event_ends_at", OffsetDateTime::class.java).toInstant(),
                rs.getObject("issued_at", OffsetDateTime::class.java).toInstant(),
                rs.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
                rs.getString("status"), rs.getString("token"), rs.getString("payload_hash"),
                rs.getString("key_id"), rs.getString("payload_json")
            ), rs.getString("request_hash")
        )
    }

    fun byPaymentId(paymentId: UUID): StoredTicket? =
        jdbc.query("SELECT * FROM tickets WHERE payment_id = ?", mapper, paymentId).firstOrNull()

    fun byId(ticketId: UUID): TicketResponse? =
        jdbc.query("SELECT * FROM tickets WHERE ticket_id = ?", mapper, ticketId).firstOrNull()?.ticket

    fun page(limit: Int, offset: Int): TicketPage = TicketPage(
        jdbc.query("SELECT * FROM tickets ORDER BY issued_at DESC, ticket_id DESC LIMIT ? OFFSET ?", mapper, limit, offset).map { it.ticket },
        jdbc.queryForObject("SELECT count(*) FROM tickets", Long::class.java) ?: 0,
        limit, offset
    )

    fun insert(ticket: TicketResponse, requestHash: String, amountMinor: Long, currency: String): Boolean =
        jdbc.update(
            """INSERT INTO tickets
                (ticket_id,payment_id,event_id,buyer_id,seat_id,event_name,venue,event_starts_at,event_ends_at,
                 issued_at,expires_at,status,token,payload_hash,key_id,payload_json,request_hash,amount_minor,currency)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (payment_id) DO NOTHING""".trimIndent(),
            ticket.ticketId, ticket.paymentId, ticket.eventId, ticket.buyerId, ticket.seatId, ticket.eventName, ticket.venue,
            ticket.eventStartsAt.atOffset(ZoneOffset.UTC), ticket.eventEndsAt.atOffset(ZoneOffset.UTC),
            ticket.issuedAt.atOffset(ZoneOffset.UTC), ticket.expiresAt.atOffset(ZoneOffset.UTC), ticket.status,
            ticket.token, ticket.payloadHash, ticket.keyId, ticket.payloadJson, requestHash, amountMinor, currency
        ) == 1
}
