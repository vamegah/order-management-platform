package com.example.omp.bank.config;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SecurityUsersConfigTest {
    SecurityUsersConfig config(
            String username, String password, String previousUsername, String previousPassword) {
        var config = new SecurityUsersConfig();
        ReflectionTestUtils.setField(config, "username", username);
        ReflectionTestUtils.setField(config, "password", password);
        ReflectionTestUtils.setField(config, "previousUsername", previousUsername);
        ReflectionTestUtils.setField(config, "previousPassword", previousPassword);
        return config;
    }

    @Test
    void supportsOverlapWithoutStoringPlaintextAndRetirement() {
        var config = config("new-user", "new-private", "old-user", "old-private");
        var encoder = config.bankPasswordEncoder();
        var users = config.bankUsers(encoder);
        assertThat(
                        encoder.matches(
                                "new-private", users.loadUserByUsername("new-user").getPassword()))
                .isTrue();
        assertThat(
                        encoder.matches(
                                "old-private", users.loadUserByUsername("old-user").getPassword()))
                .isTrue();
        var retired = config("new-user", "new-private", "", "").bankUsers(encoder);
        assertThatThrownBy(() -> retired.loadUserByUsername("old-user"))
                .isInstanceOf(
                        org.springframework.security.core.userdetails.UsernameNotFoundException
                                .class);
    }

    @Test
    void missingSecretsAndAmbiguousRotationFailStartup() {
        for (var config :
                java.util.List.of(
                        config("", "private", "", ""),
                        config("user", "", "", ""),
                        config("user", "private", "user", "other"),
                        config("user", "private", "previous", ""),
                        config("user", "private", "", "old-private")))
            assertThatThrownBy(() -> config.bankUsers(config.bankPasswordEncoder()))
                    .isInstanceOf(IllegalStateException.class);
    }
}
