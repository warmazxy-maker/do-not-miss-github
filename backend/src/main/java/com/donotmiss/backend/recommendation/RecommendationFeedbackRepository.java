package com.donotmiss.backend.recommendation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface RecommendationFeedbackRepository extends JpaRepository<RecommendationFeedbackEntity, Long> {

    boolean existsByImpressionIdAndFeedbackType(Long impressionId, RecommendationFeedbackType feedbackType);

    List<RecommendationFeedbackEntity> findByImpressionIdAndExplicitFeedbackTrue(Long impressionId);

    List<RecommendationFeedbackEntity> findByImpressionIdIn(Collection<Long> impressionIds);

    /** One student's recent reactions, newest first, as input to their profile. */
    List<RecommendationFeedbackEntity> findTop40ByUserIdAndUpdatedAtAfterOrderByUpdatedAtDesc(String userId, Instant after);

    /** All explicit clicks of one student, newest last, to decide each event's latest verdict. */
    List<RecommendationFeedbackEntity> findByUserIdAndExplicitFeedbackTrueOrderByUpdatedAtAsc(String userId);
}
