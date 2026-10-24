package com.donotmiss.backend.recommendation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RecommendationImpressionRepository extends JpaRepository<RecommendationImpressionEntity, Long> {

    Optional<RecommendationImpressionEntity> findFirstByRunIdAndUserIdAndEventId(Long runId, String userId, Long eventId);

    List<RecommendationImpressionEntity> findByRunIdAndUserId(Long runId, String userId);

    /** Latest impression of this event for this student inside the attribution window. */
    Optional<RecommendationImpressionEntity> findFirstByUserIdAndEventIdAndShownAtGreaterThanEqualOrderByShownAtDesc(
            String userId, Long eventId, Instant since);

    /** Latest impression of any event by this organization, for "followed the organization" attribution. */
    @Query("""
            select impression from RecommendationImpressionEntity impression, EventEntity event
            where impression.eventId = event.id
              and impression.userId = :userId
              and event.organizationName = :organizationName
              and impression.shownAt >= :since
            order by impression.shownAt desc
            """)
    List<RecommendationImpressionEntity> findRecentForOrganization(@Param("userId") String userId,
                                                                   @Param("organizationName") String organizationName,
                                                                   @Param("since") Instant since,
                                                                   Pageable pageable);
}
