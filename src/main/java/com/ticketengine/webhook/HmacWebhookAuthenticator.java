package com.ticketengine.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

/**
 * HMAC-SHA256 sobre {@code timestamp + "." + body}, con ventana de 300 segundos.
 * Cada módulo integrador (M2, M3) tiene su propio secreto.
 */
public abstract class HmacWebhookAuthenticator {
    private final SecretKeySpec key;
    private final Clock clock;
    private final String errorCode;

    protected HmacWebhookAuthenticator(String secret, Clock clock, String purpose, String errorCode) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32 || secret.isBlank()) {
            throw new IllegalStateException("Se requiere un secreto HMAC de al menos 32 bytes para " + purpose);
        }
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
        this.clock = clock;
        this.errorCode = errorCode;
    }

    public void authenticate(String timestamp, String signature, byte[] rawBody) {
        if (timestamp == null || !timestamp.matches("[0-9]{1,12}")
                || signature == null || !signature.matches("[a-fA-F0-9]{64}")) {
            throw new PaymentAuthenticationException(errorCode);
        }
        long seconds = Long.parseLong(timestamp);
        long now = clock.instant().getEpochSecond();
        if (seconds < now - 300 || seconds > now + 300) {
            throw new PaymentAuthenticationException(errorCode);
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            byte[] actual = mac.doFinal(rawBody);
            if (!MessageDigest.isEqual(actual, HexFormat.of().parseHex(signature))) {
                throw new PaymentAuthenticationException(errorCode);
            }
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 no disponible", ex);
        }
    }
}
