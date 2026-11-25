package com.example.omp.bank.endpoint;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.bank.LegacyBankApplication;
import com.omp.bank.soap.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.springframework.ws.soap.security.wss4j2.Wss4jSecurityInterceptor;

/** Exercises application scanning, real HTTP, JAXB, WS-Security and the bank ledger together. */
@SpringBootTest(
        classes = LegacyBankApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.ssl.enabled=false",
            "bank.security.username=boundary-client",
            "bank.security.password=boundary-test-password"
        })
class BankingSoapBoundaryTest {
    @LocalServerPort private int port;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate db;

    @BeforeEach
    void reset() {
        db.update("DELETE FROM bank_transfer");
        db.update("DELETE FROM bank_nonce");
        db.update("DELETE FROM bank_account");
        db.update(
                "INSERT INTO bank_account VALUES ('ACC-1','USD',10000),('ACC-2','USD',5000),('ACC-3','USD',25000)");
    }

    private WebServiceTemplate client(String password) throws Exception {
        return client("boundary-client", password, "UsernameToken Timestamp", "PasswordText");
    }

    private WebServiceTemplate client(String username, String password, String actions, String type)
            throws Exception {
        var marshaller = new Jaxb2Marshaller();
        marshaller.setContextPath("com.omp.bank.soap");
        marshaller.afterPropertiesSet();
        var template = new WebServiceTemplate(marshaller);
        template.setDefaultUri("http://localhost:" + port + "/ws");
        if (password != null) {
            var security = new Wss4jSecurityInterceptor();
            security.setSecurementActions(actions);
            security.setSecurementUsername(username);
            security.setSecurementPassword(password);
            security.setSecurementPasswordType(type);
            security.setSecurementUsernameTokenNonce(true);
            security.setSecurementUsernameTokenCreated(true);
            security.afterPropertiesSet();
            template.setInterceptors(
                    new org.springframework.ws.client.support.interceptor.ClientInterceptor[] {
                        security
                    });
        }
        return template;
    }

    private GetAccountBalanceRequest balanceRequest(String account) {
        var request = new GetAccountBalanceRequest();
        request.setAccountNumber(account);
        return request;
    }

    @Test
    void authenticatedTransferBalanceAndReversalPreserveTheLedger() throws Exception {
        var template = client("boundary-test-password");
        var transfer = new TransferFundsRequest();
        transfer.setFromAccount("ACC-1");
        transfer.setToAccount("ACC-2");
        transfer.setAmount(new BigDecimal("12.34"));
        transfer.setCurrency("USD");
        transfer.setReference("boundary-reference");
        var paid = (TransferFundsResponse) template.marshalSendAndReceive(transfer);
        assertThat(paid.getStatus()).isEqualTo("COMPLETED");
        assertThat(paid.getNewBalance()).isEqualByComparingTo("9987.66");
        assertThat(paid.getTimestamp()).isNotNull();
        var balance =
                (GetAccountBalanceResponse) template.marshalSendAndReceive(balanceRequest("ACC-2"));
        assertThat(balance.getAccountNumber()).isEqualTo("ACC-2");
        assertThat(balance.getBalance()).isEqualByComparingTo("5012.34");
        assertThat(balance.getCurrency()).isEqualTo("USD");
        assertThat(balance.getTimestamp()).isNotNull();

        var reverse = new ReverseTransactionRequest();
        reverse.setTransactionId(paid.getTransactionId());
        reverse.setReason("Boundary test refund");
        var reversed = (ReverseTransactionResponse) template.marshalSendAndReceive(reverse);
        assertThat(reversed.getTransactionId()).isEqualTo(paid.getTransactionId());
        assertThat(reversed.getStatus()).isEqualTo("REVERSED");
        assertThat(reversed.getTimestamp()).isNotNull();
        assertThat(
                        db.queryForObject(
                                "SELECT balance FROM bank_account WHERE account_number='ACC-1'",
                                java.math.BigDecimal.class))
                .isEqualByComparingTo("10000.00");
        assertThat(
                        db.queryForObject(
                                "SELECT balance FROM bank_account WHERE account_number='ACC-2'",
                                java.math.BigDecimal.class))
                .isEqualByComparingTo("5000.00");
        assertThatThrownBy(() -> template.marshalSendAndReceive(reverse))
                .isInstanceOf(SoapFaultClientException.class);
        assertThat(
                        db.queryForObject(
                                "SELECT balance FROM bank_account WHERE account_number='ACC-1'",
                                java.math.BigDecimal.class))
                .isEqualByComparingTo("10000.00");
    }

