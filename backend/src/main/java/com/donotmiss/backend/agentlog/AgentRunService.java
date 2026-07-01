package com.donotmiss.backend.agentlog;

import com.donotmiss.backend.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class AgentRunService {
    private final AgentRunRepository agentRunRepository;
    private final AgentRunStepRepository agentRunStepRepository;
    private final AgentTraceArtifactService artifactService;

    public AgentRunService(AgentRunRepository agentRunRepository,
                           AgentRunStepRepository agentRunStepRepository,
                           AgentTraceArtifactService artifactService) {
        this.agentRunRepository = agentRunRepository;
        this.agentRunStepRepository = agentRunStepRepository;
        this.artifactService = artifactService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long startRun(String userId, AgentRunType runType, String goal, String inputSummary) {
        AgentRunEntity entity = new AgentRunEntity();
        entity.setUserId(userId);
        entity.setRunType(runType);
        entity.setStatus(AgentRunStatus.RUNNING);
        entity.setGoal(compact(goal, 500));
        entity.setInputSummary(compact(inputSummary, 1000));
        entity.setStartedAt(Instant.now());
        return agentRunRepository.save(entity).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long startStep(Long runId, AgentStepName stepName, String inputSummary) {
        if (runId == null) {
            return null;
        }
        AgentRunStepEntity entity = new AgentRunStepEntity();
        entity.setRunId(runId);
        entity.setSequenceNo((int) agentRunStepRepository.countByRunId(runId) + 1);
        entity.setStepName(stepName);
        entity.setStatus(AgentStepStatus.RUNNING);
        entity.setInputSummary(compact(inputSummary, 1200));
        entity.setStartedAt(Instant.now());
        return agentRunStepRepository.save(entity).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeStep(Long stepId, String outputSummary) {
        if (stepId == null) {
            return;
        }
        AgentRunStepEntity entity = agentRunStepRepository.findById(stepId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Agent step not found."));
        entity.setStatus(AgentStepStatus.SUCCEEDED);
        entity.setOutputSummary(compact(outputSummary, 1200));
        entity.setFinishedAt(Instant.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failStep(Long stepId, Throwable error) {
        if (stepId == null) {
            return;
        }
        AgentRunStepEntity entity = agentRunStepRepository.findById(stepId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Agent step not found."));
        entity.setStatus(AgentStepStatus.FAILED);
        entity.setErrorMessage(compact(errorMessage(error), 1000));
        entity.setFinishedAt(Instant.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishRun(Long runId, String outputSummary) {
        if (runId == null) {
            return;
        }
        AgentRunEntity entity = agentRunRepository.findById(runId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Agent run not found."));
        entity.setStatus(AgentRunStatus.SUCCEEDED);
        entity.setOutputSummary(compact(outputSummary, 1000));
        entity.setFinishedAt(Instant.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failRun(Long runId, Throwable error) {
        if (runId == null) {
            return;
        }
        AgentRunEntity entity = agentRunRepository.findById(runId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Agent run not found."));
        entity.setStatus(AgentRunStatus.FAILED);
        entity.setErrorMessage(compact(errorMessage(error), 1000));
        entity.setFinishedAt(Instant.now());
    }

    @Transactional(readOnly = true)
    public List<AgentLogDtos.RunSummary> recent(String userId) {
        return agentRunRepository.findTop30ByUserIdOrderByStartedAtDesc(userId).stream()
                .map(AgentLogDtos.RunSummary::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AgentLogDtos.RunDetail detail(String userId, Long runId) {
        AgentRunEntity run = agentRunRepository.findByIdAndUserId(runId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Agent run not found."));
        List<AgentLogDtos.StepResponse> steps = agentRunStepRepository.findByRunIdOrderBySequenceNoAsc(runId).stream()
                .map(AgentLogDtos.StepResponse::from)
                .toList();
        return new AgentLogDtos.RunDetail(AgentLogDtos.RunSummary.from(run), steps, artifactService.list(runId));
    }

    private String errorMessage(Throwable error) {
        if (error == null) {
            return "Unknown error";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private String compact(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }
}
