package com.example.omp.order.web;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.order.dto.OrderDto;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

class OrderApiSchemaTest {
    @Test
    void publicOrderSchemaIncludesFulfillmentInItsStringStatusEnum() {
        var schemas = ModelConverters.getInstance().readAll(OrderDto.class);
        Schema<?> status = (Schema<?>) schemas.get("OrderDto").getProperties().get("status");
        if (status.get$ref() != null) {
            status = schemas.get(status.get$ref().substring(status.get$ref().lastIndexOf('/') + 1));
        }
        assertThat(status.getType()).isEqualTo("string");
        assertThat(status.getEnum())
                .extracting(Object::toString)
                .contains("CONFIRMED", "FULFILLING", "SHIPPED", "DELIVERED", "CANCELLED");
    }
}
