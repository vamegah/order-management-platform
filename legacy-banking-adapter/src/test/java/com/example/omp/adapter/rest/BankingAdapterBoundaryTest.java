package com.example.omp.adapter.rest;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.ws.test.client.RequestMatchers.payload;
import static org.springframework.ws.test.client.ResponseCreators.withPayload;

import com.example.omp.adapter.LegacyBankingAdapterApplication;
import com.example.omp.adapter.dto.BalanceResponse;
import com.example.omp.adapter.dto.TransferRequest;
import com.example.omp.adapter.dto.TransferResponse;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.test.client.MockWebServiceServer;
import org.springframework.xml.transform.StringSource;

/** Real REST requests through production beans/JAXB/security to a controlled SOAP transport. */
@SpringBootTest(
        classes = LegacyBankingAdapterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "bank.ws.url=http://bank.invalid/ws",
            "bank.ws.username=boundary-client",
            "bank.ws.password=boundary-test-password"
        })
class BankingAdapterBoundaryTest {
    @Autowired private TestRestTemplate http;
    @Autowired private WebServiceTemplate soap;
    private MockWebServiceServer bank;

    @BeforeEach
    void prepare() {
        bank = MockWebServiceServer.createServer(soap);
    }

    private TransferRequest transfer(String amount) {
        return new TransferRequest("ACC-1", "ACC-2", new BigDecimal(amount), "USD", "boundary");
    }

    private HttpEntity<TransferRequest> request(String key, String amount) {
        var headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        return new HttpEntity<>(transfer(amount), headers);
    }

    @Test
    void authenticatedTransferRetryUsesCachedResultAndChangedPayloadReturnsConflict() {
        bank.expect(
                        (uri, message) -> {
                            var header = ((SoapMessage) message).getSoapHeader();
                            var security =
                                    header.examineHeaderElements(
                                            new javax.xml.namespace.QName(
                                                    "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd",
                                                    "Security"));
                            assertThat(security.hasNext()).isTrue();
                        })
                .andExpect(
                        payload(
                                new StringSource(
                                        """
                    <TransferFundsRequest xmlns="http://legacybank.example.com/ws">
                      <fromAccount>ACC-1</fromAccount><toAccount>ACC-2</toAccount>
                      <amount>12.34</amount><currency>USD</currency><reference>boundary</reference>
                    </TransferFundsRequest>
                    """)))
                .andRespond(
                        withPayload(
                                new StringSource(
                                        """
                    <TransferFundsResponse xmlns="http://legacybank.example.com/ws">
                      <transactionId>boundary-txn</transactionId><status>COMPLETED</status>
                      <timestamp>2026-10-29T12:00:00Z</timestamp><newBalance>9987.66</newBalance>
                    </TransferFundsResponse>
                    """)));
        var first =
                http.postForEntity(
                        "/api/v1/banking/transfers",
                        request("boundary-key", "12.34"),
                        TransferResponse.class);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getBody().transactionId()).isEqualTo("boundary-txn");
        assertThat(first.getBody().newBalance()).isEqualByComparingTo("9987.66");
        var retry =
                http.postForEntity(
                        "/api/v1/banking/transfers",
                        request("boundary-key", "12.34"),
                        TransferResponse.class);
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        var conflict =
                http.postForEntity(
                        "/api/v1/banking/transfers",
                        request("boundary-key", "20.00"),
                        ProblemDetail.class);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(conflict.getBody().getStatus()).isEqualTo(409);
        assertThat(conflict.getBody().getType().toString())
                .isEqualTo("urn:problem:idempotency-conflict");
        bank.verify();
    }

    @Test
    void balanceMapsSoapPayloadAndTimestampToRest() {
        bank.expect(
                        payload(
                                new StringSource(
                                        """
                    <GetAccountBalanceRequest xmlns="http://legacybank.example.com/ws">
                      <accountNumber>ACC-1</accountNumber>
                    </GetAccountBalanceRequest>
                    """)))
                .andRespond(
                        withPayload(
                                new StringSource(
                                        """
                    <GetAccountBalanceResponse xmlns="http://legacybank.example.com/ws">
                      <accountNumber>ACC-1</accountNumber><balance>10000.00</balance>
                      <currency>USD</currency><timestamp>2026-10-29T12:00:00Z</timestamp>
                    </GetAccountBalanceResponse>
                    """)));
        var result =
                http.getForEntity("/api/v1/banking/accounts/ACC-1/balance", BalanceResponse.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody().accountNumber()).isEqualTo("ACC-1");
        assertThat(result.getBody().balance()).isEqualByComparingTo("10000.00");
        assertThat(result.getBody().currency()).isEqualTo("USD");
        assertThat(result.getBody().timestamp())
                .isEqualTo(java.time.Instant.parse("2026-10-29T12:00:00Z"));
        bank.verify();
    }

    @Test
    void invalidTransferFailsBeforeAnySoapCall() {
        var result =
                http.postForEntity("/api/v1/banking/transfers", transfer("0.00"), String.class);
        assertThat(result.getStatusCode().value()).isEqualTo(400);
        bank.verify();
    }
}
