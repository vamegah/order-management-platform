package com.example.omp.adapter.rest;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.omp.adapter.dto.BalanceResponse;
import com.example.omp.adapter.dto.TransferResponse;
import com.example.omp.adapter.port.BankingPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class BankingHttpSecurityTest {
    @Autowired MockMvc http;
    @MockitoBean BankingPort bank;
    @MockitoBean JwtDecoder decoder;

    @BeforeEach
    void tokens() {
        when(decoder.decode(anyString()))
                .thenAnswer(
                        call -> {
                            String token = call.getArgument(0);
                            if (token.equals("invalid")) throw new BadJwtException("Invalid token");
                            var jwt =
                                    Jwt.withTokenValue(token)
                                            .header("alg", "RS256")
                                            .subject(token)
                                            .claim("scope", "banking.read");
                            if (token.equals("admin"))
                                jwt.claim(
                                        "realm_access", Map.of("roles", List.of("admin", "", 42)));
                            else if (token.equals("malformed-roles"))
                                jwt.claim("realm_access", Map.of("roles", "admin"));
                            else if (!token.equals("claimless"))
                                jwt.claim("realm_access", Map.of("roles", List.of("user")));
                            return jwt.build();
                        });
        when(bank.getBalance("ACC-1"))
                .thenReturn(
                        new BalanceResponse(
                                "ACC-1",
                                new BigDecimal("10.50"),
                                "USD",
                                Instant.parse("2026-11-13T12:00:00Z")));
        when(bank.transfer(any()))
                .thenReturn(
                        new TransferResponse(
                                "test-transaction",
                                "COMPLETED",
                                Instant.parse("2026-11-13T12:00:00Z"),
                                new BigDecimal("9.50")));
    }

    @Test
    void missingAndInvalidTokensAreRejectedBeforeTheBankIsCalled() throws Exception {
        http.perform(get("/api/v1/banking/accounts/ACC-1/balance"))
                .andExpect(status().isUnauthorized());
        http.perform(
                        get("/api/v1/banking/accounts/ACC-1/balance")
                                .header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(bank);
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "claimless", "malformed-roles"})
    void nonAdministratorsCannotReadOrTransfer(String token) throws Exception {
        http.perform(
                        get("/api/v1/banking/accounts/ACC-1/balance")
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        http.perform(
                        post("/api/v1/banking/transfers")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(transferBody()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(bank);
    }

    @Test
    void administratorCanReadAndTransferUsingTheDecodedRealmRole() throws Exception {
        http.perform(
                        get("/api/v1/banking/accounts/ACC-1/balance")
                                .header("Authorization", "Bearer admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value("ACC-1"))
                .andExpect(jsonPath("$.balance").value(10.5));
        http.perform(
                        post("/api/v1/banking/transfers")
                                .header("Authorization", "Bearer admin")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(transferBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("test-transaction"));
    }

    @Test
    void administratorValidationFailureDoesNotCallSoap() throws Exception {
        http.perform(
                        post("/api/v1/banking/transfers")
                                .header("Authorization", "Bearer admin")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(bank);
    }

    @Test
    void developmentDocumentationPublishesJwtSecurityAndBothRoutes() throws Exception {
        http.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value("3.1.0"))
                .andExpect(
                        jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$['x-implemented-routes'].length()").value(2));
    }

    private String transferBody() {
        return "{\"fromAccount\":\"ACC-1\",\"toAccount\":\"ACC-2\",\"amount\":1.00,\"currency\":\"USD\",\"reference\":\"security-test\"}";
    }
}
