package com.donotmiss.backend.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PROBLEM_LOG 075: the index fell behind the published events and nobody noticed. */
class SearchIndexStartupCheckTest {
    private final EventSearchIndexService index = mock(EventSearchIndexService.class);
    private final SearchIndexStartupCheck check = new SearchIndexStartupCheck(index);

    @Test
    void rebuildsWhenTheIndexDoesNotMatch() {
        when(index.isEnabled()).thenReturn(true);
        when(index.checkConsistency()).thenReturn(Optional.of(
                new EventSearchIndexService.Consistency(12, 1, List.of(61L, 62L), List.of(58L))));

        check.checkIndex();

        verify(index).reindexAll();
    }

    @Test
    void leavesAMatchingIndexAlone() {
        when(index.isEnabled()).thenReturn(true);
        when(index.checkConsistency()).thenReturn(Optional.of(
                new EventSearchIndexService.Consistency(12, 12, List.of(), List.of())));

        check.checkIndex();

        verify(index, never()).reindexAll();
    }

    @Test
    void doesNothingButWarnWhenSearchIsOff() {
        when(index.isEnabled()).thenReturn(false);

        check.checkIndex();

        verify(index, never()).checkConsistency();
        verify(index, never()).reindexAll();
    }
}
