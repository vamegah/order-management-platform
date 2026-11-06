package com.example.omp.bank.config;

import com.example.omp.bank.endpoint.BankingFaultResolver;
import com.example.omp.bank.service.BankingFaultException;
import java.util.List;
import java.util.Properties;
import javax.security.auth.callback.CallbackHandler;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.ws.config.annotation.EnableWs;
import org.springframework.ws.config.annotation.WsConfigurerAdapter;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.soap.security.wss4j2.Wss4jSecurityInterceptor;
import org.springframework.ws.soap.security.wss4j2.callback.SpringSecurityPasswordValidationCallbackHandler;
import org.springframework.ws.soap.server.endpoint.SoapFaultDefinition;
import org.springframework.ws.soap.server.endpoint.SoapFaultMappingExceptionResolver;
import org.springframework.ws.transport.http.MessageDispatcherServlet;
import org.springframework.ws.wsdl.wsdl11.SimpleWsdl11Definition;
import org.springframework.xml.xsd.SimpleXsdSchema;
import org.springframework.xml.xsd.XsdSchema;

@EnableWs
@Configuration
public class WebServiceConfig extends WsConfigurerAdapter {

    public static final String NAMESPACE = "http://legacybank.example.com/ws";

    @Bean
    public ServletRegistrationBean<MessageDispatcherServlet> messageDispatcherServlet(
            ApplicationContext context) {
        MessageDispatcherServlet servlet = new MessageDispatcherServlet();
        servlet.setApplicationContext(context);
        servlet.setTransformWsdlLocations(true);
        return new ServletRegistrationBean<>(servlet, "/ws/*");
    }

    /**
     * Publishes the WSDL at /ws/banking.wsdl (Spring-WS maps the bean name to the WSDL filename.)
     */
    @Bean(name = "banking")
    public SimpleWsdl11Definition bankingWsdl() {
        return new SimpleWsdl11Definition(new ClassPathResource("wsdl/legacy-bank.wsdl"));
    }

    @Bean
    public XsdSchema bankingSchema() {
        return new SimpleXsdSchema(new ClassPathResource("xsd/banking.xsd"));
    }

    /** Maps bank failures to the versioned, namespaced BankingFault detail contract. */
    @Bean
    public SoapFaultMappingExceptionResolver exceptionResolver() {
        SoapFaultMappingExceptionResolver resolver = new BankingFaultResolver();
        // Run before Spring-WS's generic resolver so the structured detail reaches clients.
        resolver.setOrder(0);

        SoapFaultDefinition defaultFault = new SoapFaultDefinition();
        defaultFault.setFaultCode(SoapFaultDefinition.SERVER);
        defaultFault.setFaultStringOrReason("Bank service unavailable");
        resolver.setDefaultFault(defaultFault);

        Properties mappings = new Properties();
        mappings.setProperty(
                BankingFaultException.class.getName(), SoapFaultDefinition.CLIENT.toString());
        resolver.setExceptionMappings(mappings);

        return resolver;
    }

    @Bean
    public Wss4jSecurityInterceptor securityInterceptor(UserDetailsService userDetailsService) {
        Wss4jSecurityInterceptor interceptor = new Wss4jSecurityInterceptor();
        // Require UsernameToken + Timestamp on every request
        interceptor.setValidationActions("UsernameToken Timestamp");
        interceptor.setValidationCallbackHandler(securityCallbackHandler(userDetailsService));
        var validators = org.apache.wss4j.dom.engine.WSSConfig.getNewInstance();
        validators.setValidator(
                org.apache.wss4j.dom.WSConstants.USERNAME_TOKEN,
                new EncodedPasswordTokenValidator(userDetailsService, passwordEncoder));
        interceptor.setWssConfig(validators);
        // Reject if timestamp is more than 60s old
        interceptor.setTimestampStrict(true);
        interceptor.setValidationTimeToLive(60);
        return interceptor;
    }

    @Bean
    public CallbackHandler securityCallbackHandler(UserDetailsService userDetailsService) {
        SpringSecurityPasswordValidationCallbackHandler handler =
                new SpringSecurityPasswordValidationCallbackHandler();
        handler.setUserDetailsService(userDetailsService);
        return handler;
    }

    @Override
    public void addInterceptors(List<EndpointInterceptor> interceptors) {
        interceptors.add(securityInterceptor(userDetailsService));
    }

    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;

    public WebServiceConfig(
            UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
    }
}
