package com.example.omp.bank.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import org.apache.wss4j.dom.WSConstants;
import org.apache.wss4j.dom.handler.RequestData;
import org.apache.wss4j.dom.message.token.UsernameToken;
import org.apache.wss4j.dom.validate.Credential;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;

class EncodedPasswordTokenValidatorTest {
    JdbcTemplate db;
    UserDetailsService users;
    PasswordEncoder passwords;
    EncodedPasswordTokenValidator validator;

    @BeforeEach
    void setup() {
        db =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:h2:mem:"
                                        + java.util.UUID.randomUUID()
                                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                                "sa",
                                ""));
        db.execute(
                "CREATE TABLE bank_nonce(nonce_hash VARCHAR(64) PRIMARY KEY,expires_at TIMESTAMP WITH TIME ZONE NOT NULL)");
        users = mock(UserDetailsService.class);
        passwords = mock(PasswordEncoder.class);
        when(users.loadUserByUsername("platform"))
                .thenReturn(
                        User.withUsername("platform")
                                .password("encoded")
                                .roles("BANK_CLIENT")
                                .build());
        when(passwords.matches("private", "encoded")).thenReturn(true);
        validator = new EncodedPasswordTokenValidator(users, passwords, db);
    }

    UsernameToken token() {
        var token = mock(UsernameToken.class);
        when(token.getName()).thenReturn("platform");
        when(token.getPassword()).thenReturn("private");
        when(token.getPasswordType()).thenReturn(WSConstants.PASSWORD_TEXT);
        when(token.getNonce()).thenReturn("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NQ==");
        when(token.getCreated()).thenReturn(Instant.now().toString());
        return token;
    }

    Credential credential(UsernameToken token) {
        var credential = new Credential();
        credential.setUsernametoken(token);
        return credential;
    }

    @Test
    void nonceIsConsumedAtomicallyAndSurvivesValidatorReplacement() throws Exception {
        var credential = credential(token());
        assertThat(validator.validate(credential, new RequestData())).isSameAs(credential);
        var restarted = new EncodedPasswordTokenValidator(users, passwords, db);
        assertThatThrownBy(() -> restarted.validate(credential, new RequestData()))
                .isInstanceOf(org.apache.wss4j.common.ext.WSSecurityException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM bank_nonce", Integer.class))
                .isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "bad-password",
                "unknown",
                "disabled",
                "locked",
                "expired",
                "credential-expired",
                "digest",
                "passwordless",
                "missing-nonce",
                "short-nonce",
                "invalid-base64",
                "short-base64",
                "oversized-nonce",
                "expired-created",
                "future-created",
                "invalid-created",
                "missing-created",
                "database-down"
            })
    void rejectsUnauthenticatedStaleReplayedOrUnverifiableTokens(String type) {
        var token = token();
        switch (type) {
            case "bad-password" -> when(token.getPassword()).thenReturn("bad");
            case "unknown" ->
                    when(users.loadUserByUsername("platform"))
                            .thenThrow(new UsernameNotFoundException("private"));
            case "disabled", "locked", "expired", "credential-expired" ->
                    when(users.loadUserByUsername("platform"))
                            .thenReturn(
                                    User.withUsername("platform")
                                            .password("encoded")
                                            .roles("BANK_CLIENT")
                                            .disabled(type.equals("disabled"))
                                            .accountLocked(type.equals("locked"))
                                            .accountExpired(type.equals("expired"))
                                            .credentialsExpired(type.equals("credential-expired"))
                                            .build());
            case "digest" -> when(token.getPasswordType()).thenReturn(WSConstants.PASSWORD_DIGEST);
            case "passwordless" -> when(token.getPasswordType()).thenReturn(null);
            case "missing-nonce" -> when(token.getNonce()).thenReturn(null);
            case "short-nonce" -> when(token.getNonce()).thenReturn("short");
            case "invalid-base64" -> when(token.getNonce()).thenReturn("invalid_base64_nonce!");
            case "short-base64" -> when(token.getNonce()).thenReturn("MDEyMzQ1Njc4OWFi");
            case "oversized-nonce" -> when(token.getNonce()).thenReturn("x".repeat(257));
            case "expired-created" ->
                    when(token.getCreated()).thenReturn(Instant.now().minusSeconds(180).toString());
            case "future-created" ->
                    when(token.getCreated()).thenReturn(Instant.now().plusSeconds(180).toString());
            case "invalid-created" -> when(token.getCreated()).thenReturn("invalid");
            case "missing-created" -> when(token.getCreated()).thenReturn(null);
            case "database-down" -> db.execute("DROP TABLE bank_nonce");
            default -> throw new AssertionError();
        }
        assertThatThrownBy(() -> validator.validate(credential(token), new RequestData()))
                .isInstanceOf(org.apache.wss4j.common.ext.WSSecurityException.class)
                .hasMessageNotContaining("private");
    }

    @Test
    void rejectsAbsentCredentialsAndInvalidPasswordEncoding() {
        assertThatThrownBy(() -> validator.validate(null, new RequestData()))
                .isInstanceOf(org.apache.wss4j.common.ext.WSSecurityException.class);
        assertThatThrownBy(() -> validator.validate(new Credential(), new RequestData()))
                .isInstanceOf(org.apache.wss4j.common.ext.WSSecurityException.class);
        when(passwords.matches("private", "encoded"))
                .thenThrow(new IllegalArgumentException("private"));
        assertThatThrownBy(() -> validator.validate(credential(token()), new RequestData()))
                .isInstanceOf(org.apache.wss4j.common.ext.WSSecurityException.class)
                .hasMessageNotContaining("private");
    }
}
