package com.example.omp.adapter.port;

import com.example.omp.adapter.dto.BalanceResponse;
import com.example.omp.adapter.dto.TransferRequest;
import com.example.omp.adapter.dto.TransferResponse;

public interface BankingPort {

    TransferResponse transfer(TransferRequest request);

    BalanceResponse getBalance(String accountNumber);
}
