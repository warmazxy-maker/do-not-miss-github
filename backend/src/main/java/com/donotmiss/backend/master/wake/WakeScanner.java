package com.donotmiss.backend.master.wake;

import com.donotmiss.backend.capabilityv2.RankPromotionPolicy;
import com.donotmiss.backend.master.goal.TandemGoalEntity;
import com.donotmiss.backend.master.goal.TandemGoalMilestoneEntity;
import com.donotmiss.backend.master.goal.TandemGoalMilestoneRepository;
import com.donotmiss.backend.master.goal.TandemGoalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * F4: finds things worth telling a student and records them as wake triggers. It only reads the
 * other modules' tables, so check-in, scoring and goals stay exactly as they were ("existing
 * features unchanged"): new achievement records (check-ins, completed challenges, goal steps),
 * capability ranks higher than last seen, events starting tomorrow, goals due for a review.
 *
 * <p>Saying something is the processor's job; the dedupe key here makes sure each thing is
 * recorded once, ever.</p>
 */
@Component
public class WakeScanner {
    private static final Logger log = LoggerFactory.getLogger(WakeScanner.class);
    /** Things that happen close together become one message (F4 decision 4). */
    static final Duration MERGE_WINDOW = Duration.ofMinutes(10);
    static final Duration REVIEW_AFTER = Duration.ofDays(7);
    /** Reminders and review invitations are not sent at night. */
    static final int DAY_STARTS_AT = 8;

    private final JdbcTemplate jdbc;
    private final WakeTriggerRepository triggers;
    private final TandemGoalRepository goals;
    private final TandemGoalMilestoneRepository steps;
    private final ObjectMapper objectMapper;
    private final ZoneId zone;
    private final boolean enabled;

