package com.ticketengine.application

import com.ticketengine.crypto.TicketCryptoService
import com.ticketengine.domain.TicketValidationException
import com.ticketengine.dto.EXTERNAL_ID
import com.ticketengine.dto.WalletResponse
import com.ticketengine.dto.WalletTicket
import com.ticketengine.persistence.TicketRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Entrega los boletos de un comprador en el formato que consume el Módulo 5 (Wallet). */
@Service
class WalletService(
    private val repository: TicketRepository,
    private val crypto: TicketCryptoService,
    private val clock: Clock,
    @Value("\${ticket.wallet-time-zone:America/Mexico_City}") timeZone: String
) {
    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.of(timeZone))

    @Transactional(readOnly = true)
    fun ticketsFor(buyerId: String): WalletResponse {
        if (!ID.matches(buyerId)) throw TicketValidationException("buyerId inválido")
        val now = clock.instant()
        val tickets = repository.byBuyer(buyerId, MAX_TICKETS).map { ticket ->
            WalletTicket(
                ticketId = ticket.ticketId.toString(),
                event = ticket.eventName,
                date = dateFormat.format(ticket.eventStartsAt),
                seat = ticket.seatId,
                // No recibimos el nombre del comprador; M5 puede resolverlo con M1 a partir de este id.
                titular = ticket.buyerId,
                status = if (ticket.expiresAt.isAfter(now)) "ACTIVO" else "VENCIDO",
                signatureECDSA = ticket.token
            )
        }
        val jwk = crypto.publicJwk()
        return WalletResponse(tickets, linkedMapOf(
            "kty" to jwk.getValue("kty"), "crv" to jwk.getValue("crv"), "x" to jwk.getValue("x"), "y" to jwk.getValue("y")
        ))
    }

    companion object {
        private val ID = Regex(EXTERNAL_ID)
        private const val MAX_TICKETS = 200
    }
}
