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

    EncodedPasswordTokenValidator(UserDetailsService users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
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
        return super.validate(credential, data);
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
