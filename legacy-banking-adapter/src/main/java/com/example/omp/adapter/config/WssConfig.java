package com.example.omp.adapter.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.ws.soap.security.wss4j2.Wss4jSecurityInterceptor;

@Configuration
public class WssConfig {

    @Value("${bank.ws.username}")
    private String username;

    @Value("${bank.ws.password}")
    private String password;

    public Wss4jSecurityInterceptor securityInterceptor() {
        Wss4jSecurityInterceptor interceptor = new Wss4jSecurityInterceptor();
        interceptor.setSecurementActions("UsernameToken Timestamp");
        interceptor.setSecurementUsername(username);
        interceptor.setSecurementPassword(password);
        interceptor.setSecurementPasswordType("PasswordText");
        interceptor.setSecurementTimeToLive(60); // seconds
        return interceptor;
    }
}
