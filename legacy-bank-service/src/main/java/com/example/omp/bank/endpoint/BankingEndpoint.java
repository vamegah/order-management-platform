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
    private final com.example.omp.bank.service.BankFailureSimulator simulator;

    public BankingEndpoint(
            BankingService bankingService,
            com.example.omp.bank.service.BankFailureSimulator simulator) {
        this.bankingService = bankingService;
        this.simulator = simulator;
    }

    @PayloadRoot(namespace = NS, localPart = "TransferFundsRequest")
    @ResponsePayload
    public TransferFundsResponse transferFunds(
            @RequestPayload TransferFundsRequest request,
            @org.springframework.ws.soap.server.endpoint.annotation.SoapHeader(
                            "{urn:omp:banking:request:v1}RequestId")
                    org.springframework.ws.soap.SoapHeaderElement requestId,
            @org.springframework.ws.soap.server.endpoint.annotation.SoapHeader(
                            "{http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd}Security")
                    org.springframework.ws.soap.SoapHeaderElement security) {

        var failure = simulator.next();
        failure.before();

        BankingService.TransferResult result =
                bankingService.transfer(
                        request.getFromAccount(),
                        request.getToAccount(),
                        request.getAmount(),
                        request.getCurrency(),
                        request.getReference(),
                        identity(requestId, security));
        failure.after();

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

        var failure = simulator.next();
        failure.before();

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

        var failure = simulator.next();
        failure.before();

        BankingService.ReverseResult result =
                bankingService.reverse(request.getTransactionId(), request.getReason());

        ReverseTransactionResponse response = new ReverseTransactionResponse();
        response.setTransactionId(result.transactionId());
        response.setStatus(result.status());
        response.setTimestamp(toXml(result.timestamp()));
        return response;
    }

    // ---------- helpers ----------

    private String identity(
            org.springframework.ws.soap.SoapHeaderElement requestId,
            org.springframework.ws.soap.SoapHeaderElement security) {
        if (requestId == null) return "legacy:" + java.util.UUID.randomUUID();
        try {
            var idResult = new javax.xml.transform.dom.DOMResult();
            javax.xml.transform.TransformerFactory.newInstance()
                    .newTransformer()
                    .transform(requestId.getSource(), idResult);
            String id =
                    ((org.w3c.dom.Document) idResult.getNode())
                            .getDocumentElement()
                            .getTextContent();
            if (!id.matches("[A-Za-z0-9._:-]{1,128}")) throw new IllegalArgumentException();
            var result = new javax.xml.transform.dom.DOMResult();
            javax.xml.transform.TransformerFactory.newInstance()
                    .newTransformer()
                    .transform(security.getSource(), result);
            var users =
                    ((org.w3c.dom.Document) result.getNode())
                            .getElementsByTagNameNS(
                                    "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd",
                                    "Username");
            if (users.getLength() != 1) throw new IllegalArgumentException();
            // Rotation identities share one logical platform ledger namespace. REST scopes callers
            // upstream.
            return "platform:" + id;
        } catch (Exception ex) {
            throw new com.example.omp.bank.service.BankingFaultException(
                    "INVALID_REQUEST", "Invalid request identity");
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
