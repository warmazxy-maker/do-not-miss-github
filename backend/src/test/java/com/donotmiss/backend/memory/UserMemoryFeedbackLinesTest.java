package com.donotmiss.backend.memory;

import com.donotmiss.backend.recommendation.RecommendationFeedbackDtos.FeedbackSignal;
import com.donotmiss.backend.recommendation.RecommendationFeedbackType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Without the model, recommendation feedback reaches the profile verbatim, one line per event. */
class UserMemoryFeedbackLinesTest {

    @Test
    void acceptedAndRejectedAreSeparatedOneLinePerEventNewestFirst() {
        List<FeedbackSignal> newestFirst = List.of(
                signal(RecommendationFeedbackType.NOT_RELEVANT, 58L, "后端工程实战夜", "时间对不上"),
                signal(RecommendationFeedbackType.RESERVED, 31L, "Java 项目实战", null),
                signal(RecommendationFeedbackType.INTERESTED, 31L, "Java 项目实战", null),
                signal(RecommendationFeedbackType.RESERVATION_CANCELLED, 12L, null, null));

        assertEquals(List.of("「Java 项目实战」：已预约"),
                UserMemoryService.rawFeedbackLines(newestFirst, true));
        assertEquals(List.of("「后端工程实战夜」：不相关（原因：时间对不上）", "「活动 #12」：取消了预约"),
                UserMemoryService.rawFeedbackLines(newestFirst, false));
    }

    @Test
    void atMostFiveLines() {
        List<FeedbackSignal> many = java.util.stream.LongStream.rangeClosed(1, 8)
                .mapToObj(id -> signal(RecommendationFeedbackType.INTERESTED, id, "活动" + id, null))
                .toList();

        assertEquals(5, UserMemoryService.rawFeedbackLines(many, true).size());
        assertEquals(List.of(), UserMemoryService.rawFeedbackLines(List.of(), false));
    }

    private static FeedbackSignal signal(RecommendationFeedbackType type, Long eventId, String title, String reason) {
        return new FeedbackSignal(type, type.positive(), type.explicit(), eventId, title, "企业", "大阪", null, reason, Instant.now());
    }
}
