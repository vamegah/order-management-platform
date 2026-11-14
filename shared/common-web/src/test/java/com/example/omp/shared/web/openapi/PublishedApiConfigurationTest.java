package com.example.omp.shared.web.openapi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class PublishedApiConfigurationTest {
    private final PublishedApiConfiguration configuration = new PublishedApiConfiguration();

    @Test
    void inventoryIncludesHiddenAndMultipleRoutesButExcludesInfrastructure() throws Exception {
        var mappings = mock(RequestMappingHandlerMapping.class);
        var entries = new LinkedHashMap<RequestMappingInfo, HandlerMethod>();
        var handler = handler(new ExampleController(), "hidden");
        entries.put(
                RequestMappingInfo.paths("/api/v1/b", "/api/v1/a")
                        .methods(RequestMethod.POST, RequestMethod.GET)
                        .build(),
                handler);
        entries.put(RequestMappingInfo.paths("/actuator/health").build(), handler);
        when(mappings.getHandlerMethods()).thenReturn(entries);
        var document = new OpenAPI();
        configuration.publishedRouteInventory(mappings).customise(document);
        assertThat(document.getExtensions().get("x-implemented-routes"))
                .isEqualTo(
                        List.of(
                                "GET /api/v1/a",
                                "GET /api/v1/b",
                                "POST /api/v1/a",
                                "POST /api/v1/b"));
    }

    @Test
    void unboundedHttpMethodMappingsCannotEscapePublication() throws Exception {
        var mappings = mock(RequestMappingHandlerMapping.class);
        when(mappings.getHandlerMethods())
                .thenReturn(
                        java.util.Map.of(
                                RequestMappingInfo.paths("/api/v1/unsafe").build(),
                                handler(new ExampleController(), "hidden")));
        assertThatThrownBy(
                        () ->
                                configuration
                                        .publishedRouteInventory(mappings)
                                        .customise(new OpenAPI()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declare HTTP methods");
    }

    @Test
    void stableOperationIdentityUsesTheControllerAndMethod() throws Exception {
        var operation =
                configuration
                        .publishedOperationIdentity()
                        .customize(new Operation(), handler(new ExampleController(), "hidden"));
        assertThat(operation.getOperationId()).isEqualTo("Example_hidden");
        assertThat(operation.getResponses()).containsKeys("401", "403");
        assertThat(operation.getExtensions()).isNull();
    }

    @Test
    void explicitIdentityAndAuthenticationResponseArePreserved() throws Exception {
        var response = new ApiResponse().description("Provider signature rejected");
        var operation =
                new Operation()
                        .operationId("existing")
                        .responses(new ApiResponses().addApiResponse("401", response));
        configuration
                .publishedOperationIdentity()
                .customize(operation, handler(new ExampleController(), "explicit"));
        assertThat(operation.getOperationId()).isEqualTo("existing");
        assertThat(operation.getResponses().get("401")).isSameAs(response);
    }

    @Test
    void methodAuthorizationOverridesTheClassPolicy() throws Exception {
        var operation =
                configuration
                        .publishedOperationIdentity()
                        .customize(new Operation(), handler(new SecuredController(), "owner"));
        assertThat(operation.getExtensions())
                .containsEntry("x-method-authorization", "hasAuthority('owner')");
    }

    @Test
    void classAuthorizationIsRetainedInTheContract() throws Exception {
        var operation =
                configuration
                        .publishedOperationIdentity()
                        .customize(new Operation(), handler(new SecuredController(), "admin"));
        assertThat(operation.getExtensions())
                .containsEntry("x-method-authorization", "hasRole('admin')");
    }

    @Test
    void definitionsPublishV1JwtSecurityAndTheActualDevelopmentIngress() {
        var definition =
                PublishedApiSupport.definition(
                        "Catalog", "product-service.localhost", "Catalog operations");
        assertThat(definition.getOpenapi()).isEqualTo("3.1.0");
        assertThat(definition.getInfo().getVersion()).isEqualTo("1.0.0");
        assertThat(definition.getServers().getFirst().getUrl())
                .isEqualTo("http://product-service.localhost");
        assertThat(definition.getSecurity().getFirst()).containsKey("bearerAuth");
        assertThat(definition.getComponents().getSecuritySchemes().get("bearerAuth").getScheme())
                .isEqualTo("bearer");
    }

    private static HandlerMethod handler(Object bean, String name) throws Exception {
        return new HandlerMethod(bean, bean.getClass().getMethod(name));
    }

    static class ExampleController {
        @Hidden
        public void hidden() {}

        @io.swagger.v3.oas.annotations.Operation(operationId = "publishedName")
        public void explicit() {}
    }

    @PreAuthorize("hasRole('admin')")
    static class SecuredController {
        public void admin() {}

        @PreAuthorize("hasAuthority('owner')")
        public void owner() {}
    }
}