    public WakeScanner(JdbcTemplate jdbc, WakeTriggerRepository triggers, TandemGoalRepository goals,
                       TandemGoalMilestoneRepository steps, ObjectMapper objectMapper,
                       @Value("${app.mcp.default-timezone:Asia/Tokyo}") String timezone,
                       @Value("${app.tandem.wake.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.triggers = triggers;
        this.goals = goals;
        this.steps = steps;
        this.objectMapper = objectMapper;
        this.zone = ZoneId.of(timezone);
        this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "${app.tandem.wake.initial-delay-ms:30000}",
            fixedDelayString = "${app.tandem.wake.scan-delay-ms:60000}")
    public void scanFrequent() {
        if (!enabled) {
            return;
        }
        run("achievements", this::scanAchievements);
        run("ranks", this::scanRanks);
    }

    @Scheduled(initialDelayString = "${app.tandem.wake.initial-delay-ms:30000}",
            fixedDelayString = "${app.tandem.wake.daily-scan-delay-ms:600000}")
    public void scanDaily() {
        if (!enabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(zone);
        run("tomorrow", () -> scanTomorrow(now));
        run("reviews", () -> scanReviews(Instant.now(), now));
    }

    private void run(String name, Runnable scan) {
        try {
            scan.run();
        } catch (RuntimeException ex) {
            log.warn("Tandem wake scan '{}' failed; it runs again on the next tick.", name, ex);
        }
    }

    // ---- check-ins, completed challenges, goal steps ----

    /** New achievement records since the last look. The first run only sets the starting point. */
    void scanAchievements() {
        Long cursor = cursor("achievement");
        if (cursor == null) {
            setCursor("achievement", jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM achievement_records", Long.class));
            return;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, user_id, source_type, source_id, event_id, event_title FROM achievement_records"
                        + " WHERE id > ? ORDER BY id LIMIT 200", cursor);
        Instant now = Instant.now();
        long last = cursor;
        for (Map<String, Object> row : rows) {
            last = ((Number) row.get("id")).longValue();
            String userId = (String) row.get("user_id");
            boolean event = "EVENT".equals(row.get("source_type"));
            long refId = ((Number) row.get(event ? "event_id" : "source_id")).longValue();
            String title = (String) row.get("event_title");
            if (event) {
                ObjectNode payload = objectMapper.createObjectNode().put("eventId", refId).put("title", title);
                raise(userId, WakeKind.CHECK_IN, "checkin:" + refId, payload, now.plus(MERGE_WINDOW));
            }
            TandemGoalMilestoneEntity.Kind kind = event ? TandemGoalMilestoneEntity.Kind.EVENT : TandemGoalMilestoneEntity.Kind.CHALLENGE;
            for (TandemGoalMilestoneEntity step : steps.findByUserIdAndKindAndRefId(userId, kind, refId)) {
                TandemGoalEntity goal = goals.findById(step.getGoalId()).orElse(null);
                if (goal == null || goal.getStatus() != TandemGoalEntity.Status.ACTIVE) {
                    continue;
                }
                ObjectNode payload = objectMapper.createObjectNode().put("goalId", goal.getId())
                        .put("goalTitle", goal.getTitle()).put("kind", kind.name()).put("refId", refId)
                        .put("stepTitle", step.getTitle());
                raise(userId, WakeKind.GOAL_STEP, "goal-step:" + goal.getId() + ":" + kind + ":" + refId, payload,
                        now.plus(MERGE_WINDOW));
            }
        }
        if (last != cursor) {
            setCursor("achievement", last);
        }
    }

    // ---- capability rank-ups (including the ones applied after a passed review) ----

    /**
     * Compares each capability's current rank with the last one seen. The first run only records
     * what is there; a bucket seen for the first time is recorded silently too.
     */
    void scanRanks() {
        Map<String, String> seen = new HashMap<>();
        jdbc.query("SELECT user_id, bucket_id, rank_name FROM tandem_wake_ranks",
                (java.sql.ResultSet rs) -> {
                    seen.put(rs.getString("user_id") + "/" + rs.getLong("bucket_id"), rs.getString("rank_name"));
                });
        boolean baseline = cursor("rank-baseline") == null;
        Instant now = Instant.now();
        List<Map<String, Object>> states = jdbc.queryForList(
                "SELECT s.user_id, s.bucket_id, s.rank_name, b.display_name FROM user_capability_v2_states s"
                        + " JOIN capability_v2_buckets b ON b.id = s.bucket_id");
        for (Map<String, Object> state : states) {
            String userId = (String) state.get("user_id");
            long bucketId = ((Number) state.get("bucket_id")).longValue();
            String rank = (String) state.get("rank_name");
            String old = seen.get(userId + "/" + bucketId);
            if (rank == null || rank.equals(old)) {
                continue;
            }
            if (!baseline && old != null
                    && RankPromotionPolicy.RANK_ORDER.indexOf(rank) > RankPromotionPolicy.RANK_ORDER.indexOf(old)) {
                ObjectNode payload = objectMapper.createObjectNode().put("bucketId", bucketId)
                        .put("bucketName", (String) state.get("display_name")).put("fromRank", old).put("toRank", rank);
                raise(userId, WakeKind.RANK_UP, "rank:" + bucketId + ":" + rank, payload, now.plus(MERGE_WINDOW));
            }
            jdbc.update("INSERT INTO tandem_wake_ranks (user_id, bucket_id, rank_name, updated_at) VALUES (?, ?, ?, ?)"
                    + " ON DUPLICATE KEY UPDATE rank_name = VALUES(rank_name), updated_at = VALUES(updated_at)",
                    userId, bucketId, rank, LocalDateTime.now(zone));
        }
        if (baseline) {
            setCursor("rank-baseline", 1L);
        }
    }

    // ---- reminders and review invitations, in the daytime ----

    void scanTomorrow(LocalDateTime now) {
        if (now.getHour() < DAY_STARTS_AT) {
            return;
        }
        LocalDate tomorrow = now.toLocalDate().plusDays(1);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT r.id AS reservation_id, r.user_id, e.id AS event_id, e.title, e.start_time, e.end_time, e.location"
                        + " FROM reservations r JOIN events e ON e.id = r.event_id"
                        + " WHERE r.status = 'RESERVED' AND e.start_time >= ? AND e.start_time < ?",
                tomorrow.atStartOfDay(), tomorrow.plusDays(1).atStartOfDay());
        Instant soon = Instant.now().plus(Duration.ofMinutes(1));
        for (Map<String, Object> row : rows) {
            long reservationId = ((Number) row.get("reservation_id")).longValue();
            ObjectNode payload = objectMapper.createObjectNode().put("reservationId", reservationId)
                    .put("eventId", ((Number) row.get("event_id")).longValue()).put("title", (String) row.get("title"))
                    .put("start", String.valueOf(toLocal(row.get("start_time"))))
                    .put("end", String.valueOf(toLocal(row.get("end_time"))))
                    .put("location", (String) row.get("location"));
            raise((String) row.get("user_id"), WakeKind.EVENT_TOMORROW, "tomorrow:" + reservationId + ":" + tomorrow,
                    payload, soon);
        }
    }

    /** Replaces F3's "ask once while chatting": the invitation now arrives as a message. */
    void scanReviews(Instant now, LocalDateTime localNow) {
        if (localNow.getHour() < DAY_STARTS_AT) {
            return;
        }
        for (TandemGoalEntity goal : goals.findByStatus(TandemGoalEntity.Status.ACTIVE)) {
            Instant since = goal.getLastReviewedAt() == null ? goal.getCreatedAt() : goal.getLastReviewedAt();
            if (since.plus(REVIEW_AFTER).isAfter(now)) {
                continue;
            }
            ObjectNode payload = objectMapper.createObjectNode().put("goalId", goal.getId())
                    .put("goalTitle", goal.getTitle()).put("since", since.toString());
            raise(goal.getUserId(), WakeKind.GOAL_REVIEW, "review:" + goal.getId() + ":" + since.toEpochMilli(),
                    payload, now.plus(Duration.ofMinutes(1)));
        }
    }

    // ---- helpers ----

    /** Records a trigger unless the same thing was recorded before (decision 4: never said twice). */
    boolean raise(String userId, WakeKind kind, String dedupeKey, ObjectNode payload, Instant processAfter) {
        if (triggers.existsByUserIdAndDedupeKey(userId, dedupeKey)) {
            return false;
        }
        try {
            triggers.save(WakeTriggerEntity.create(userId, kind, dedupeKey, payload.toString(), Instant.now(), processAfter));
            return true;
        } catch (DataIntegrityViolationException raced) {
            return false;
        }
    }

    private Long cursor(String name) {
        List<Long> values = jdbc.queryForList("SELECT position_value FROM tandem_wake_cursors WHERE name = ?", Long.class, name);
        return values.isEmpty() ? null : values.get(0);
    }

    private void setCursor(String name, long value) {
        jdbc.update("INSERT INTO tandem_wake_cursors (name, position_value, updated_at) VALUES (?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE position_value = VALUES(position_value), updated_at = VALUES(updated_at)",
                name, value, LocalDateTime.now(zone));
    }

    private static Object toLocal(Object value) {
        return value instanceof java.sql.Timestamp timestamp ? timestamp.toLocalDateTime() : value;
    }
}
