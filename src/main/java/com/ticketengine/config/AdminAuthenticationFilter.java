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

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AdminAuthenticationFilter extends OncePerRequestFilter {
    private final byte[] expectedDigest;

    public AdminAuthenticationFilter(@Value("${ticket.admin-token}") String adminToken) {
        if (adminToken.getBytes(StandardCharsets.UTF_8).length < 32 || adminToken.isBlank()) {
            throw new IllegalStateException("Se requiere un token administrativo de al menos 32 bytes");
        }
        expectedDigest = digest(adminToken);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean publicRead = "GET".equals(request.getMethod())
                && ("/public-key".equals(path) || "/.well-known/jwks.json".equals(path)
                || "/actuator/health".equals(path) || "/openapi.yaml".equals(path));
        boolean publicPost = "POST".equals(request.getMethod())
                && ("/verify".equals(path) || "/api/tickets".equals(path) || "/webhooks/payments/confirmed".equals(path));
        // Default-deny also protects matrix parameters and alternate path spellings before MVC normalization.
        if (publicRead || publicPost) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")
                || !MessageDigest.isEqual(expectedDigest, digest(header.substring(7)))) {
            response.setStatus(401);
            response.setHeader("WWW-Authenticate", "Bearer realm=\"ticket-engine-admin\"");
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Acceso administrativo requerido\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static byte[] digest(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }
}
