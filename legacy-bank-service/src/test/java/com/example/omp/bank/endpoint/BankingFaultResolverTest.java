package com.example.omp.bank.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.omp.bank.service.BankingFaultException;
import java.io.StringWriter;
import java.util.Locale;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import org.junit.jupiter.api.Test;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;

class BankingFaultResolverTest {
    @Test
    void businessFaultUsesNamespacedSchema() throws Exception {
        assertThat(detail(new BankingFaultException("INSUFFICIENT_FUNDS", "Insufficient funds")))
                .contains(
                        "BankingFault",
                        "http://legacybank.example.com/ws",
                        "INSUFFICIENT_FUNDS",
                        "Insufficient funds");
    }

    @Test
    void infrastructureFaultRedactsImplementationDetails() throws Exception {
        assertThat(detail(new IllegalStateException("password=secret")))
                .contains("UPSTREAM_ERROR", "Bank service unavailable")
                .doesNotContain("password", "secret");
    }

    private String detail(Exception exception) throws Exception {
        var factory = new SaajSoapMessageFactory();
        factory.afterPropertiesSet();
        var fault =
                factory.createWebServiceMessage()
                        .getSoapBody()
                        .addServerOrReceiverFault("Bank service unavailable", Locale.ENGLISH);
        new BankingFaultResolver().customizeFault(null, exception, fault);
        var writer = new StringWriter();
        TransformerFactory.newInstance()
                .newTransformer()
                .transform(fault.getFaultDetail().getSource(), new StreamResult(writer));
        return writer.toString();
    }
}
