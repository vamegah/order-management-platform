package com.example.omp.customer.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger configuration.
 *
 * <p>Springdoc auto-generates the spec from {@code @Operation} annotations on controller methods.
 * This class adds top-level metadata and the JWT security scheme so Swagger UI's "Authorize" button
 * works.
 */
@Configuration
public class OpenApiConfig {

    @Value("${spring.application.name}")
    private String applicationName;

    @Bean
    public OpenAPI productServiceOpenApi() {
        final String securitySchemeName = "bearerAuth";

        return new OpenAPI()
                .info(
                        new Info()
                                .title("Customer Service API")
                                .version("1.0.0")
                                .description(
                                        """
                    REST API for the customer management system.

                    **Authentication:** All endpoints except `/actuator/health` require
                    a JWT issued by Keycloak. Click "Authorize" and paste a token from
                    the `postman` client (see the Keycloak realm config).

                    **Roles:** Read operations require any authenticated user. Write
                    operations (POST, PUT, PATCH, DELETE) require the `admin` role.
                    """)
                                .contact(
                                        new Contact()
                                                .name("Order Management Platform")
                                                .url(
                                                        "https://github.com/example/order-management-platform"))
                                .license(new License().name("Internal Reference Project")))
                .servers(
                        List.of(
                                new Server()
                                        .url("http://customer-service.localhost")
                                        .description("Local (dev)"),
                                new Server()
                                        .url("https://api.example.com")
                                        .description("Production")))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        securitySchemeName,
                                        new SecurityScheme()
                                                .name(securitySchemeName)
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")
                                                .description(
                                                        "JWT issued by Keycloak. Obtain via the `postman` client.")))
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName));
    }
}
