package com.donotmiss.backend.ops;

import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.common.CurrentUser;
import com.donotmiss.backend.mq.DomainEventOutboxEntity;
import com.donotmiss.backend.mq.DomainEventOutboxRepository;
import com.donotmiss.backend.mq.DomainEventOutboxStatus;
import com.donotmiss.backend.mq.MqProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpsConsoleTest {
    @Mock
    private CurrentUser currentUser;
    @Mock
    private HttpServletRequest request;
    @Mock
    private OpsQueries queries;
    @Mock
    private DomainEventOutboxRepository outboxRepository;
    @Mock
    private ObjectProvider<AmqpAdmin> amqpAdmin;
    @Mock
    private ObjectProvider<RabbitListenerEndpointRegistry> listenerRegistry;

    // ---- Access ----

    @Test
    void consoleIsClosedWhenDisabledEvenForListedUser() {
        OpsAccessGuard guard = new OpsAccessGuard(currentUser, false, "demo-student");

        assertFalse(guard.allowed(request));
        ApiException error = assertThrows(ApiException.class, () -> guard.require(request));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
    }

    @Test
    void consoleOpensOnlyForListedUsers() {
        OpsAccessGuard guard = new OpsAccessGuard(currentUser, true, " demo-student , ops-admin ");
        when(currentUser.id(request)).thenReturn("demo-student", "someone-else");

        assertTrue(guard.allowed(request));
        assertFalse(guard.allowed(request));
    }

    @Test
    void anonymousCallerIsRefusedWithoutError() {
        OpsAccessGuard guard = new OpsAccessGuard(currentUser, true, "demo-student");
        when(currentUser.id(request)).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "请先登录"));

        assertFalse(guard.allowed(request));
    }

    @Test
    void emptyAdminListKeepsConsoleClosed() {
        OpsAccessGuard guard = new OpsAccessGuard(currentUser, true, "");

        assertFalse(guard.allowed(request));
    }

    // ---- Statistics ----

    @Test
    void percentileUsesNearestRank() {
        List<Long> values = List.of(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L);

        assertEquals(100L, OpsAgentService.percentile(values, 0.95));
        assertEquals(50L, OpsAgentService.percentile(values, 0.5));
        assertEquals(55L, OpsAgentService.average(values));
        assertNull(OpsAgentService.percentile(List.of(), 0.95));
    }

    // ---- Journeys ----

    @Test
    void recordIdIsReadFromRunInputPrefix() {
        assertEquals(Optional.of(23L), OpsJourneyService.recordIdOf("recordId=23, assessmentId=23, evidenceId=x"));
        assertEquals(Optional.of(7L), OpsJourneyService.recordIdOf("recordId=7"));
        assertEquals(Optional.empty(), OpsJourneyService.recordIdOf("resumeEvidenceId=4"));
        assertEquals(Optional.empty(), OpsJourneyService.recordIdOf(null));
    }

    @Test
    void verdictPrefersFailureOverProgressOverDone() {
        OpsDtos.JourneyStep record = step("SQL", "成果记录写入 achievement_records", "OK");
        OpsDtos.JourneyStep written = step("SQL", "证据写入 capability_v2_evidence_links", "OK");
        OpsDtos.JourneyStep deferred = step("MQ", "Outbox → capability-v2.score", "DEFERRED");
        OpsDtos.JourneyStep failed = step("AGENT", "证据评估 Agent", "FAILED");

        assertEquals("ERROR", OpsJourneyService.verdict(List.of(record, deferred, failed))[1]);
        assertEquals("WARN", OpsJourneyService.verdict(List.of(record, deferred))[1]);
        assertEquals("OK", OpsJourneyService.verdict(List.of(record, written))[1]);
        assertEquals("MUTED", OpsJourneyService.verdict(List.of(record))[1]);
    }

    @Test
    void resumeRunsAreMatchedBySessionAndEvidencePrefixes() {
        java.util.regex.Pattern session = OpsJourneyService.SESSION_ID;
        java.util.regex.Pattern evidence = OpsJourneyService.RESUME_EVIDENCE_ID;

        assertEquals(Optional.of(12L), OpsJourneyService.idOf(session, "sessionId=12, snapshotId=3"));
        assertEquals(Optional.of(12L), OpsJourneyService.idOf(session, "sessionId=12"));
        assertEquals(Optional.of(5L), OpsJourneyService.idOf(evidence, "resumeEvidenceId=5, assessmentId=9, evidenceId=x"));
        // A guide session id is a UUID and must not be mistaken for a resume session.
        assertEquals(Optional.empty(), OpsJourneyService.idOf(session, "sessionId=7cee4d1d-33a3"));
        assertEquals(Optional.empty(), OpsJourneyService.idOf(evidence, "recordId=5, assessmentId=9"));
    }

    @Test
    void waitingForUserIsReportedSeparatelyFromProcessing() {
        OpsDtos.JourneyStep session = step("SQL", "定段会话 resume_interview_sessions", "WAITING");
        OpsDtos.JourneyStep parsed = step("AGENT", "简历解析 Agent", "OK");
        OpsDtos.JourneyStep cancelled = step("SQL", "定段会话 resume_interview_sessions", "CANCELLED");

        String[] waiting = OpsJourneyService.verdict(List.of(session, parsed));
        assertEquals("等待用户回答", waiting[0]);
        assertEquals("WARN", waiting[1]);
        assertEquals("MUTED", OpsJourneyService.verdict(List.of(cancelled, parsed))[1]);
    }

    @Test
    void failedAttemptFollowedByRetryIsNotTheCurrentState() {
        Instant t0 = Instant.parse("2026-10-23T07:28:40Z");
        // evidence 1: failed, failed again, then succeeded
        com.donotmiss.backend.agentlog.AgentRunEntity a1 = run(144L, com.donotmiss.backend.agentlog.AgentRunStatus.FAILED, t0);
        com.donotmiss.backend.agentlog.AgentRunEntity a2 = run(149L, com.donotmiss.backend.agentlog.AgentRunStatus.FAILED, t0.plusSeconds(60));
        com.donotmiss.backend.agentlog.AgentRunEntity a3 = run(154L, com.donotmiss.backend.agentlog.AgentRunStatus.SUCCEEDED, t0.plusSeconds(180));
        // evidence 3: last attempt failed but a deferred message was queued afterwards
        com.donotmiss.backend.agentlog.AgentRunEntity b1 = run(153L, com.donotmiss.backend.agentlog.AgentRunStatus.FAILED, t0.plusSeconds(200));
        // evidence 5: last attempt failed and nothing was queued after it: a real failure
        com.donotmiss.backend.agentlog.AgentRunEntity c1 = run(160L, com.donotmiss.backend.agentlog.AgentRunStatus.FAILED, t0.plusSeconds(300));
        Map<com.donotmiss.backend.agentlog.AgentRunEntity, Long> evidenceOf = Map.of(a1, 1L, a2, 1L, a3, 1L, b1, 3L, c1, 5L);
        Map<Long, Instant> queued = Map.of(1L, t0.minusSeconds(5), 3L, t0.plusSeconds(230), 5L, t0.plusSeconds(290));

        java.util.Set<Long> superseded = OpsJourneyService.supersededRuns(
                List.of(a1, a2, a3, b1, c1), evidenceOf::get, queued::get);

        assertEquals(java.util.Set.of(144L, 149L, 153L), superseded);
    }

    private static com.donotmiss.backend.agentlog.AgentRunEntity run(Long id,
                                                                     com.donotmiss.backend.agentlog.AgentRunStatus status,
                                                                     Instant startedAt) {
        com.donotmiss.backend.agentlog.AgentRunEntity run = new com.donotmiss.backend.agentlog.AgentRunEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(run, "id", id);
        run.setStatus(status);
        run.setStartedAt(startedAt);
        return run;
    }

    // ---- Outbox ----

    @Test
    void outboxRowFlagsDeferredAndExhaustedMessages() {
        OpsMessagingService service = messagingService();
        Instant now = Instant.now();

        DomainEventOutboxEntity deferred = outbox(DomainEventOutboxStatus.PENDING, 1, now.plus(Duration.ofMinutes(4)));
        DomainEventOutboxEntity exhausted = outbox(DomainEventOutboxStatus.FAILED, 8, now.minusSeconds(5));
        DomainEventOutboxEntity sent = outbox(DomainEventOutboxStatus.SENT, 1, now.plus(Duration.ofMinutes(4)));

        assertTrue(service.toRow(deferred, now).deferred());
        assertFalse(service.toRow(deferred, now).exhausted());
        assertTrue(service.toRow(exhausted, now).exhausted());
        assertFalse(service.toRow(exhausted, now).deferred());
        assertFalse(service.toRow(sent, now).deferred());
        assertEquals("CapabilityV2ScoringMessage", service.toRow(sent, now).payloadType());
    }

    @Test
    void retryResetsAttemptsAndMakesMessageDueNow() {
        OpsMessagingService service = messagingService();
        DomainEventOutboxEntity event = outbox(DomainEventOutboxStatus.FAILED, 8, Instant.now().plus(Duration.ofHours(1)));
        when(outboxRepository.findById(5L)).thenReturn(Optional.of(event));
        when(outboxRepository.save(event)).thenReturn(event);

        OpsDtos.OutboxRow row = service.retry(5L, "demo-student");

        assertEquals("PENDING", row.status());
        assertEquals(0, row.attempts());
        assertFalse(event.getNextAttemptAt().isAfter(Instant.now()));
        assertFalse(row.exhausted());
    }

    @Test
    void sentMessageCannotBeRetried() {
        OpsMessagingService service = messagingService();
        DomainEventOutboxEntity event = outbox(DomainEventOutboxStatus.SENT, 1, Instant.now());
        when(outboxRepository.findById(5L)).thenReturn(Optional.of(event));

        ApiException error = assertThrows(ApiException.class, () -> service.retry(5L, "demo-student"));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void outboxStatsAggregatePerRoutingKey() {
        OpsMessagingService service = messagingService();
        when(queries.outboxCounts()).thenReturn(List.of(
                new Object[]{DomainEventOutboxStatus.SENT, "capability-v2.score", 10L},
                new Object[]{DomainEventOutboxStatus.PENDING, "capability-v2.score", 2L},
                new Object[]{DomainEventOutboxStatus.FAILED, "event.index", 1L}));
        when(queries.countDeferred(any(Integer.class), any(Instant.class))).thenReturn(1L);
        when(queries.countExhausted(8)).thenReturn(1L);

        OpsDtos.OutboxStats stats = service.outboxStats();

        assertEquals(2, stats.pending());
        assertEquals(1, stats.failed());
        assertEquals(1, stats.exhausted());
        Map<String, Object> capability = stats.byRoutingKey().stream()
                .filter(row -> "capability-v2.score".equals(row.get("routingKey"))).findFirst().orElseThrow();
        assertEquals(10L, capability.get("SENT"));
        assertEquals(0L, capability.get("FAILED"));
    }

    @Test
    void probeTurnsAnExceptionIntoDownCard() {
        OpsDtos.DependencyHealth card = OpsHealthService.probe("redis", "Redis", () -> {
            throw new IllegalStateException("outer", new java.net.ConnectException("Connection refused"));
        });

        assertEquals(OpsDtos.HealthState.DOWN, card.state());
        assertTrue(card.summary().contains("Connection refused"));
    }

    private OpsMessagingService messagingService() {
        return new OpsMessagingService(queries, outboxRepository, new MqProperties(), amqpAdmin, listenerRegistry, 8);
    }

    private static DomainEventOutboxEntity outbox(DomainEventOutboxStatus status, int attempts, Instant nextAttemptAt) {
        DomainEventOutboxEntity event = new DomainEventOutboxEntity();
        event.setRoutingKey("capability-v2.score");
        event.setPayloadType("com.donotmiss.backend.mq.DomainEventMessages$CapabilityV2ScoringMessage");
        event.setPayloadJson("{}");
        event.setStatus(status);
        event.setAttempts(attempts);
        event.setNextAttemptAt(nextAttemptAt);
        return event;
    }

    private static OpsDtos.JourneyStep step(String layer, String name, String status) {
        return new OpsDtos.JourneyStep(layer, name, status, Instant.now(), null, null);
    }
}
