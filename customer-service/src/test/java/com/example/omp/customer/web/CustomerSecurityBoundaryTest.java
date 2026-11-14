package com.example.omp.customer.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.omp.customer.TestSecurityConfig;
import com.example.omp.customer.config.SecurityConfig;
import com.example.omp.customer.service.CustomerService;
import com.example.omp.shared.web.config.SharedWebAutoConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({
    AddressController.class,
    CustomerController.class,
    CustomerPreferencesController.class
})
@Import({SecurityConfig.class, SharedWebAutoConfiguration.class, TestSecurityConfig.class})
@ActiveProfiles("test")
class CustomerSecurityBoundaryTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000102");
    @Autowired MockMvc mvc;
    @MockitoBean CustomerService customers;

    @Test
    void anonymousCallerCannotReadAddresses() throws Exception {
        mvc.perform(get("/api/v1/customers/{id}/addresses", ALICE))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(customers);
    }

    @Test
    void customerWithoutVerifiedClaimCannotReadAddresses() throws Exception {
        mvc.perform(
                        get("/api/v1/customers/{id}/addresses", ALICE)
                                .header(
                                        "Authorization",
                                        "Bearer " + TestSecurityConfig.aliceToken()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(customers);
    }

    @Test
    void verifiedCustomerCanReadOwnAddresses() throws Exception {
        mvc.perform(
                        get("/api/v1/customers/{id}/addresses", ALICE)
                                .header(
                                        "Authorization",
                                        "Bearer " + TestSecurityConfig.linkedAliceToken()))
                .andExpect(status().isOk());
        verify(customers).listAddresses(ALICE);
    }

    @Test
    void verifiedCustomerCannotReadAnotherCustomersAddresses() throws Exception {
        mvc.perform(
                        get("/api/v1/customers/{id}/addresses", BOB)
                                .header(
                                        "Authorization",
                                        "Bearer " + TestSecurityConfig.linkedAliceToken()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(customers);
    }

    @Test
    void adminCanReadAnotherCustomersAddresses() throws Exception {
        mvc.perform(
                        get("/api/v1/customers/{id}/addresses", BOB)
                                .header(
                                        "Authorization",
                                        "Bearer " + TestSecurityConfig.adminToken()))
                .andExpect(status().isOk());
        verify(customers).listAddresses(BOB);
    }

    @Test
    void configuredPaginationAcceptsEmailSort() throws Exception {
        mvc.perform(
                        get("/api/v1/customers")
                                .header(
                                        "Authorization",
                                        "Bearer " + TestSecurityConfig.adminToken())
                                .param("sort", "email,asc"))
                .andExpect(status().isOk());
        verify(customers)
                .list(any(), argThat(page -> page.getSort().getOrderFor("email").isAscending()));
    }

    @Test
    void unknownSortFieldIsRejectedBeforeServiceAccess() throws Exception {
        mvc.perform(
                        get("/api/v1/customers")
                                .header(
                                        "Authorization",
                                        "Bearer " + TestSecurityConfig.adminToken())
                                .param("sort", "name,asc"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(customers);
    }

    @Test
    void allowedOriginCanPreflightWithoutAuthentication() throws Exception {
        mvc.perform(
                        options("/api/v1/customers/me")
                                .header("Origin", "http://localhost:3000")
                                .header("Access-Control-Request-Method", "GET")
                                .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
        verifyNoInteractions(customers);
    }

    @Test
    void unknownOriginCannotPreflight() throws Exception {
        mvc.perform(
                        options("/api/v1/customers/me")
                                .header("Origin", "https://untrusted.example")
                                .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        verifyNoInteractions(customers);
    }

    @Test
    void preferenceWritesRequireAdministratorEvenForTheVerifiedOwner() throws Exception {
        for (String token : new String[] {"", TestSecurityConfig.linkedAliceToken()}) {
            var request =
                    put("/api/v1/customers/{id}/preferences", ALICE)
                            .contentType("application/json")
                            .content("{}");
            if (!token.isBlank()) request.header("Authorization", "Bearer " + token);
            mvc.perform(request)
                    .andExpect(
                            token.isBlank() ? status().isUnauthorized() : status().isForbidden());
        }
        verifyNoInteractions(customers);
    }

    @Test
    void administratorPartialPreferencesPreserveAbsentFieldsAndExplicitFalse() throws Exception {
        when(customers.updatePreferences(eq(ALICE), any()))
                .thenReturn(
                        new com.example.omp.customer.dto.CustomerPreferencesDto(
                                "en-US", "USD", false, false, false));
        mvc.perform(
                        put("/api/v1/customers/{id}/preferences", ALICE)
                                .header(
                                        "Authorization",
                                        "Bearer " + TestSecurityConfig.adminToken())
                                .contentType("application/json")
                                .content("{\"notificationEmail\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationEmail").value(false));
        verify(customers)
                .updatePreferences(
                        ALICE,
                        new com.example.omp.customer.dto.UpdatePreferencesRequest(
                                null, null, null, false, null));
    }

    @Test
    void invalidPreferenceFieldsFailBeforeServiceMutation() throws Exception {
        for (String body : new String[] {"{\"currency\":\"EURO\"}", "{\"locale\":\" \"}"}) {
            mvc.perform(
                            put("/api/v1/customers/{id}/preferences", ALICE)
                                    .header(
                                            "Authorization",
                                            "Bearer " + TestSecurityConfig.adminToken())
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(customers);
    }
}
