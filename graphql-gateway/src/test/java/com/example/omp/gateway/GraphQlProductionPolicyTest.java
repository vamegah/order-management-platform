package com.example.omp.gateway;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "OMP_DOMAIN=example.test")
@ActiveProfiles({"test", "prod"})
@Import(TestSecurityConfig.class)
class GraphQlProductionPolicyTest extends AbstractGraphQlTest {
    private static boolean previousIntrospection;

    @org.junit.jupiter.api.BeforeAll
    static void captureGlobalIntrospection() {
        previousIntrospection = graphql.introspection.Introspection.isEnabledJvmWide();
    }

    @org.junit.jupiter.api.AfterAll
    static void restoreGlobalIntrospection() {
        // Boot 3.3 implements this setting with a JVM-wide graphql-java toggle.
        graphql.introspection.Introspection.enabledJvmWide(previousIntrospection);
    }

    @Autowired GraphQlTester tester;
    @Autowired Environment environment;

    @Test
    void productionDisablesIntrospectionGraphiQlAndServletErrorDetails() {
        tester.document("{__schema{queryType{name}}}")
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors).isNotEmpty());
        assertThat(environment.getProperty("spring.graphql.graphiql.enabled", Boolean.class))
                .isFalse();
        assertThat(
                        environment.getProperty(
                                "spring.graphql.schema.introspection.enabled", Boolean.class))
                .isFalse();
        assertThat(environment.getProperty("server.error.include-message")).isEqualTo("never");
        assertThat(environment.getProperty("server.error.include-stacktrace")).isEqualTo("never");
    }
}
