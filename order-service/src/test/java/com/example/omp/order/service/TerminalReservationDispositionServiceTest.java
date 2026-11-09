package com.example.omp.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.omp.order.dto.TerminalReservationDispositionRequest;
import com.example.omp.order.error.*;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;

class TerminalReservationDispositionServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TerminalReservationDispositionService service =
            new TerminalReservationDispositionService(jdbc);
    private final UUID order = UUID.randomUUID(), line = UUID.randomUUID();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "operator-identity-too-long"})
    void invalidOperatorFailsBeforeDatabaseAccess(String actor) {
        if ("operator-identity-too-long".equals(actor)) actor = "x".repeat(201);
        String identity = actor;
        assertThatThrownBy(() -> service.record(order, request(0L, List.of(line)), identity))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(jdbc);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidPlansFailBeforeDatabaseAccess(TerminalReservationDispositionRequest request) {
        assertThatThrownBy(() -> service.record(order, request, "admin"))
                .isInstanceOf(InvalidOrderDataException.class);
        verifyNoInteractions(jdbc);
    }

    static Stream<TerminalReservationDispositionRequest> invalidRequests() {
        var line = UUID.randomUUID();
        var ids = List.of(line);
        return Stream.of(
                null,
                new TerminalReservationDispositionRequest(null, "Reason", "Evidence", ids),
                new TerminalReservationDispositionRequest(-1L, "Reason", "Evidence", ids),
                new TerminalReservationDispositionRequest(0L, null, "Evidence", ids),
                new TerminalReservationDispositionRequest(0L, " ", "Evidence", ids),
                new TerminalReservationDispositionRequest(0L, "x".repeat(501), "Evidence", ids),
                new TerminalReservationDispositionRequest(0L, "Reason", null, ids),
                new TerminalReservationDispositionRequest(0L, "Reason", " ", ids),
                new TerminalReservationDispositionRequest(0L, "Reason", "x".repeat(2001), ids),
                new TerminalReservationDispositionRequest(0L, "Reason", "Evidence", null),
                new TerminalReservationDispositionRequest(0L, "Reason", "Evidence", List.of()),
                new TerminalReservationDispositionRequest(
                        0L, "Reason", "Evidence", Collections.nCopies(101, line)),
                new TerminalReservationDispositionRequest(
                        0L, "Reason", "Evidence", Arrays.asList(line, null)),
                new TerminalReservationDispositionRequest(
                        0L, "Reason", "Evidence", List.of(line, line)));
    }

    @Test
    void inspectionReturnsOriginalAuditAndEligibility() throws Exception {
        var row = row("DELIVERED", true);
        UUID audit = UUID.randomUUID();
        when(row.getObject("audit_id", UUID.class)).thenReturn(audit);
        when(row.getString("policy")).thenReturn("IRRECOVERABLE_LEGACY_REFERENCE");
        when(row.getLong("previous_order_version")).thenReturn(3L);
        when(row.getString("actor")).thenReturn("admin");
        when(row.getString("reason")).thenReturn("Reason");
        when(row.getString("evidence")).thenReturn("Evidence");
        when(row.getTimestamp("recorded_at"))
                .thenReturn(Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        returning(List.of(row));
        var view = service.inspect(order);
        assertThat(view.orderId()).isEqualTo(order);
        assertThat(view.version()).isEqualTo(4);
        assertThat(view.status()).isEqualTo("DELIVERED");
        assertThat(view.recorded()).isZero();
        assertThat(view.lines())
                .singleElement()
                .satisfies(
                        value -> {
                            assertThat(value.lineId()).isEqualTo(line);
                            assertThat(value.sku()).isEqualTo("SKU");
                            assertThat(value.quantity()).isEqualTo(2);
                            assertThat(value.eligible()).isTrue();
                            assertThat(value.audit().id()).isEqualTo(audit);
                            assertThat(value.audit().previousOrderVersion()).isEqualTo(3);
                            assertThat(value.audit().recordedAt())
                                    .isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
                        });
        verify(jdbc).query(anyString(), any(RowMapper.class), eq(order));
    }

    @Test
    void unknownAndOversizedOrdersAreRejected() throws Exception {
        returning(List.of());
        assertThatThrownBy(() -> service.inspect(order)).isInstanceOf(OrderNotFoundException.class);
        returning(Collections.nCopies(101, row("DELIVERED", true)));
        assertThatThrownBy(() -> service.inspect(order))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    @Test
    void anOrderWithoutLinesCanBeInspectedButCannotReceiveAnException() throws Exception {
        var empty = row("DELIVERED", false);
        when(empty.getObject("line_id", UUID.class)).thenReturn(null);
        returning(List.of(empty));
        assertThat(service.inspect(order).lines()).isEmpty();
        lockExists();
        assertThatThrownBy(() -> service.record(order, request(4L, List.of(line)), "admin"))
                .isInstanceOf(InvalidOrderDataException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void aMissingLockedOrderReturnsNotFound() {
        assertThatThrownBy(() -> service.record(order, request(0L, List.of(line)), "admin"))
                .isInstanceOf(OrderNotFoundException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "PLACED", "CONFIRMED", "FULFILLING", "SHIPPED"})
    void everyNonterminalStateRejectsDisposition(String status) throws Exception {
        lockExists();
        returning(List.of(row(status, false)));
        assertThatThrownBy(() -> service.record(order, request(4L, List.of(line)), "admin"))
                .isInstanceOf(InvalidOrderStateException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void ineligibleStaleAndPartialPlansPreserveState() throws Exception {
        lockExists();
        returning(List.of(row("CANCELLED", false)));
        assertThatThrownBy(() -> service.record(order, request(4L, List.of(line)), "admin"))
                .isInstanceOf(InvalidOrderStateException.class);
        returning(List.of(row("CANCELLED", true)));
        assertThatThrownBy(() -> service.record(order, request(3L, List.of(line)), "admin"))
                .isInstanceOf(InvalidOrderStateException.class);
        assertThatThrownBy(
                        () ->
                                service.record(
                                        order, request(4L, List.of(UUID.randomUUID())), "admin"))
                .isInstanceOf(InvalidOrderDataException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    private void lockExists() {
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(order))).thenReturn(List.of(order));
    }

    private TerminalReservationDispositionRequest request(Long version, List<UUID> lines) {
        return new TerminalReservationDispositionRequest(version, "Reason", "Evidence", lines);
    }

    private ResultSet row(String status, boolean eligible) throws Exception {
        var row = mock(ResultSet.class);
        when(row.getObject("line_id", UUID.class)).thenReturn(line);
        when(row.getLong("version")).thenReturn(4L);
        when(row.getString("status")).thenReturn(status);
        when(row.getString("product_sku")).thenReturn("SKU");
        when(row.getInt("quantity")).thenReturn(2);
        when(row.getBoolean("eligible")).thenReturn(eligible);
        return row;
    }

    private void returning(List<ResultSet> rows) {
        doAnswer(
                        call -> {
                            RowMapper<?> mapper = call.getArgument(1);
                            var mapped = new ArrayList<>();
                            for (int index = 0; index < rows.size(); index++)
                                mapped.add(mapper.mapRow(rows.get(index), index));
                            return mapped;
                        })
                .when(jdbc)
                .query(
                        anyString(),
                        org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
                        any(Object[].class));
    }
}
