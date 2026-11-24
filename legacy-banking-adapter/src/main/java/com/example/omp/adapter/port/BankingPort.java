package com.example.omp.adapter.port;

import com.example.omp.adapter.dto.BalanceResponse;
import com.example.omp.adapter.dto.TransferRequest;
import com.example.omp.adapter.dto.TransferResponse;

public interface BankingPort {

    TransferResponse transfer(TransferRequest request, String requestId);

    default TransferResponse transfer(TransferRequest request) {
        return transfer(request, java.util.UUID.randomUUID().toString());
    }

    BalanceResponse getBalance(String accountNumber);
}
