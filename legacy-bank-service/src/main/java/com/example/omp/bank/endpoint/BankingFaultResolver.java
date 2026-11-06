package com.example.omp.bank.endpoint;

import com.example.omp.bank.service.BankingFaultException;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import org.springframework.ws.soap.SoapFault;
import org.springframework.ws.soap.server.endpoint.SoapFaultMappingExceptionResolver;

/** Emits the BankingFault detail defined in banking.xsd for every backend fault. */
public class BankingFaultResolver extends SoapFaultMappingExceptionResolver {
    private static final String NAMESPACE = "http://legacybank.example.com/ws";

    @Override
    protected void customizeFault(Object endpoint, Exception ex, SoapFault fault) {
        String code =
                ex instanceof BankingFaultException business
                        ? business.getCode()
                        : "UPSTREAM_ERROR";
        String message =
                ex instanceof BankingFaultException business
                        ? business.getMessage()
                        : "Bank service unavailable";
        try {
            var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            var root = document.createElementNS(NAMESPACE, "BankingFault");
            document.appendChild(root);
            var codeElement = document.createElementNS(NAMESPACE, "code");
            codeElement.setTextContent(code);
            root.appendChild(codeElement);
            var messageElement = document.createElementNS(NAMESPACE, "message");
            messageElement.setTextContent(message);
            root.appendChild(messageElement);
            TransformerFactory.newInstance()
                    .newTransformer()
                    .transform(new DOMSource(document), fault.addFaultDetail().getResult());
        } catch (Exception serializationFailure) {
            throw new IllegalStateException("Could not serialize bank fault", serializationFailure);
        }
    }
}
