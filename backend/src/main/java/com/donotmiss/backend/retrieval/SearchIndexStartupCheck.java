package com.donotmiss.backend.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * PROBLEM_LOG 075: a backend started without SEARCH_ENABLED consumed every index message without
 * writing anything, and AI search silently fell back to the database for days. At every start:
 * say loudly when search is off, and rebuild the index when it does not match the published events.
 */
@Component
public class SearchIndexStartupCheck {
    private static final Logger log = LoggerFactory.getLogger(SearchIndexStartupCheck.class);

    private final EventSearchIndexService indexService;

    public SearchIndexStartupCheck(EventSearchIndexService indexService) {
        this.indexService = indexService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkIndex() {
        if (!indexService.isEnabled()) {
            log.warn("SEARCH_ENABLED is not true: AI search uses the database fallback only, and event index "
                    + "messages consumed by this backend are not written. Check that the backend was started "
                    + "from the do-not-miss-backend folder so ./.env is read.");
            return;
        }
        try {
            indexService.checkConsistency().ifPresent(result -> {
                if (result.consistent()) {
                    log.info("Search index matches the published events ({} events).", result.expected());
                    return;
                }
                log.warn("Search index out of date: expected {}, indexed {}, missing {}, stale {}. Rebuilding.",
                        result.expected(), result.indexed(), result.missing(), result.stale());
                int count = indexService.reindexAll();
                log.info("Search index rebuilt with {} events.", count);
            });
        } catch (RuntimeException ex) {
            // Never block the start: search still works through the database fallback.
            log.warn("Search index startup check failed: {}", ex.getMessage());
        }
    }
}
