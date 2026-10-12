package com.transactionexecution.api.domain.model;

import com.transactionexecution.api.domain.exception.InvalidPageQueryException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageQueryTest {

    @ParameterizedTest
    @CsvSource({"0, 1", "0, 20", "5, 100"})
    void acceptsValidValues(int page, int size) {
        assertThat(new PageQuery(page, size)).extracting(PageQuery::page, PageQuery::size).containsExactly(page, size);
    }

    @ParameterizedTest
    @CsvSource({"-1, 20", "0, 0", "0, -5", "0, 101"})
    void rejectsOutOfRangeValues(int page, int size) {
        assertThatThrownBy(() -> new PageQuery(page, size))
                .isInstanceOf(InvalidPageQueryException.class)
                .extracting("code").isEqualTo(InvalidPageQueryException.INVALID_PAGE_QUERY);
    }

    @Test
    void firstPageUsesDefaultSize() {
        assertThat(PageQuery.first()).isEqualTo(new PageQuery(0, PageQuery.DEFAULT_SIZE));
    }
}
