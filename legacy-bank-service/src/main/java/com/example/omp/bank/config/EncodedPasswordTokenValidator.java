package com.example.omp.bank.config;

import org.apache.wss4j.common.ext.WSSecurityException;
import org.apache.wss4j.dom.WSConstants;
import org.apache.wss4j.dom.handler.RequestData;
import org.apache.wss4j.dom.message.token.UsernameToken;
import org.apache.wss4j.dom.validate.Credential;
import org.apache.wss4j.dom.validate.UsernameTokenValidator;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Validates PasswordText against encoded users; the transport must provide trusted TLS. */
final class EncodedPasswordTokenValidator extends UsernameTokenValidator {
    private final UserDetailsService users;
    private final PasswordEncoder encoder;
    private final org.springframework.jdbc.core.JdbcTemplate db;

    EncodedPasswordTokenValidator(
            UserDetailsService users,
            PasswordEncoder encoder,
            org.springframework.jdbc.core.JdbcTemplate db) {
        this.users = users;
        this.encoder = encoder;
        this.db = db;
    }

    @Override
    public Credential validate(Credential credential, RequestData data) throws WSSecurityException {
        // Digest verification requires a recoverable secret; encoded user passwords cannot
        // support it. Reject digest, custom and passwordless token types explicitly.
        if (credential == null
                || credential.getUsernametoken() == null
                || !WSConstants.PASSWORD_TEXT.equals(
                        credential.getUsernametoken().getPasswordType())) {
            throw rejected();
        }
        var validated = super.validate(credential, data);
        var token = validated.getUsernametoken();
        try {
            var created = java.time.Instant.parse(token.getCreated());
            var now = java.time.Instant.now();
            if (token.getNonce() == null
                    || token.getNonce().length() < 16
                    || token.getNonce().length() > 256
                    || created.isBefore(now.minusSeconds(60))
                    || created.isAfter(now.plusSeconds(5))) throw rejected();
            byte[] nonce = java.util.Base64.getDecoder().decode(token.getNonce());
            if (nonce.length < 16 || nonce.length > 64) throw rejected();
            db.update("DELETE FROM bank_nonce WHERE expires_at<CURRENT_TIMESTAMP");
            String hash =
                    com.example.omp.bank.service.BankingService.hash(
                            token.getName() + "\n" + token.getNonce());
            if (db.update(
                            "INSERT INTO bank_nonce(nonce_hash,expires_at) VALUES (?,?) ON CONFLICT DO NOTHING",
                            hash,
                            java.sql.Timestamp.from(now.plusSeconds(120)))
                    != 1) throw rejected();
        } catch (java.time.DateTimeException
                | IllegalArgumentException
                | NullPointerException
                | org.springframework.dao.DataAccessException failure) {
            throw rejected();
        }
        return validated;
    }

    @Override
    protected void verifyPlaintextPassword(UsernameToken token, RequestData data)
            throws WSSecurityException {
        try {
            var user = users.loadUserByUsername(token.getName());
            if (!user.isEnabled()
                    || !user.isAccountNonLocked()
                    || !user.isAccountNonExpired()
                    || !user.isCredentialsNonExpired()
                    || !encoder.matches(token.getPassword(), user.getPassword())) {
                throw rejected();
            }
        } catch (UsernameNotFoundException | IllegalArgumentException failure) {
            throw rejected();
        }
    }

    private WSSecurityException rejected() {
        return new WSSecurityException(WSSecurityException.ErrorCode.FAILED_AUTHENTICATION);
    }
}
