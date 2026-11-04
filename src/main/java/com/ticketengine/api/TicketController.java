package com.ticketengine.api;

import com.ticketengine.application.TicketEmissionService;
import com.ticketengine.crypto.TicketCryptoService;
import com.ticketengine.dto.EmissionResult;
import com.ticketengine.dto.PaymentConfirmationRequest;
import com.ticketengine.dto.SystemResponse;
import com.ticketengine.dto.TicketPage;
import com.ticketengine.dto.TicketResponse;
import com.ticketengine.dto.VerificationRequest;
import com.ticketengine.dto.VerificationResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class TicketController {
    private final TicketEmissionService service;
    private final TicketCryptoService crypto;
    private final boolean demoEnabled;

    public TicketController(TicketEmissionService service, TicketCryptoService crypto,
                            @Value("${ticket.demo-enabled:false}") boolean demoEnabled) {
        this.service = service;
        this.crypto = crypto;
        this.demoEnabled = demoEnabled;
    }

    @PostMapping("/api/demo/tickets")
    public ResponseEntity<EmissionResult> demo(@Valid @RequestBody PaymentConfirmationRequest request) {
        if (!demoEnabled) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sandbox deshabilitado");
        EmissionResult result = service.emit(request);
        return ResponseEntity.status(result.getReplayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }

    @GetMapping("/api/tickets")
    public TicketPage list(@RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset) {
        return service.list(limit, offset);
    }

    @GetMapping("/api/tickets/{ticketId}")
    public TicketResponse get(@PathVariable UUID ticketId) {
        return service.get(ticketId);
    }

    @GetMapping("/api/system")
    public SystemResponse system() {
        return service.system();
    }

    @GetMapping("/public-key")
    public Map<String, Object> publicKey() {
        return crypto.publicJwk();
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return Map.of("keys", List.of(crypto.publicJwk()));
    }

    @PostMapping("/verify")
    public VerificationResponse verify(@Valid @RequestBody VerificationRequest request) {
        return service.verify(request.getToken());
    }
}
