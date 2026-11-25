package com.ticketengine.api;

import com.ticketengine.application.WalletService;
import com.ticketengine.dto.WalletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Consulta del Módulo 5 (Wallet); requiere el Bearer ticket.wallet-token. */
@RestController
public class WalletController {
    private final WalletService wallet;

    public WalletController(WalletService wallet) {
        this.wallet = wallet;
    }

    @GetMapping("/api/wallet/{buyerId}/tickets")
    public WalletResponse tickets(@PathVariable String buyerId) {
        return wallet.ticketsFor(buyerId);
    }
}
