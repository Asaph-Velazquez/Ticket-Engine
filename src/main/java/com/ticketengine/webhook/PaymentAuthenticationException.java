package com.ticketengine.webhook;

/** Firma o timestamp HMAC ausente, inválido o vencido. */
public final class PaymentAuthenticationException extends RuntimeException {
    private final String code;

    public PaymentAuthenticationException(String code) {
        super("No se pudo autenticar el mensaje del módulo integrador");
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
