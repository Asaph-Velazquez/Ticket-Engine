package com.ticketengine.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketengine.application.TicketEmissionService;
import com.ticketengine.domain.TicketValidationException;
import com.ticketengine.dto.EmissionResult;
import com.ticketengine.dto.PaymentConfirmationRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class PaymentWebhookController {
    private final PaymentWebhookAuthenticator authenticator;
    private final ObjectMapper json;
    private final TicketEmissionService service;

    public PaymentWebhookController(PaymentWebhookAuthenticator authenticator, ObjectMapper json, TicketEmissionService service) {
        this.authenticator = authenticator;
        this.json = json;
        this.service = service;
    }

    @PostMapping(value = {"/webhooks/payments/confirmed", "/api/tickets"}, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EmissionResult> confirmed(
            @RequestHeader(value = "X-Payment-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Payment-Signature", required = false) String signature,
            @RequestBody byte[] rawBody) {
        authenticator.authenticate(timestamp, signature, rawBody);
        PaymentConfirmationRequest request;
        try {
            request = json.readValue(rawBody, PaymentConfirmationRequest.class);
        } catch (IOException ex) {
            throw new TicketValidationException("JSON de confirmación inválido");
        }
        if (request == null) throw new TicketValidationException("Solicitud de pago requerida");
        EmissionResult result = service.emit(request);
        return ResponseEntity.status(result.getReplayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }
}
