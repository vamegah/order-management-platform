package com.example.omp.shared.web.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.omp.shared.web.error.InvalidPaginationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Sort;

class PageableResolverTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                ",",
                "createdAt,asc,",
                "createdAt,",
                "createdAt,asc,createdAt,desc",
                "updatedAt,desc,,asc"
            })
    void rejectsEmptyTokensAndDuplicateSortFields(String sort) {
        assertThatThrownBy(() -> resolver.resolve(0, 10, sort))
                .isInstanceOf(InvalidPaginationException.class);
    }

    @Test
    void boundsSortInputAndChecksDefaultSortAgainstAllowlist() {
        assertThatThrownBy(() -> resolver.resolve(0, 10, "createdAt,asc,".repeat(100)))
                .isInstanceOf(InvalidPaginationException.class)
                .hasMessageContaining("too long");
        properties.setAllowedSortFields(List.of("name"));
        assertThatThrownBy(() -> resolver.resolve(0, 10, null))
                .isInstanceOf(InvalidPaginationException.class)
                .hasMessageContaining("Cannot sort");
    }

    private final PaginationProperties properties = new PaginationProperties();
    private final PageableResolver resolver = new PageableResolver(properties);

    @Test
    void defaultsUseBoundedFirstPageAndDescendingCreationDate() {
        var result = resolver.resolve(null, null, null);
        assertThat(result.getPageNumber()).isZero();
        assertThat(result.getPageSize()).isEqualTo(20);
        assertThat(result.getSort()).containsExactly(Sort.Order.desc("createdAt"));
        assertThat(resolver.resolve(0, 1, " ").getSort()).isEqualTo(result.getSort());
    }

    @Test
    void configuredBoundsAndSortFieldsApplyToExplicitAndDefaultSizes() {
        properties.setDefaultSize(5);
        properties.setMaxSize(10);
        properties.setAllowedSortFields(List.of("name", "price"));

        assertThat(resolver.resolve(null, null, "name").getPageSize()).isEqualTo(5);
        var result = resolver.resolve(2, 10, "price, DESC, name,asc");
        assertThat(result.getOffset()).isEqualTo(20);
        assertThat(result.getSort())
                .containsExactly(Sort.Order.desc("price"), Sort.Order.asc("name"));
    }

    @Test
    void rejectsNegativePage() {
        assertThatThrownBy(() -> resolver.resolve(-1, 10, null))
                .isInstanceOf(InvalidPaginationException.class)
                .hasMessageContaining("Page number");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101, Integer.MAX_VALUE})
    void rejectsUnboundedSizes(int size) {
        assertThatThrownBy(() -> resolver.resolve(0, size, null))
                .isInstanceOf(InvalidPaginationException.class)
                .hasMessageContaining("Page size");
    }

    @ParameterizedTest
    @ValueSource(strings = {"password,asc", "createdAt,sideways", "createdAt,asc,secret,desc"})
    void rejectsUnknownSortFieldsAndDirections(String sort) {
        assertThatThrownBy(() -> resolver.resolve(0, 10, sort))
                .isInstanceOf(InvalidPaginationException.class);
    }
}
