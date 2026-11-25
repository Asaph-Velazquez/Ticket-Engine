package com.ticketengine.webhook;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** Autentica las confirmaciones de pago del Módulo 3. */
@Component
public final class PaymentWebhookAuthenticator extends HmacWebhookAuthenticator {
    public PaymentWebhookAuthenticator(@Value("${ticket.webhook-secret}") String secret, Clock clock) {
        super(secret, clock, "el Módulo 3", "INVALID_PAYMENT_AUTH");
    }
}
