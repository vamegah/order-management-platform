package com.example.omp.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class PageResponseTest {
    @Test
    void mapsContentAndPreservesPaginationContract() {
        var page = new PageImpl<>(List.of(3, 4), PageRequest.of(1, 2), 5);
        var response = PageResponse.from(page, value -> "item-" + value);

        assertThat(response.content()).containsExactly("item-3", "item-4");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(5);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.first()).isFalse();
        assertThat(response.last()).isFalse();
    }

    @Test
    void emptyPageHasStableWireFields() {
        var response = PageResponse.from(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
        assertThat(response.first()).isTrue();
        assertThat(response.last()).isTrue();
    }
}
