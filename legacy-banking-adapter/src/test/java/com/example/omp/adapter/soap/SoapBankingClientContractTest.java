package com.example.omp.adapter.soap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.ws.test.client.RequestMatchers.payload;
import static org.springframework.ws.test.client.ResponseCreators.withPayload;

import com.example.omp.adapter.dto.TransferRequest;
import com.example.omp.adapter.dto.TransferResponse;
import com.example.omp.adapter.port.BankingException;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.test.client.MockWebServiceServer;
import org.springframework.xml.transform.StringSource;

class SoapBankingClientContractTest {

    @ParameterizedTest
    @CsvSource({
        "INSUFFICIENT_FUNDS,INSUFFICIENT_FUNDS",
        "UPSTREAM_ERROR,UPSTREAM_ERROR",
        "UNKNOWN_FUTURE_CODE,UPSTREAM_ERROR"
    })
    void mapsStructuredFaultsWithoutLeakingReason(
            String backendCode, BankingException.Code expected) {
        mockServer
                .expect(
                        payload(
                                new StringSource(
                                        """
                <GetAccountBalanceRequest xmlns="http://legacybank.example.com/ws">
                  <accountNumber>ACC-1</accountNumber>
                </GetAccountBalanceRequest>
                """)))
                .andRespond(
                        org.springframework.ws.test.client.ResponseCreators.withSoapEnvelope(
                                new StringSource(
                                        """
                        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                          <soap:Body><soap:Fault>
                            <faultcode>soap:Server</faultcode><faultstring>password=secret</faultstring>
                            <detail><BankingFault xmlns="http://legacybank.example.com/ws"><code>%s</code></BankingFault></detail>
                          </soap:Fault></soap:Body>
                        </soap:Envelope>
                        """
                                                .formatted(backendCode))));
        assertThatThrownBy(() -> bankingClient.getBalance("ACC-1"))
                .isInstanceOfSatisfying(
                        BankingException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo(expected))
                .hasMessageNotContaining("secret");
        mockServer.verify();
    }

    private MockWebServiceServer mockServer;
    private SoapBankingClient bankingClient;

    @BeforeEach
    void setUp() throws Exception {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setContextPath("com.omp.bank.soap");
        marshaller.afterPropertiesSet();

        WebServiceTemplate template = new WebServiceTemplate();
        template.setDefaultUri("https://bank.test/ws");
        template.setMarshaller(marshaller);
        template.setUnmarshaller(marshaller);
        mockServer = MockWebServiceServer.createServer(template);
        bankingClient = new SoapBankingClient(template);
    }

    @Test
    void transfer_sends_the_wsdl_request_and_maps_the_response() {
        mockServer
                .expect(
                        payload(
                                new StringSource(
                                        """
                                        <TransferFundsRequest xmlns="http://legacybank.example.com/ws">
                                          <fromAccount>ACC-1</fromAccount>
                                          <toAccount>ACC-2</toAccount>
                                          <amount>12.34</amount>
                                          <currency>USD</currency>
                                          <reference>order-42</reference>
                                        </TransferFundsRequest>
                                        """)))
                .andExpect(
                        (uri, message) ->
                                assertThat(
                                                ((org.springframework.ws.soap.SoapMessage) message)
                                                        .getSoapAction()
                                                        .replace(String.valueOf((char) 34), ""))
                                        .isEqualTo(
                                                "http://legacybank.example.com/ws/TransferFunds"))
                .andRespond(
                        withPayload(
                                new StringSource(
                                        """
                                        <TransferFundsResponse xmlns="http://legacybank.example.com/ws">
                                          <transactionId>txn-42</transactionId>
                                          <status>COMPLETED</status>
                                          <timestamp>2026-10-21T12:00:00Z</timestamp>
                                          <newBalance>87.66</newBalance>
                                        </TransferFundsResponse>
                                        """)));

        TransferResponse response =
                bankingClient.transfer(
                        new TransferRequest(
                                "ACC-1", "ACC-2", new BigDecimal("12.34"), "USD", "order-42"));

        assertThat(response.transactionId()).isEqualTo("txn-42");
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.newBalance()).isEqualByComparingTo("87.66");
        mockServer.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "timeout,UPSTREAM_TIMEOUT",
        "connect,UPSTREAM_UNAVAILABLE",
        "tls,UPSTREAM_AUTHENTICATION",
        "wrapped-tls,UPSTREAM_AUTHENTICATION",
        "malformed,UPSTREAM_ERROR"
    })
    void redactsAndClassifiesTransportFailures(String type, BankingException.Code expected) {
        mockServer
                .expect((uri, message) -> {})
                .andRespond(
                        (uri, message, factory) -> {
                            switch (type) {
                                case "timeout" ->
                                        throw new java.net.SocketTimeoutException(
                                                "password=private");
                                case "connect" ->
                                        throw new java.net.ConnectException("password=private");
                                case "tls" ->
                                        throw new javax.net.ssl.SSLHandshakeException(
                                                "password=private");
                                case "wrapped-tls" ->
                                        throw new java.io.IOException(
                                                "private",
                                                new javax.net.ssl.SSLHandshakeException(
                                                        "password=private"));
                                default -> throw new IllegalArgumentException("password=private");
                            }
                        });
        assertThatThrownBy(() -> bankingClient.getBalance("ACC-1"))
                .isInstanceOfSatisfying(
                        BankingException.class, ex -> assertThat(ex.getCode()).isEqualTo(expected))
                .hasMessageNotContaining("private");
        mockServer.verify();
    }

    @Test
    void securityFaultHasAStableCodeWithoutExposingSoapReason() {
        mockServer
                .expect((uri, message) -> {})
                .andRespond(
                        org.springframework.ws.test.client.ResponseCreators.withSoapEnvelope(
                                new StringSource(
                                        """
          <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"><s:Body><s:Fault><faultcode>wsse:FailedAuthentication</faultcode><faultstring>password=private</faultstring></s:Fault></s:Body></s:Envelope>
          """)));
        assertThatThrownBy(() -> bankingClient.getBalance("ACC-1"))
                .isInstanceOfSatisfying(
                        BankingException.class,
                        ex ->
                                assertThat(ex.getCode())
                                        .isEqualTo(BankingException.Code.UPSTREAM_AUTHENTICATION))
                .hasMessageNotContaining("private");
        mockServer.verify();
    }
}
