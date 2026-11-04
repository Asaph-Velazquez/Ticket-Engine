package com.ticketengine.webhook;

public final class PaymentAuthenticationException extends RuntimeException {
    public PaymentAuthenticationException() {
        super("No se pudo autenticar la confirmación de pago");
    }
}
