package com.example.omp.product.domain;

import static org.assertj.core.api.Assertions.*;

import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SkuTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "-ABC", "_ABC", "AB C", "SKU/ONE", "商品-123"})
    void rejectsMalformedBusinessIdentifiers(String raw) {
        assertThatThrownBy(() -> Sku.of(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizedIdentifiersDeduplicateAsCollectionKeys() {
        var identities = new HashSet<Sku>();
        identities.add(Sku.of(" sku_001 "));
        identities.add(Sku.of("SKU_001"));
        assertThat(identities).hasSize(1).contains(Sku.of("sKu_001"));
        assertThat(identities).doesNotContain(Sku.of("SKU_002"));
        assertThat(identities.iterator().next().value()).isEqualTo("SKU_001");
    }

    @Test
    void enforcesExactLengthBoundaries() {
        assertThat(Sku.of("a123").value()).isEqualTo("A123");
        assertThat(Sku.of("a".repeat(64)).value()).hasSize(64);
        assertThatThrownBy(() -> Sku.of("a".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sku.of(null)).isInstanceOf(NullPointerException.class);
    }
}
