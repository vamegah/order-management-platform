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
    public HttpUrlConnectionMessageSender messageSender() throws Exception {
        return new HttpUrlConnectionMessageSender();
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
                    wssConfig.securityInterceptor()
                });
        return template;
    }
}
