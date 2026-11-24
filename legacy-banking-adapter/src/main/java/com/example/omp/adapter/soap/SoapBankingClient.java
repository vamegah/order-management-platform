package com.example.omp.adapter.soap;

import com.example.omp.adapter.dto.BalanceResponse;
import com.example.omp.adapter.dto.TransferRequest;
import com.example.omp.adapter.dto.TransferResponse;
import com.example.omp.adapter.port.BankingException;
import com.example.omp.adapter.port.BankingPort;
import com.omp.bank.soap.*;
import java.time.Instant;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.namespace.QName;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.w3c.dom.Document;

@Component
public class SoapBankingClient implements BankingPort {

    private static final Logger log = LoggerFactory.getLogger(SoapBankingClient.class);

    private final WebServiceTemplate webServiceTemplate;
    private final com.example.omp.adapter.config.BankingResilience resilience;

    public SoapBankingClient(WebServiceTemplate webServiceTemplate) {
        this(webServiceTemplate, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SoapBankingClient(
            WebServiceTemplate webServiceTemplate,
            com.example.omp.adapter.config.BankingResilience resilience) {
        this.webServiceTemplate = webServiceTemplate;
        this.resilience = resilience;
    }

    @Override
    public TransferResponse transfer(TransferRequest request, String requestId) {
        return execute("transfer", () -> transferCall(request, requestId));
    }

    private TransferResponse transferCall(TransferRequest request, String requestId) {
        TransferFundsRequest soapRequest = new TransferFundsRequest();
        soapRequest.setFromAccount(request.fromAccount());
        soapRequest.setToAccount(request.toAccount());
        soapRequest.setAmount(request.amount());
        soapRequest.setCurrency(request.currency());
        soapRequest.setReference(request.reference());

        try {
            TransferFundsResponse soapResponse =
                    (TransferFundsResponse)
                            webServiceTemplate.marshalSendAndReceive(
                                    soapRequest,
                                    message -> {
                                        ((org.springframework.ws.soap.SoapMessage) message)
                                                .setSoapAction(
                                                        "http://legacybank.example.com/ws/TransferFunds");
                                        var header =
                                                ((org.springframework.ws.soap.SoapMessage) message)
                                                        .getSoapHeader();
                                        var identity =
                                                header.addHeaderElement(
                                                        new QName(
                                                                "urn:omp:banking:request:v1",
                                                                "RequestId"));
                                        identity.setMustUnderstand(true);
                                        identity.setText(requestId);
                                    });

            if (soapResponse == null
                    || soapResponse.getTransactionId() == null
                    || soapResponse.getTransactionId().isBlank()
                    || !"COMPLETED".equals(soapResponse.getStatus())
                    || soapResponse.getNewBalance() == null
                    || soapResponse.getNewBalance().signum() < 0)
                throw new IllegalArgumentException("Invalid bank result");
            return new TransferResponse(
                    soapResponse.getTransactionId(),
                    soapResponse.getStatus(),
                    toInstant(soapResponse.getTimestamp()),
                    soapResponse.getNewBalance());
        } catch (SoapFaultClientException ex) {
            throw mapFault(ex);
        } catch (Exception ex) {
            throw transportFailure(ex);
        }
    }

    @Override
    public BalanceResponse getBalance(String accountNumber) {
        return execute("balance", () -> balanceCall(accountNumber));
    }

    private BalanceResponse balanceCall(String accountNumber) {
        GetAccountBalanceRequest soapRequest = new GetAccountBalanceRequest();
        soapRequest.setAccountNumber(accountNumber);

        try {
            GetAccountBalanceResponse soapResponse =
                    (GetAccountBalanceResponse)
                            webServiceTemplate.marshalSendAndReceive(
                                    soapRequest,
                                    new org.springframework.ws.soap.client.core.SoapActionCallback(
                                            "http://legacybank.example.com/ws/GetAccountBalance"));

            return new BalanceResponse(
                    soapResponse.getAccountNumber(),
                    soapResponse.getBalance(),
                    soapResponse.getCurrency(),
                    toInstant(soapResponse.getTimestamp()));
        } catch (SoapFaultClientException ex) {
            throw mapFault(ex);
        } catch (Exception ex) {
            throw transportFailure(ex);
        }
    }

    // ---------- helpers ----------

    /** Maps only structured codes; raw SOAP reasons never cross the REST boundary. */
    private BankingException mapFault(SoapFaultClientException ex) {
        var faultCode = ex.getFaultCode();
        BankingException.Code code =
                faultCode != null
                                && faultCode
                                        .getNamespaceURI()
                                        .equals(
                                                "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd")
                        ? BankingException.Code.UPSTREAM_AUTHENTICATION
                        : BankingException.Code.UPSTREAM_ERROR;
        var detail = ex.getSoapFault().getFaultDetail();
        if (detail != null) {
            var entries = detail.getDetailEntries();
            while (entries.hasNext()) {
                var entry = entries.next();
                if (!entry.getName()
                        .equals(new QName("http://legacybank.example.com/ws", "BankingFault")))
                    continue;
                try {
                    var result = new DOMResult();
                    TransformerFactory.newInstance()
                            .newTransformer()
                            .transform(entry.getSource(), result);
                    var document = (Document) result.getNode();
                    var codes =
                            document.getElementsByTagNameNS(
                                    "http://legacybank.example.com/ws", "code");
                    if (codes.getLength() == 1)
                        code = BankingException.Code.valueOf(codes.item(0).getTextContent());
                } catch (Exception invalidDetail) {
                    code = BankingException.Code.UPSTREAM_ERROR;
                }
            }
        }
        log.warn("SOAP bank request failed: code={}", code);
        return new BankingException(code, "Bank request failed: " + code.name(), ex);
    }

    private <T> T execute(String operation, java.util.function.Supplier<T> call) {
        return resilience == null ? call.get() : resilience.execute(operation, call);
    }

    private BankingException transportFailure(Exception ex) {
        boolean timeout = false, io = false;
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof javax.net.ssl.SSLException)
                return new BankingException(
                        BankingException.Code.UPSTREAM_AUTHENTICATION, "Bank TLS rejected", ex);
            timeout |= cause instanceof java.net.SocketTimeoutException;
            io |= cause instanceof java.io.IOException;
        }
        if (timeout)
            return new com.example.omp.adapter.port.BankTransportException(
                    BankingException.Code.UPSTREAM_TIMEOUT, ex);
        if (io)
            return new com.example.omp.adapter.port.BankTransportException(
                    BankingException.Code.UPSTREAM_UNAVAILABLE, ex);
        return new BankingException(
                BankingException.Code.UPSTREAM_ERROR, "Invalid bank response", ex);
    }

    private Instant toInstant(XMLGregorianCalendar xml) {
        if (xml == null) throw new IllegalArgumentException("Missing bank timestamp");
        return xml.toGregorianCalendar().toInstant();
    }
}
