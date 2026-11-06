package com.example.omp.order.service;

import com.example.omp.order.domain.OrderNumber;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Generates unique order numbers by querying a Postgres sequence.
 *
 * <p>The sequence ({@code orders.order_number_seq}) is created in V1 migration. Format:
 * ORD-YYYY-NNNNNNN.
 */
@Component
public class OrderNumberGenerator {

    private final JdbcTemplate jdbc;

    public OrderNumberGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public OrderNumber next() {
        Long seq = jdbc.queryForObject("SELECT nextval('orders.order_number_seq')", Long.class);
        return OrderNumber.next(seq != null ? seq : 1L);
    }
}
