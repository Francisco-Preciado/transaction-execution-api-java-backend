package com.transactionexecution.api.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResultTest {

    @Test
    void totalPagesRoundsUp() {
        assertThat(new PageResult<>(List.of(1, 2), 0, 2, 5).totalPages()).isEqualTo(3);
        assertThat(new PageResult<>(List.of(1, 2), 0, 2, 4).totalPages()).isEqualTo(2);
        assertThat(new PageResult<>(List.of(), 0, 20, 0).totalPages()).isZero();
    }

    @Test
    void mapKeepsPagingMetadata() {
        PageResult<String> mapped = new PageResult<>(List.of(1, 2), 1, 2, 5).map(String::valueOf);

        assertThat(mapped.items()).containsExactly("1", "2");
        assertThat(mapped.page()).isEqualTo(1);
        assertThat(mapped.size()).isEqualTo(2);
        assertThat(mapped.totalItems()).isEqualTo(5);
    }
}
