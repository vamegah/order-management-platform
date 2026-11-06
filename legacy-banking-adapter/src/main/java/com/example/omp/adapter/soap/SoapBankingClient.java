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

    public SoapBankingClient(WebServiceTemplate webServiceTemplate) {
        this.webServiceTemplate = webServiceTemplate;
    }

    @Override
    public TransferResponse transfer(TransferRequest request) {
        TransferFundsRequest soapRequest = new TransferFundsRequest();
        soapRequest.setFromAccount(request.fromAccount());
        soapRequest.setToAccount(request.toAccount());
        soapRequest.setAmount(request.amount());
        soapRequest.setCurrency(request.currency());
        soapRequest.setReference(request.reference());

        try {
            TransferFundsResponse soapResponse =
                    (TransferFundsResponse) webServiceTemplate.marshalSendAndReceive(soapRequest);

            return new TransferResponse(
                    soapResponse.getTransactionId(),
                    soapResponse.getStatus(),
                    toInstant(soapResponse.getTimestamp()),
                    soapResponse.getNewBalance());
        } catch (SoapFaultClientException ex) {
            throw mapFault(ex);
        } catch (Exception ex) {
            log.error("Unexpected SOAP call failure", ex);
            throw new BankingException(
                    BankingException.Code.UPSTREAM_ERROR, "Bank communication failed", ex);
        }
    }

    @Override
    public BalanceResponse getBalance(String accountNumber) {
        GetAccountBalanceRequest soapRequest = new GetAccountBalanceRequest();
        soapRequest.setAccountNumber(accountNumber);

        try {
            GetAccountBalanceResponse soapResponse =
                    (GetAccountBalanceResponse)
                            webServiceTemplate.marshalSendAndReceive(soapRequest);

            return new BalanceResponse(
                    soapResponse.getAccountNumber(),
                    soapResponse.getBalance(),
                    soapResponse.getCurrency(),
                    toInstant(soapResponse.getTimestamp()));
        } catch (SoapFaultClientException ex) {
            throw mapFault(ex);
        } catch (Exception ex) {
            log.error("Unexpected SOAP call failure", ex);
            throw new BankingException(
                    BankingException.Code.UPSTREAM_ERROR, "Bank communication failed", ex);
        }
    }

    // ---------- helpers ----------

    /** Maps only structured codes; raw SOAP reasons never cross the REST boundary. */
    private BankingException mapFault(SoapFaultClientException ex) {
        BankingException.Code code = BankingException.Code.UPSTREAM_ERROR;
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

    private Instant toInstant(XMLGregorianCalendar xml) {
        return xml == null ? Instant.now() : xml.toGregorianCalendar().toInstant();
    }
}
