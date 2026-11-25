package com.example.omp.bank.config;

import javax.xml.namespace.QName;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.soap.SoapHeaderElement;
import org.springframework.ws.soap.server.SoapEndpointInterceptor;

/** MustUnderstand binds this header to durable deduplication, never to best-effort caching. */
public class DurableRequestHeaderInterceptor implements SoapEndpointInterceptor {
    public boolean understands(SoapHeaderElement header) {
        return header.getName().equals(new QName("urn:omp:banking:request:v1", "RequestId"));
    }

    public boolean handleRequest(MessageContext context, Object endpoint) {
        return true;
    }

    public boolean handleResponse(MessageContext context, Object endpoint) {
        return true;
    }

    public boolean handleFault(MessageContext context, Object endpoint) {
        return true;
    }

    public void afterCompletion(MessageContext context, Object endpoint, Exception failure) {}
}
