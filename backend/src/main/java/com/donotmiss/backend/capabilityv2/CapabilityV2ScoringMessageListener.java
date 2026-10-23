package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.mq.DomainEventMessages;
import com.donotmiss.backend.mq.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Receives the post-evaluator message.
 *
 * <p>Two kinds of failure are handled differently. Ordinary exceptions (for example a
 * concurrent-write conflict) propagate to RabbitMQ's short in-process retry. A model outage
 * can last minutes, which those few-second retries cannot cover, so the message is instead
 * deferred through the outbox with exponential backoff (1, 2, 4 ... 60 minutes).</p>
 */
@Service
public class CapabilityV2ScoringMessageListener {
    static final int MAX_DEFERRALS = 8;
    private static final Duration FIRST_DEFERRAL = Duration.ofMinutes(1);
    private static final Duration MAX_DEFERRAL = Duration.ofMinutes(60);
    private static final Logger log = LoggerFactory.getLogger(CapabilityV2ScoringMessageListener.class);

    private final CapabilityV2AsyncScoringService asyncScoringService;
    private final DomainEventPublisher publisher;

    public CapabilityV2ScoringMessageListener(CapabilityV2AsyncScoringService asyncScoringService,
                                              DomainEventPublisher publisher) {
        this.asyncScoringService = asyncScoringService;
        this.publisher = publisher;
    }

    @RabbitListener(queues = "${app.mq.queues.capability-v2-scoring}")
    public void handle(DomainEventMessages.CapabilityV2ScoringMessage message) {
        if (!isValid(message)) {
            log.warn("Ignored invalid capability V2 scoring message: {}", message);
            return;
        }

        try {
            CapabilityV2AsyncScoringService.ProcessingOutcome outcome = message.fromResumeInterview()
                    ? asyncScoringService.processResumeEvidence(
                            message.resumeEvidenceId(),
                            message.userId(),
                            message.assessmentId(),
                            message.evidenceQuality(),
                            message.novelty())
                    : asyncScoringService.process(
                            message.recordId(),
                            message.userId(),
                            message.assessmentId(),
                            message.evidenceQuality(),
                            message.novelty());
            log.info(
                    "Processed capability V2 scoring message. source={}, sourceId={}, assessmentId={}, skipped={}, buckets={}, runId={}, summary={}",
                    message.evidenceSource(), sourceId(message), message.assessmentId(), outcome.skipped(),
                    outcome.scoredBuckets(), outcome.agentRunId(), outcome.summary()
            );
        } catch (CapabilityModelUnavailableException exception) {
            handleModelUnavailable(message, exception);
        }
    }

    private void handleModelUnavailable(DomainEventMessages.CapabilityV2ScoringMessage message,
                                        CapabilityModelUnavailableException exception) {
        if (!exception.retryable() || message.deferralCount() >= MAX_DEFERRALS) {
            // Nothing was written; the failed agent run keeps the evidence and the reason for a manual replay.
            log.error("Capability V2 scoring gave up. source={}, sourceId={}, deferrals={}, stage={}, reason={}",
                    message.evidenceSource(), sourceId(message), message.deferralCount(), exception.stage(),
                    exception.getMessage());
            return;
        }
        Duration delay = deferralDelay(message.deferralCount());
        publisher.deferCapabilityV2Scoring(message, delay);
        log.warn("Capability V2 scoring deferred because the model is unavailable. source={}, sourceId={}, deferral={}/{}, delay={}, stage={}",
                message.evidenceSource(), sourceId(message), message.deferralCount() + 1, MAX_DEFERRALS, delay,
                exception.stage());
    }

    private static boolean isValid(DomainEventMessages.CapabilityV2ScoringMessage message) {
        if (message == null || message.assessmentId() == null
                || message.userId() == null || message.userId().isBlank()) {
            return false;
        }
        return sourceId(message) != null;
    }

    private static Long sourceId(DomainEventMessages.CapabilityV2ScoringMessage message) {
        return message.fromResumeInterview() ? message.resumeEvidenceId() : message.recordId();
    }

    static Duration deferralDelay(int deferralCount) {
        Duration delay = FIRST_DEFERRAL.multipliedBy(1L << Math.min(Math.max(deferralCount, 0), 10));
        return delay.compareTo(MAX_DEFERRAL) > 0 ? MAX_DEFERRAL : delay;
    }
}
