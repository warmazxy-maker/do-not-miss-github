package com.donotmiss.backend.agentlog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class AgentTraceArtifactService {
    private static final Logger log = LoggerFactory.getLogger(AgentTraceArtifactService.class);

    private final AgentTraceArtifactRepository repository;
    private final ObjectMapper objectMapper;

    public AgentTraceArtifactService(AgentTraceArtifactRepository repository,
                                     ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long runId, AgentStepName stepName, String artifactType, Object content, String summary) {
        if (runId == null || artifactType == null || artifactType.isBlank()) {
            return;
        }
        try {
            String json = toJson(content);
            AgentTraceArtifactEntity entity = new AgentTraceArtifactEntity();
            entity.setRunId(runId);
            entity.setStepName(stepName);
            entity.setArtifactType(compact(artifactType, 80));
            entity.setContentSummary(compact(summary, 1000));
            entity.setContentJson(json);
            entity.setContentHash(hash(json));
            entity.setRedacted(true);
            repository.save(entity);
        } catch (RuntimeException ex) {
            log.warn("Failed to persist agent trace artifact. runId={}, type={}, error={}",
                    runId, artifactType, ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<AgentLogDtos.ArtifactResponse> list(Long runId) {
        return repository.findByRunIdOrderByIdAsc(runId).stream()
                .map(AgentLogDtos.ArtifactResponse::from)
                .toList();
    }

    private String toJson(Object content) {
        Object safeContent = content == null
                ? Map.of("empty", true, "capturedAt", Instant.now().toString())
                : content;
        try {
            return objectMapper.writeValueAsString(safeContent);
        } catch (JsonProcessingException ex) {
            return "{\"serializationError\":\"" + escape(ex.getMessage()) + "\"}";
        }
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String compact(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private String escape(String value) {
        return compact(value, 300)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
