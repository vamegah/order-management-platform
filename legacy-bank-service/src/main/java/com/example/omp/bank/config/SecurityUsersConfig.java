package com.example.omp.bank.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

@Configuration
public class SecurityUsersConfig {

    @Value("${bank.security.username}")
    private String username;

    @Value("${bank.security.password}")
    private String password;

    @Bean
    public PasswordEncoder bankPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService bankUsers(PasswordEncoder encoder) {
        UserDetails user =
                User.builder()
                        .username(username)
                        .password(encoder.encode(password))
                        .roles("BANK_CLIENT")
                        .build();
        return new InMemoryUserDetailsManager(user);
    }
}
