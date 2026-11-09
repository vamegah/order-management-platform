package com.example.omp.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.order.dto.ReservationReferenceCensusPage.Issue;
import com.example.omp.order.error.InvalidOrderDataException;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ReservationReferenceCensusTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UUID order = UUID.randomUUID();

    @Test
    void lookaheadDoesNotLeakAnotherFindingAndCursorUsesTheLastScannedLine() throws Exception {
        UUID first = UUID.randomUUID(), extra = UUID.randomUUID();
        returning(List.of(row(first, null, false), row(extra, UUID.randomUUID(), true)));
        var result = new ReservationReferenceCensus(jdbc).scan(null, 1);
        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.nextAfterLineId()).isEqualTo(first);
        assertThat(result.findings())
                .singleElement()
                .satisfies(
                        f -> {
                            assertThat(f.lineId()).isEqualTo(first);
                            assertThat(f.orderId()).isEqualTo(order);
                            assertThat(f.orderVersion()).isEqualTo(3);
                            assertThat(f.status()).isEqualTo("DRAFT");
                            assertThat(f.sku()).isEqualTo("SKU");
                            assertThat(f.quantity()).isEqualTo(2);
                            assertThat(f.reservationId()).isNull();
                            assertThat(f.issue()).isEqualTo(Issue.MISSING_REFERENCE);
                        });
        verify(jdbc).query(anyString(), any(RowMapper.class), eq(2));
    }

    @Test
    void finalPageOmitsHealthyLinesAndIncludesSharedReferenceDetails() throws Exception {
        UUID cursor = UUID.randomUUID(),
                sharedLine = UUID.randomUUID(),
                reference = UUID.randomUUID();
        returning(
                List.of(
                        row(UUID.randomUUID(), UUID.randomUUID(), false),
                        row(sharedLine, reference, true)));
        var result = new ReservationReferenceCensus(jdbc).scan(cursor, 100);
        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.nextAfterLineId()).isNull();
        assertThat(result.findings())
                .singleElement()
                .satisfies(
                        f -> {
                            assertThat(f.lineId()).isEqualTo(sharedLine);
                            assertThat(f.reservationId()).isEqualTo(reference);
                            assertThat(f.issue()).isEqualTo(Issue.SHARED_REFERENCE);
                        });
        verify(jdbc).query(anyString(), any(RowMapper.class), eq(cursor), eq(101));
    }

    @Test
    void emptyPageEndsTheTraversal() {
        returning(List.of());
        var result = new ReservationReferenceCensus(jdbc).scan(UUID.randomUUID(), 50);
        assertThat(result.scanned()).isZero();
        assertThat(result.nextAfterLineId()).isNull();
        assertThat(result.findings()).isEmpty();
    }

    private ResultSet row(UUID lineId, UUID reference, boolean shared) throws Exception {
        var row = mock(ResultSet.class);
        when(row.getObject("id", UUID.class)).thenReturn(lineId);
        when(row.getObject("order_id", UUID.class)).thenReturn(order);
        when(row.getObject("reservation_id", UUID.class)).thenReturn(reference);
        when(row.getLong("version")).thenReturn(3L);
        when(row.getString("status")).thenReturn("DRAFT");
        when(row.getString("product_sku")).thenReturn("SKU");
        when(row.getInt("quantity")).thenReturn(2);
        when(row.getBoolean("shared_reference")).thenReturn(shared);
        return row;
    }

    private void returning(List<ResultSet> rows) {
        when(jdbc.query(
                        anyString(),
                        org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
                        any(Object[].class)))
                .thenAnswer(
                        call -> {
                            RowMapper<?> mapper = call.getArgument(1);
                            var result = new ArrayList<>();
                            for (int index = 0; index < rows.size(); index++)
                                result.add(mapper.mapRow(rows.get(index), index));
                            return result;
                        });
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101, Integer.MAX_VALUE})
    void invalidBatchBoundsFailBeforeDatabaseAccess(int limit) {
        var jdbc = mock(JdbcTemplate.class);
        assertThatThrownBy(() -> new ReservationReferenceCensus(jdbc).scan(null, limit))
                .isInstanceOf(InvalidOrderDataException.class);
        verifyNoInteractions(jdbc);
    }
}
