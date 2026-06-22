package com.donotmiss.backend.agentlog;

import java.time.Instant;
import java.util.List;

public class AgentLogDtos {
    public record RunSummary(
            Long id,
            AgentRunType runType,
            AgentRunStatus status,
            String goal,
            String inputSummary,
            String outputSummary,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt,
            long durationMillis
    ) {
        static RunSummary from(AgentRunEntity entity) {
            return new RunSummary(
                    entity.getId(),
                    entity.getRunType(),
                    entity.getStatus(),
                    entity.getGoal(),
                    entity.getInputSummary(),
                    entity.getOutputSummary(),
                    entity.getErrorMessage(),
                    entity.getStartedAt(),
                    entity.getFinishedAt(),
                    entity.durationMillis()
            );
        }
    }

    public record StepResponse(
            Long id,
            int sequenceNo,
            AgentStepName stepName,
            AgentStepStatus status,
            String inputSummary,
            String outputSummary,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt,
            long durationMillis
    ) {
        static StepResponse from(AgentRunStepEntity entity) {
            return new StepResponse(
                    entity.getId(),
                    entity.getSequenceNo(),
                    entity.getStepName(),
                    entity.getStatus(),
                    entity.getInputSummary(),
                    entity.getOutputSummary(),
                    entity.getErrorMessage(),
                    entity.getStartedAt(),
                    entity.getFinishedAt(),
                    entity.durationMillis()
            );
        }
    }

    public record RunDetail(
            RunSummary run,
            List<StepResponse> steps
    ) {
    }
}
