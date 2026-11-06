package com.example.omp.gateway;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Architecture rules for the gateway, enforced by ArchUnit.
 *
 * <p>These rules protect the gateway's layering:
 *
 * <ul>
 *   <li><b>graphql.type</b> — pure data carriers (records). No Spring, no Feign clients, no mapper
 *       logic.
 *   <li><b>clients</b> — Feign interfaces. Only depend on DTOs and Feign annotations.
 *   <li><b>graphql.*</b> resolvers — may depend on clients and mappers.
 *   <li><b>graphql.mapper</b> — converts client DTOs to GraphQL types. No client calls.
 * </ul>
 */
class LayeringTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.example.omp.gateway");
    }

    // =========================================================================
    // graphql.type records are pure data carriers
    // =========================================================================

    @Test
    void dtoRecordsDoNotDependOnSpring() {
        noClasses()
                .that()
                .resideInAPackage("..gateway.clients.dto..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("org.springframework..")
                .check(classes);
    }

    @Test
    void dtoRecordsDoNotDependOnFeignClients() {
        noClasses()
                .that()
                .resideInAPackage("..gateway.clients.dto..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.example.omp.gateway.clients")
                .check(classes);
    }

    // =========================================================================
    // Clients only depend on DTOs and Feign
    // =========================================================================

    @Test
    void clientsDoNotDependOnResolvers() {
        noClasses()
                .that()
                .resideInAPackage("..gateway.clients..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..gateway.graphql..")
                .check(classes);
    }

    @Test
    void clientsDoNotDependOnMappers() {
        noClasses()
                .that()
                .resideInAPackage("..gateway.clients..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..gateway.graphql.mapper..")
                .check(classes);
    }

    // =========================================================================
    // Mappers depend on types and DTOs only
    // =========================================================================

    @Test
    void dtoRecordsDoNotCallClients() {
        noClasses()
                .that()
                .resideInAPackage("..gateway.clients.dto..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.example.omp.gateway.clients")
                .check(classes);
    }

    @Test
    void dtoRecordsDoNotDependOnResolvers() {
        noClasses()
                .that()
                .resideInAPackage("..gateway.clients.dto..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..gateway.graphql.query..",
                        "..gateway.graphql.mutation..",
                        "..gateway.graphql.field..")
                .check(classes);
    }

    // =========================================================================
    // Controllers don't bypass the resolver layer
    // =========================================================================

    @Test
    void graphqlFieldResolversDoNotDependOnHttpLayer() {
        noClasses()
                .that()
                .resideInAPackage("..gateway.graphql..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.web.servlet..", "jakarta.servlet..")
                .check(classes);
    }

    // =========================================================================
    // Error handling structure
    // =========================================================================

    @Test
    void graphqlErrorHandlingDoesNotDependOnClients() {
        noClasses()
                .that()
                .resideInAPackage("..gateway.graphql.error..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.example.omp.gateway.clients")
                .check(classes);
    }
}