    @Test
    void missingAndInvalidCredentialsAreRejectedBeforeLedgerAccess() throws Exception {
        for (String password : new String[] {null, "incorrect-password"}) {
            var template = client(password);
            assertThatThrownBy(() -> template.marshalSendAndReceive(balanceRequest("ACC-1")))
                    .isInstanceOfSatisfying(
                            SoapFaultClientException.class,
                            fault -> {
                                assertThat(fault.getSoapFault().getFaultStringOrReason())
                                        .isEqualTo("Bank authentication rejected");
                                var rendered = new org.springframework.xml.transform.StringResult();
                                try {
                                    javax.xml.transform.TransformerFactory.newInstance()
                                            .newTransformer()
                                            .transform(
                                                    fault.getSoapFault()
                                                            .getFaultDetail()
                                                            .getSource(),
                                                    rendered);
                                } catch (Exception serializationFailure) {
                                    throw new AssertionError(serializationFailure);
                                }
                                assertThat(rendered.toString())
                                        .contains("UPSTREAM_AUTHENTICATION")
                                        .doesNotContain(
                                                "incorrect-password", "boundary-test-password");
                            });
        }
        assertThat(
                        db.queryForObject(
                                "SELECT balance FROM bank_account WHERE account_number='ACC-1'",
                                java.math.BigDecimal.class))
                .isEqualByComparingTo("10000.00");
    }

    @Test
    void digestTokensMissingTimestampsAndUnknownUsersAreRejected() throws Exception {
        var clients =
                java.util.List.of(
                        client(
                                "boundary-client",
                                "boundary-test-password",
                                "UsernameToken Timestamp",
                                "PasswordDigest"),
                        client(
                                "boundary-client",
                                "boundary-test-password",
                                "UsernameToken",
                                "PasswordText"),
                        client(
                                "unknown-client",
                                "boundary-test-password",
                                "UsernameToken Timestamp",
                                "PasswordText"));
        for (var template : clients) {
            assertThatThrownBy(() -> template.marshalSendAndReceive(balanceRequest("ACC-1")))
                    .isInstanceOf(SoapFaultClientException.class);
        }
        assertThat(
                        db.queryForObject(
                                "SELECT balance FROM bank_account WHERE account_number='ACC-1'",
                                java.math.BigDecimal.class))
                .isEqualByComparingTo("10000.00");
    }

    @Test
    void authenticatedBusinessFailureUsesStructuredFaultDetail() throws Exception {
        var template = client("boundary-test-password");
        assertThatThrownBy(() -> template.marshalSendAndReceive(balanceRequest("MISSING")))
                .isInstanceOfSatisfying(
                        SoapFaultClientException.class,
                        fault -> {
                            assertThat(fault.getSoapFault().getFaultDetail()).isNotNull();
                            var entry =
                                    fault.getSoapFault().getFaultDetail().getDetailEntries().next();
                            assertThat(entry.getName())
                                    .isEqualTo(
                                            new javax.xml.namespace.QName(
                                                    "http://legacybank.example.com/ws",
                                                    "BankingFault"));
                        });
    }

