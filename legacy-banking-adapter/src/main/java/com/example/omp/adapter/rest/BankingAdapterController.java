package com.example.omp.adapter.rest;

import com.example.omp.adapter.dto.BalanceResponse;
import com.example.omp.adapter.dto.TransferRequest;
import com.example.omp.adapter.dto.TransferResponse;
import com.example.omp.adapter.idempotency.IdempotencyStore;
import com.example.omp.adapter.port.BankingPort;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/banking")
@org.springframework.security.access.prepost.PreAuthorize("hasRole('admin')")
public class BankingAdapterController {
    private final BankingPort bankingPort;
    private final IdempotencyStore idempotencyStore;

    public BankingAdapterController(BankingPort bankingPort, IdempotencyStore idempotencyStore) {
        this.bankingPort = bankingPort;
        this.idempotencyStore = idempotencyStore;
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> transfer(
            @Valid @RequestBody TransferRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (request.amount().stripTrailingZeros().scale() > 2)
            throw new com.example.omp.adapter.port.BankingException(
                    com.example.omp.adapter.port.BankingException.Code.INVALID_REQUEST,
                    "Invalid monetary precision");
        var authentication =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        var jwt = (org.springframework.security.oauth2.jwt.Jwt) authentication.getPrincipal();
        if (jwt.getSubject() == null || jwt.getSubject().isBlank())
            throw new org.springframework.security.access.AccessDeniedException(
                    "Verified subject required");
        String scopedKey =
                idempotencyKey == null
                        ? null
                        : hash(
                                jwt.getClaimAsString("iss")
                                        + "\n"
                                        + jwt.getSubject()
                                        + "\n"
                                        + idempotencyKey);
        String requestHash =
                hash(
                        String.join(
                                "\n",
                                request.fromAccount(),
                                request.toAccount(),
                                request.amount().stripTrailingZeros().toString(),
                                request.currency(),
                                request.reference()));
        return ResponseEntity.ok(
                idempotencyStore.execute(
                        scopedKey,
                        requestHash,
                        id -> {
                            if (request.amount().compareTo(new java.math.BigDecimal("10000")) > 0)
                                throw new com.example.omp.adapter.port.BankingException(
                                        com.example.omp.adapter.port.BankingException.Code
                                                .DAILY_LIMIT_EXCEEDED,
                                        "Limit exceeded");
                            return bankingPort.transfer(request, id);
                        }));
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            value.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @GetMapping("/accounts/{accountNumber}/balance")
    public BalanceResponse balance(@PathVariable String accountNumber) {
        if (!accountNumber.matches("ACC-[0-9]{1,30}"))
            throw new com.example.omp.adapter.port.BankingException(
                    com.example.omp.adapter.port.BankingException.Code.INVALID_ACCOUNT_FORMAT,
                    "Invalid account");
        return bankingPort.getBalance(accountNumber);
    }
}
