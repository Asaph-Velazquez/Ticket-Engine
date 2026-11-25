package com.ticketengine.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AdminAuthenticationFilter extends OncePerRequestFilter {
    /** Única forma aceptada de la ruta de la Wallet; cualquier variante cae en el control administrativo. */
    private static final Pattern WALLET_PATH = Pattern.compile("/api/wallet/[A-Za-z0-9][A-Za-z0-9._:-]{0,63}/tickets");

    private final byte[] expectedDigest;
    private final byte[] walletDigest;

    public AdminAuthenticationFilter(@Value("${ticket.admin-token}") String adminToken,
                                     @Value("${ticket.wallet-token}") String walletToken) {
        requireStrong(adminToken, "administrativo");
        requireStrong(walletToken, "de la Wallet");
        expectedDigest = digest(adminToken);
        walletDigest = digest(walletToken);
        if (MessageDigest.isEqual(expectedDigest, walletDigest)) {
            throw new IllegalStateException("El token de la Wallet debe ser distinto del administrativo");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean get = "GET".equals(request.getMethod());
        boolean publicRead = get
                && ("/public-key".equals(path) || "/.well-known/jwks.json".equals(path)
                || "/actuator/health".equals(path) || "/openapi.yaml".equals(path));
        // Los webhooks de M2 y M3 se autentican con HMAC en el controlador.
        boolean publicPost = "POST".equals(request.getMethod())
                && ("/verify".equals(path) || "/api/tickets".equals(path) || "/webhooks/payments/confirmed".equals(path)
                || "/webhooks/reservations/confirmed".equals(path));
        // Default-deny also protects matrix parameters and alternate path spellings before MVC normalization.
        if (publicRead || publicPost) {
            chain.doFilter(request, response);
            return;
        }
        boolean walletRoute = get && WALLET_PATH.matcher(path).matches();
        byte[] required = walletRoute ? walletDigest : expectedDigest;
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")
                || !MessageDigest.isEqual(required, digest(header.substring(7)))) {
            response.setStatus(401);
            response.setHeader("WWW-Authenticate", walletRoute ? "Bearer realm=\"ticket-engine-wallet\"" : "Bearer realm=\"ticket-engine-admin\"");
            response.setContentType("application/json");
            response.getWriter().write(walletRoute
                    ? "{\"code\":\"UNAUTHORIZED\",\"message\":\"Credencial de la Wallet requerida\"}"
                    : "{\"code\":\"UNAUTHORIZED\",\"message\":\"Acceso administrativo requerido\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void requireStrong(String token, String label) {
        if (token.getBytes(StandardCharsets.UTF_8).length < 32 || token.isBlank()) {
            throw new IllegalStateException("Se requiere un token " + label + " de al menos 32 bytes");
        }
    }

    private static byte[] digest(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }
}
