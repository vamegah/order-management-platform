package com.example.omp.order.service;

import com.example.omp.order.dto.ReservationReferenceCensusPage;
import com.example.omp.order.dto.ReservationReferenceCensusPage.Finding;
import com.example.omp.order.dto.ReservationReferenceCensusPage.Issue;
import com.example.omp.order.error.InvalidOrderDataException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bounded local metadata inspection. Remote ownership and repair require separate verification. */
@Service
public class ReservationReferenceCensus {
    private final JdbcTemplate jdbc;

    public ReservationReferenceCensus(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, timeout = 5)
    public ReservationReferenceCensusPage scan(UUID afterLineId, int limit) {
        if (limit < 1 || limit > 100)
            throw new InvalidOrderDataException("Census limit must be between 1 and 100");
        // Page over all lines so healthy rows cannot turn a request into an unbounded scan.
        String sql =
                """
                SELECT l.id, l.order_id, o.version, o.status, l.product_sku,
                       l.quantity, l.reservation_id,
                       l.reservation_id IS NOT NULL AND EXISTS (
                           SELECT 1 FROM orders.order_line other
                           WHERE other.reservation_id = l.reservation_id AND other.id <> l.id
                       ) AS shared_reference
                FROM orders.order_line l
                JOIN orders.purchase_order o ON o.id = l.order_id
                """
                        + (afterLineId == null ? "" : " WHERE l.id > ?")
                        + " ORDER BY l.id LIMIT ?";
        Object[] parameters =
                afterLineId == null
                        ? new Object[] {limit + 1}
                        : new Object[] {afterLineId, limit + 1};
        var rows =
                jdbc.query(
                        sql,
                        (result, index) -> {
                            UUID lineId = result.getObject("id", UUID.class);
                            UUID reference = result.getObject("reservation_id", UUID.class);
                            Issue issue =
                                    reference == null
                                            ? Issue.MISSING_REFERENCE
                                            : result.getBoolean("shared_reference")
                                                    ? Issue.SHARED_REFERENCE
                                                    : null;
                            return new Row(
                                    lineId,
                                    issue == null
                                            ? null
                                            : new Finding(
                                                    lineId,
                                                    result.getObject("order_id", UUID.class),
                                                    result.getLong("version"),
                                                    result.getString("status"),
                                                    result.getString("product_sku"),
                                                    result.getInt("quantity"),
                                                    reference,
                                                    issue));
                        },
                        parameters);
        boolean more = rows.size() > limit;
        var page = rows.subList(0, Math.min(limit, rows.size()));
        return new ReservationReferenceCensusPage(
                page.size(),
                more ? page.getLast().lineId() : null,
                page.stream().map(Row::finding).filter(java.util.Objects::nonNull).toList());
    }

    private record Row(UUID lineId, Finding finding) {}
}
