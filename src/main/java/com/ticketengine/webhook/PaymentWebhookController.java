package com.ticketengine.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketengine.application.IntegrationIntakeService;
import com.ticketengine.domain.TicketValidationException;
import com.ticketengine.dto.IntakeResult;
import com.ticketengine.dto.PaymentConfirmedMessage;
import com.ticketengine.dto.ReservationDetailsMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** Recibe el pago confirmado (Módulo 3) y el detalle de reserva (Módulo 2). */
@RestController
public class PaymentWebhookController {
    private final PaymentWebhookAuthenticator paymentAuth;
    private final ReservationWebhookAuthenticator reservationAuth;
    private final ObjectMapper json;
    private final IntegrationIntakeService intake;

    public PaymentWebhookController(PaymentWebhookAuthenticator paymentAuth, ReservationWebhookAuthenticator reservationAuth,
                                    ObjectMapper json, IntegrationIntakeService intake) {
        this.paymentAuth = paymentAuth;
        this.reservationAuth = reservationAuth;
        this.json = json;
        this.intake = intake;
    }

    @PostMapping(value = {"/webhooks/payments/confirmed", "/api/tickets"}, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IntakeResult> paymentConfirmed(
            @RequestHeader(value = "X-Payment-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Payment-Signature", required = false) String signature,
            @RequestBody byte[] rawBody) {
        paymentAuth.authenticate(timestamp, signature, rawBody);
        return respond(intake.receivePayment(read(rawBody, PaymentConfirmedMessage.class)));
    }

    @PostMapping(value = "/webhooks/reservations/confirmed", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IntakeResult> reservationConfirmed(
            @RequestHeader(value = "X-Reservation-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Reservation-Signature", required = false) String signature,
            @RequestBody byte[] rawBody) {
        reservationAuth.authenticate(timestamp, signature, rawBody);
        return respond(intake.receiveReservation(read(rawBody, ReservationDetailsMessage.class)));
    }

    private <T> T read(byte[] rawBody, Class<T> type) {
        T value;
        try {
            value = json.readValue(rawBody, type);
        } catch (IOException ex) {
            throw new TicketValidationException("JSON de integración inválido");
        }
        if (value == null) throw new TicketValidationException("Mensaje requerido");
        return value;
    }

    /** 201 boleto nuevo, 200 reintento del mismo boleto, 202 recibido y esperando al otro módulo. */
    private static ResponseEntity<IntakeResult> respond(IntakeResult result) {
        HttpStatus status = result.getTicket() == null ? HttpStatus.ACCEPTED
                : result.getReplayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result);
    }
}
