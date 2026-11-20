package com.donotmiss.backend.master.wake;

import com.donotmiss.backend.master.goal.TandemGoalEntity;
import com.donotmiss.backend.master.goal.TandemGoalMilestoneRepository;
import com.donotmiss.backend.master.goal.TandemGoalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** F4: the scanner records each thing once, and review invitations only in the daytime. */
class WakeScannerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WakeTriggerRepository triggers = mock(WakeTriggerRepository.class);
    private final TandemGoalRepository goals = mock(TandemGoalRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final WakeScanner scanner = new WakeScanner(jdbc, triggers, goals,
            mock(TandemGoalMilestoneRepository.class), MAPPER, "Asia/Tokyo", true);

    private TandemGoalEntity goal(long id, Instant created) throws Exception {
        TandemGoalEntity goal = TandemGoalEntity.create("student", null);
        goal.setTitle("练口语");
        Field idField = TandemGoalEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(goal, id);
        Field createdField = TandemGoalEntity.class.getDeclaredField("createdAt");
        createdField.setAccessible(true);
        createdField.set(goal, created);
        return goal;
    }

    @Test
    void theSameThingIsRecordedOnlyOnce() {
        when(triggers.existsByUserIdAndDedupeKey("student", "checkin:65")).thenReturn(false, true);

        assertThat(scanner.raise("student", WakeKind.CHECK_IN, "checkin:65", MAPPER.createObjectNode(), Instant.now())).isTrue();
        assertThat(scanner.raise("student", WakeKind.CHECK_IN, "checkin:65", MAPPER.createObjectNode(), Instant.now())).isFalse();
        verify(triggers).save(any());
    }

    @Test
    void aGoalNotReviewedForAWeekGetsOneInvitationPerQuietSpell() throws Exception {
        Instant now = Instant.now();
        TandemGoalEntity stale = goal(1, now.minus(Duration.ofDays(9)));
        TandemGoalEntity fresh = goal(2, now.minus(Duration.ofDays(2)));
        when(goals.findByStatus(TandemGoalEntity.Status.ACTIVE)).thenReturn(List.of(stale, fresh));

        scanner.scanReviews(now, LocalDateTime.of(2026, 11, 20, 10, 0));

        ArgumentCaptor<WakeTriggerEntity> saved = ArgumentCaptor.forClass(WakeTriggerEntity.class);
        verify(triggers).save(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo(WakeKind.GOAL_REVIEW);
        // Keyed by when the quiet spell started: after the next review, a new spell gets a new key.
        assertThat(saved.getValue().getDedupeKey()).isEqualTo("review:1:" + stale.getCreatedAt().toEpochMilli());
    }

    /** Ranks last seen, and the ranks the capability table holds now. */
    private void ranks(boolean baselineDone, List<String[]> seen, List<java.util.Map<String, Object>> now) throws Exception {
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("tandem_wake_cursors"), org.mockito.ArgumentMatchers.eq(Long.class),
                org.mockito.ArgumentMatchers.eq("rank-baseline"))).thenReturn(baselineDone ? List.of(1L) : List.of());
        org.mockito.Mockito.doAnswer(call -> {
            org.springframework.jdbc.core.RowCallbackHandler handler = call.getArgument(1);
            for (String[] row : seen) {
                java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                when(rs.getString("user_id")).thenReturn(row[0]);
                when(rs.getLong("bucket_id")).thenReturn(Long.parseLong(row[1]));
                when(rs.getString("rank_name")).thenReturn(row[2]);
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(org.mockito.ArgumentMatchers.contains("FROM tandem_wake_ranks"),
                any(org.springframework.jdbc.core.RowCallbackHandler.class));
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("user_capability_v2_states"))).thenReturn(now);
    }

    private static java.util.Map<String, Object> state(long bucket, String rank) {
        return java.util.Map.of("user_id", "student", "bucket_id", bucket, "rank_name", rank, "display_name", "日语");
    }

    @Test
    void aHigherRankThanLastSeenIsARankUpButAFirstSightingIsNot() throws Exception {
        ranks(true, List.<String[]>of(new String[]{"student", "1", "FOUNDATION"}, new String[]{"student", "2", "PROFICIENT"}),
                List.of(state(1, "DEVELOPING"), state(2, "DEVELOPING"), state(3, "FOUNDATION")));

        scanner.scanRanks();

        // Bucket 1 went up; bucket 2 went down (silent); bucket 3 is new (silent).
        ArgumentCaptor<WakeTriggerEntity> saved = ArgumentCaptor.forClass(WakeTriggerEntity.class);
        verify(triggers).save(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo(WakeKind.RANK_UP);
        assertThat(saved.getValue().getDedupeKey()).isEqualTo("rank:1:DEVELOPING");
        assertThat(saved.getValue().getPayloadJson()).contains("\"fromRank\":\"FOUNDATION\"");
    }

    @Test
    void theFirstRunOnlyRecordsWhatIsThere() throws Exception {
        ranks(false, List.of(), List.of(state(1, "PROFICIENT")));

        scanner.scanRanks();

        verify(triggers, never()).save(any());
    }

    @Test
    void nothingIsRaisedAtNight() throws Exception {
        when(goals.findByStatus(TandemGoalEntity.Status.ACTIVE))
                .thenReturn(List.of(goal(1, Instant.now().minus(Duration.ofDays(9)))));

        scanner.scanReviews(Instant.now(), LocalDateTime.of(2026, 11, 20, 3, 0));

        verify(triggers, never()).existsByUserIdAndDedupeKey(anyString(), anyString());
    }
}
