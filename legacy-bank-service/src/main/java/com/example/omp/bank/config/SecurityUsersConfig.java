package com.example.omp.bank.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
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

    @Value("${bank.security.previous-username:}")
    private String previousUsername;

    @Value("${bank.security.previous-password:}")
    private String previousPassword;

    @Bean
    public PasswordEncoder bankPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService bankUsers(PasswordEncoder encoder) {
        if (username.isBlank() || password.isBlank())
            throw new IllegalStateException("Bank credentials must be mounted");
        if (previousUsername.isBlank() != previousPassword.isBlank())
            throw new IllegalStateException("Complete previous credential pair required");
        var users =
                new InMemoryUserDetailsManager(
                        User.withUsername(username)
                                .password(encoder.encode(password))
                                .roles("BANK_CLIENT")
                                .build());
        if (!previousUsername.isBlank() && !previousPassword.isBlank()) {
            if (previousUsername.equals(username))
                throw new IllegalStateException("Rotation requires distinct user identities");
            users.createUser(
                    User.withUsername(previousUsername)
                            .password(encoder.encode(previousPassword))
                            .roles("BANK_CLIENT")
                            .build());
        }
        return users;
    }
}
