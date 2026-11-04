package com.ticketengine.webhook;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

@Component
public final class PaymentWebhookAuthenticator {
    private final SecretKeySpec key;
    private final Clock clock;

    public PaymentWebhookAuthenticator(@Value("${ticket.webhook-secret}") String secret, Clock clock) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32 || secret.isBlank()) {
            throw new IllegalStateException("Se requiere un secreto de webhook de al menos 32 bytes");
        }
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
        this.clock = clock;
    }

    public void authenticate(String timestamp, String signature, byte[] rawBody) {
        if (timestamp == null || !timestamp.matches("[0-9]{1,12}")
                || signature == null || !signature.matches("[a-fA-F0-9]{64}")) {
            throw new PaymentAuthenticationException();
        }
        long seconds = Long.parseLong(timestamp);
        long now = clock.instant().getEpochSecond();
        if (seconds < now - 300 || seconds > now + 300) {
            throw new PaymentAuthenticationException();
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            byte[] actual = mac.doFinal(rawBody);
            if (!MessageDigest.isEqual(actual, HexFormat.of().parseHex(signature))) {
                throw new PaymentAuthenticationException();
            }
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 no disponible", ex);
        }
    }
}
