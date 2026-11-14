package com.example.omp.shared.web.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;

/** Metadata shared by the independently generated, published v1 HTTP contracts. */
public final class PublishedApiSupport {
    private PublishedApiSupport() {}

    public static OpenAPI definition(String title, String developmentHost, String description) {
        return new OpenAPI()
                .openapi("3.1.0")
                .info(
                        new Info()
                                .title(title)
                                .version("1.0.0")
                                .description(description)
                                .contact(
                                        new Contact()
                                                .name("Order Management Platform")
                                                .url(
                                                        "https://github.com/vamegah/order-management-platform")))
                .servers(
                        List.of(
                                new Server()
                                        .url("http://" + developmentHost)
                                        .description("Development through Traefik"),
                                new Server().url("/").description("Current ingress origin")))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "bearerAuth",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")
                                                .description(
                                                        "Keycloak access token; customer_id identifies the customer owner.")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
