package com.example.omp.adapter.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.transport.http.HttpUrlConnectionMessageSender;

@Configuration
public class SoapClientConfig {
    private io.micrometer.tracing.Tracer tracer;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void configureTracing(io.micrometer.tracing.Tracer tracer) {
        this.tracer = tracer;
    }

    @Value("${bank.ws.url}")
    private String bankWsUrl;

    @Bean
    public Jaxb2Marshaller jaxb2Marshaller() {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setContextPath("com.omp.bank.soap");
        return marshaller;
    }

    @Bean
    public SaajSoapMessageFactory messageFactory() {
        SaajSoapMessageFactory factory = new SaajSoapMessageFactory();
        factory.afterPropertiesSet();
        return factory;
    }

    @Bean
    public HttpUrlConnectionMessageSender messageSender(
            @Value("${bank.ws.allow-insecure:false}") boolean allowInsecure,
            @Value("${bank.ws.trust-store:}") String trustStore,
            @Value("${bank.ws.trust-store-password:}") String trustPassword,
            @Value("${bank.ws.connect-timeout:1s}") java.time.Duration connectTimeout,
            @Value("${bank.ws.read-timeout:2s}") java.time.Duration readTimeout)
            throws Exception {
        var uri = java.net.URI.create(bankWsUrl);
        if (uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getQuery() != null
                || (!"https".equals(uri.getScheme()) && !allowInsecure))
            throw new IllegalStateException("Bank URL must use trusted HTTPS");
        if (connectTimeout.isZero()
                || connectTimeout.isNegative()
                || connectTimeout.compareTo(java.time.Duration.ofSeconds(5)) > 0
                || readTimeout.isZero()
                || readTimeout.isNegative()
                || readTimeout.compareTo(java.time.Duration.ofSeconds(10)) > 0)
            throw new IllegalStateException("Bank timeouts must be bounded");
        HttpUrlConnectionMessageSender sender;
        if ("https".equals(uri.getScheme())) {
            var https =
                    new org.springframework.ws.transport.http.HttpsUrlConnectionMessageSender() {
                        @Override
                        protected void prepareConnection(java.net.HttpURLConnection connection)
                                throws java.io.IOException {
                            super.prepareConnection(connection);
                            connection.setInstanceFollowRedirects(false);
                        }
                    };
            var trust =
                    javax.net.ssl.TrustManagerFactory.getInstance(
                            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            java.security.KeyStore store = null;
            if (!trustStore.isBlank()) {
                store = java.security.KeyStore.getInstance("PKCS12");
                try (var input =
                        new org.springframework.core.io.DefaultResourceLoader()
                                .getResource(trustStore)
                                .getInputStream()) {
                    store.load(input, trustPassword.toCharArray());
                }
            }
            trust.init(store);
            https.setTrustManagers(trust.getTrustManagers());
            https.setSslProtocol("TLS");
            sender = https;
        } else sender = new HttpUrlConnectionMessageSender();
        sender.setConnectionTimeout(connectTimeout);
        sender.setReadTimeout(readTimeout);
        return sender;
    }

    @Bean
    public WebServiceTemplate webServiceTemplate(
            Jaxb2Marshaller marshaller,
            SaajSoapMessageFactory messageFactory,
            HttpUrlConnectionMessageSender messageSender,
            WssConfig wssConfig) {

        WebServiceTemplate template = new WebServiceTemplate(messageFactory);
        template.setMarshaller(marshaller);
        template.setUnmarshaller(marshaller);
        template.setDefaultUri(bankWsUrl);
        template.setMessageSender(messageSender);
        template.setInterceptors(
                new org.springframework.ws.client.support.interceptor.ClientInterceptor[] {
                    wssConfig.securityInterceptor(),
                    new com.example.omp.adapter.soap.SoapTraceInterceptor(tracer)
                });
        return template;
    }
}
