package com.donotmiss.backend.retrieval;

import com.donotmiss.backend.event.EventEntity;

import java.util.List;
import java.util.Optional;

/**
 * The set of events retrieval searches over. Retrieval and scoring code is the same for every
 * corpus; only where events come from differs:
 * <ul>
 *     <li>{@link LiveEventCorpus}: the database and the live search index.</li>
 *     <li>{@code FrozenEventCorpus}: a versioned fixture file and an evaluation index, so an
 *     evaluation run gives the same candidates next month as it does today.</li>
 * </ul>
 */
public interface EventCorpus {

    /** Short label for traces and evaluation reports, e.g. {@code live} or {@code events-v1}. */
    String name();

    boolean searchEnabled();

    List<EventSearchIndexService.SearchHit> bm25Hits(String query, String category, String benefitType, String location, int limit);

    List<EventSearchIndexService.SearchHit> vectorHits(String query, int limit);

    Optional<EventEntity> find(Long eventId);

    /** Fallback candidates when the search engine returns nothing: every active event matching the filters. */
    List<EventEntity> filtered(String category, String benefitType, String location);

    /** Housekeeping before a search; the live corpus expires overdue events, a frozen one does nothing. */
    default void beforeRetrieve() {
    }
}