    @Test
    void applicationPublishesTheWsdlWithItsSoapOperations() throws Exception {
        var response =
                java.net.http.HttpClient.newHttpClient()
                        .send(
                                java.net.http.HttpRequest.newBuilder(
                                                java.net.URI.create(
                                                        "http://localhost:"
                                                                + port
                                                                + "/ws/banking.wsdl"))
                                        .GET()
                                        .build(),
                                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("TransferFunds", "GetAccountBalance", "ReverseTransaction");
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document =
                factory.newDocumentBuilder()
                        .parse(
                                new java.io.ByteArrayInputStream(
                                        response.body()
                                                .getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8)));
        var operations =
                document.getElementsByTagNameNS(
                        "http://schemas.xmlsoap.org/wsdl/soap/", "operation");
        assertThat(operations.getLength()).isEqualTo(3);
        for (int index = 0; index < operations.getLength(); index++) {
            var operation = (org.w3c.dom.Element) operations.item(index);
            String name = ((org.w3c.dom.Element) operation.getParentNode()).getAttribute("name");
            assertThat(operation.getAttribute("soapAction"))
                    .isEqualTo("http://legacybank.example.com/ws/" + name);
        }
        var faults = document.getElementsByTagNameNS("http://schemas.xmlsoap.org/wsdl/", "fault");
        assertThat(faults.getLength()).isEqualTo(2);
        var schemas = document.getElementsByTagNameNS("http://www.w3.org/2001/XMLSchema", "schema");
        assertThat(schemas.getLength()).isEqualTo(1);
        assertThat(((org.w3c.dom.Element) schemas.item(0)).getAttribute("targetNamespace"))
                .isEqualTo("http://legacybank.example.com/ws");
        assertThat(
                        document.getElementsByTagNameNS(
                                        "http://www.w3.org/2001/XMLSchema", "import")
                                .getLength())
                .isZero();
    }

    @Test
    void unsupportedMandatoryHeadersFailBeforeMoneyMovement() throws Exception {
        var template = client("boundary-test-password");
        var transfer = new TransferFundsRequest();
        transfer.setFromAccount("ACC-1");
        transfer.setToAccount("ACC-2");
        transfer.setAmount(java.math.BigDecimal.ONE);
        transfer.setCurrency("USD");
        transfer.setReference("mandatory-header");
        assertThatThrownBy(
                        () ->
                                template.marshalSendAndReceive(
                                        transfer,
                                        message -> {
                                            var header =
                                                    ((org.springframework.ws.soap.SoapMessage)
                                                                    message)
                                                            .getSoapHeader()
                                                            .addHeaderElement(
                                                                    new javax.xml.namespace.QName(
                                                                            "urn:unsupported:protocol",
                                                                            "RequestId"));
                                            header.setMustUnderstand(true);
                                            header.setText("key");
                                        }))
                .isInstanceOf(SoapFaultClientException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM bank_transfer", Integer.class)).isZero();
    }

    @Test
    void mandatoryDurableHeaderDeduplicatesTransfers() throws Exception {
        var template = client("boundary-test-password");
        var transfer = new TransferFundsRequest();
        transfer.setFromAccount("ACC-1");
        transfer.setToAccount("ACC-2");
        transfer.setAmount(java.math.BigDecimal.ONE);
        transfer.setCurrency("USD");
        transfer.setReference("mandatory-durable-header");
        org.springframework.ws.client.core.WebServiceMessageCallback callback =
                message -> {
                    var header =
                            ((org.springframework.ws.soap.SoapMessage) message)
                                    .getSoapHeader()
                                    .addHeaderElement(identityHeader());
                    header.setMustUnderstand(true);
                    header.setText("x".repeat(identityContract().get("maxLength").asInt()));
                };
        var first = (TransferFundsResponse) template.marshalSendAndReceive(transfer, callback);
        var second = (TransferFundsResponse) template.marshalSendAndReceive(transfer, callback);
        assertThat(second.getTransactionId()).isEqualTo(first.getTransactionId());
        assertThat(second.getTimestamp()).isEqualTo(first.getTimestamp());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM bank_transfer", Integer.class))
                .isEqualTo(1);
    }

    private com.fasterxml.jackson.databind.JsonNode identityContract() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(
                            java.nio.file.Path.of(
                                            "..",
                                            "contracts",
                                            "soap",
                                            "extensions",
                                            "request-identity-v1.json")
                                    .toFile());
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Published request identity unavailable", failure);
        }
    }

    private javax.xml.namespace.QName identityHeader() {
        var contract = identityContract();
        return new javax.xml.namespace.QName(
                contract.get("namespace").asText(), contract.get("element").asText());
    }
}
