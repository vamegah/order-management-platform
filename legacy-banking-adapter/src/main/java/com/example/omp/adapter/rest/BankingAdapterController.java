package com.example.omp.adapter.rest;

import com.example.omp.adapter.dto.BalanceResponse;
import com.example.omp.adapter.dto.TransferRequest;
import com.example.omp.adapter.dto.TransferResponse;
import com.example.omp.adapter.idempotency.IdempotencyStore;
import com.example.omp.adapter.port.BankingPort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/banking")
public class BankingAdapterController {
    private final BankingPort bankingPort;
    private final IdempotencyStore idempotencyStore;

    public BankingAdapterController(BankingPort bankingPort, IdempotencyStore idempotencyStore) {
        this.bankingPort = bankingPort;
        this.idempotencyStore = idempotencyStore;
    }

    @PostMapping("/transfers")
    @CircuitBreaker(name = "banking")
    public ResponseEntity<TransferResponse> transfer(
            @Valid @RequestBody TransferRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        String requestHash = request.toString();
        var cached = idempotencyStore.lookup(idempotencyKey, requestHash);
        if (cached.isPresent()) return ResponseEntity.ok((TransferResponse) cached.get());
        TransferResponse response = bankingPort.transfer(request);
        idempotencyStore.store(idempotencyKey, requestHash, response);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/accounts/{accountNumber}/balance")
    @CircuitBreaker(name = "banking")
    public BalanceResponse balance(@PathVariable String accountNumber) {
        return bankingPort.getBalance(accountNumber);
    }
}
