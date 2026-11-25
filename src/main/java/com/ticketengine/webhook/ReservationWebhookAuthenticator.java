package com.ticketengine.webhook;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** Autentica el detalle de reserva del Módulo 2 con un secreto distinto al de pagos. */
@Component
public final class ReservationWebhookAuthenticator extends HmacWebhookAuthenticator {
    public ReservationWebhookAuthenticator(@Value("${ticket.reservation-webhook-secret}") String secret, Clock clock) {
        super(secret, clock, "el Módulo 2", "INVALID_RESERVATION_AUTH");
    }
}
