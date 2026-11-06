package com.example.omp.bank.endpoint;

import com.example.omp.bank.service.BankingService;
import com.omp.bank.soap.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

@Endpoint
public class BankingEndpoint {

    private static final String NS = "http://legacybank.example.com/ws";

    private final BankingService bankingService;

    public BankingEndpoint(BankingService bankingService) {
        this.bankingService = bankingService;
    }

    @PayloadRoot(namespace = NS, localPart = "TransferFundsRequest")
    @ResponsePayload
    public TransferFundsResponse transferFunds(@RequestPayload TransferFundsRequest request) {

        simulateLegacyLatency();

        BankingService.TransferResult result =
                bankingService.transfer(
                        request.getFromAccount(),
                        request.getToAccount(),
                        request.getAmount(),
                        request.getCurrency(),
                        request.getReference());

        TransferFundsResponse response = new TransferFundsResponse();
        response.setTransactionId(result.transactionId());
        response.setStatus(result.status());
        response.setTimestamp(toXml(result.timestamp()));
        response.setNewBalance(result.newBalance());
        return response;
    }

    @PayloadRoot(namespace = NS, localPart = "GetAccountBalanceRequest")
    @ResponsePayload
    public GetAccountBalanceResponse getAccountBalance(
            @RequestPayload GetAccountBalanceRequest request) {

        simulateLegacyLatency();

        BankingService.BalanceResult result = bankingService.getBalance(request.getAccountNumber());

        GetAccountBalanceResponse response = new GetAccountBalanceResponse();
        response.setAccountNumber(result.accountNumber());
        response.setBalance(result.balance());
        response.setCurrency(result.currency());
        response.setTimestamp(toXml(result.timestamp()));
        return response;
    }

    @PayloadRoot(namespace = NS, localPart = "ReverseTransactionRequest")
    @ResponsePayload
    public ReverseTransactionResponse reverseTransaction(
            @RequestPayload ReverseTransactionRequest request) {

        simulateLegacyLatency();

        BankingService.ReverseResult result =
                bankingService.reverse(request.getTransactionId(), request.getReason());

        ReverseTransactionResponse response = new ReverseTransactionResponse();
        response.setTransactionId(result.transactionId());
        response.setStatus(result.status());
        response.setTimestamp(toXml(result.timestamp()));
        return response;
    }

    // ---------- helpers ----------

    private void simulateLegacyLatency() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private XMLGregorianCalendar toXml(Instant instant) {
        try {
            ZonedDateTime zdt = instant.atZone(ZoneOffset.UTC);
            return DatatypeFactory.newInstance().newXMLGregorianCalendar(zdt.toString());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to convert timestamp", e);
        }
    }
}
