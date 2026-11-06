package com.example.omp.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.schema.PropertyDataFetcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.execution.GraphQlSource;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class SchemaTest extends AbstractGraphQlTest {
    @Autowired GraphQlSource source;
    @Autowired org.springframework.graphql.test.tester.GraphQlTester tester;

    @Test
    void developmentSchemaIntrospectionWorksWithinDefaultLimits() {
        tester.document(graphql.introspection.IntrospectionQuery.INTROSPECTION_QUERY)
                .execute()
                .errors()
                .verify()
                .path("__schema.queryType.name")
                .entity(String.class)
                .isEqualTo("Query");
    }

    @Test
    void everyQueryAndMutationHasAnExplicitResolver() {
        var schema = source.schema();
        for (var root : java.util.List.of(schema.getQueryType(), schema.getMutationType())) {
            for (var field : root.getFieldDefinitions()) {
                assertThat(schema.getCodeRegistry().getDataFetcher(root, field))
                        .as("Resolver for %s.%s", root.getName(), field.getName())
                        .isNotInstanceOf(PropertyDataFetcher.class);
            }
        }
    }

    @Test
    void domainScalarsAreWired() {
        for (String name : java.util.List.of("Date", "DateTime", "Decimal")) {
            assertThat(source.schema().getType(name))
                    .isInstanceOf(graphql.schema.GraphQLScalarType.class);
        }
    }
}
