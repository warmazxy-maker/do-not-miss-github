package com.donotmiss.backend.guide;

import com.donotmiss.backend.ai.AiDtos;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@Service
public class GuideSessionService {
    private static final int MAX_RECENT_MESSAGES = 24;
    private static final long SESSION_TTL_SECONDS = 60 * 60;

    private final GuideSessionRepository repository;
    private final ObjectMapper objectMapper;

    public GuideSessionService(GuideSessionRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public GuideSessionEntity loadOrCreate(String userId, String sessionId) {
        String normalizedUserId = normalizeUserId(userId);
        if (sessionId != null && !sessionId.isBlank()) {
            return repository.findBySessionIdAndUserId(sessionId.trim(), normalizedUserId)
                    .map(this::touch)
                    .orElseGet(() -> create(normalizedUserId));
        }
        return create(normalizedUserId);
    }

    @Transactional
    public GuideSessionEntity appendMessage(GuideSessionEntity session, String role, String content) {
        List<GuideDtos.GuideMessage> messages = new ArrayList<>(readMessages(session));
        messages.add(new GuideDtos.GuideMessage(
                normalizeRole(role),
                content == null ? "" : content.trim(),
                Instant.now()
        ));
        int fromIndex = Math.max(0, messages.size() - MAX_RECENT_MESSAGES);
        session.setRecentMessagesJson(toJson(messages.subList(fromIndex, messages.size())));
        return touch(session);
    }

    @Transactional
    public GuideSessionEntity saveRagSnapshot(GuideSessionEntity session,
                                              GuideAction action,
                                              String currentGoal,
                                              String ragNeed,
                                              List<AiDtos.RecommendedEvent> recommendations) {
        session.setLastAction(action == null ? null : action.name());
        if (hasText(currentGoal)) {
            session.setCurrentGoal(currentGoal.trim());
        }
        if (hasText(ragNeed)) {
            session.setLastRagNeed(ragNeed.trim());
        }
        session.setLastRecommendedEventIdsJson(toJson(recommendations == null
                ? List.of()
                : recommendations.stream()
                .filter(item -> item != null && item.event() != null)
                .map(item -> item.event().id())
                .filter(id -> id != null)
                .distinct()
                .limit(15)
                .toList()));
        return touch(session);
    }

    @Transactional
    public GuideSessionEntity savePreferences(GuideSessionEntity session,
                                              List<String> acceptedPreferences,
                                              List<String> rejectedConditions) {
        if (acceptedPreferences != null) {
            session.setAcceptedPreferencesJson(toJson(cleanStrings(acceptedPreferences, 12)));
        }
        if (rejectedConditions != null) {
            session.setRejectedConditionsJson(toJson(cleanStrings(rejectedConditions, 12)));
        }
        return touch(session);
    }

    @Transactional
    public GuideSessionEntity saveDecision(GuideSessionEntity session, GuideDtos.GuideDecision decision) {
        if (decision == null) {
            return touch(session);
        }
        session.setLastAction(decision.action() == null ? null : decision.action().name());
        if (hasText(decision.currentGoal())) {
            session.setCurrentGoal(decision.currentGoal().trim());
        }
        if (hasText(decision.ragNeed())) {
            session.setLastRagNeed(decision.ragNeed().trim());
        }
        if (decision.acceptedPreferences() != null) {
            session.setAcceptedPreferencesJson(toJson(cleanStrings(decision.acceptedPreferences(), 12)));
        }
        if (decision.rejectedConditions() != null) {
            session.setRejectedConditionsJson(toJson(cleanStrings(decision.rejectedConditions(), 12)));
        }
        return touch(session);
    }

    @Transactional
    public GuideSessionEntity compressContext(GuideSessionEntity session,
                                              String sessionSummary,
                                              List<GuideDtos.GuideMessage> messagesToKeep) {
        if (hasText(sessionSummary)) {
            session.setSessionSummary(compact(sessionSummary.trim(), 3000));
        }
        session.setRecentMessagesJson(toJson(messagesToKeep == null ? List.of() : messagesToKeep));
        return touch(session);
    }

    @Transactional(readOnly = true)
    public List<GuideDtos.GuideMessage> recentMessages(GuideSessionEntity session) {
        return readMessages(session);
    }

    @Transactional(readOnly = true)
    public GuideDtos.GuideSessionView view(GuideSessionEntity session) {
        return new GuideDtos.GuideSessionView(
                session.getSessionId(),
                session.getCurrentGoal(),
                session.getSessionSummary(),
                session.getLastAction(),
                session.getLastRagNeed(),
                readMessages(session),
                readLongList(session.getLastRecommendedEventIdsJson()),
                readStringList(session.getAcceptedPreferencesJson()),
                readStringList(session.getRejectedConditionsJson()),
                session.getUpdatedAt(),
                session.getExpiresAt()
        );
    }

    private GuideSessionEntity create(String userId) {
        GuideSessionEntity session = new GuideSessionEntity();
        session.setSessionId(UUID.randomUUID().toString());
        session.setUserId(userId);
        return repository.save(session);
    }

    private GuideSessionEntity touch(GuideSessionEntity session) {
        session.setExpiresAt(Instant.now().plusSeconds(SESSION_TTL_SECONDS));
        return repository.save(session);
    }

    private List<GuideDtos.GuideMessage> readMessages(GuideSessionEntity session) {
        return readValue(session.getRecentMessagesJson(), new TypeReference<>() {
        }, List.of());
    }

    private List<Long> readLongList(String json) {
        return readValue(json, new TypeReference<>() {
        }, List.of());
    }

    private List<String> readStringList(String json) {
        return readValue(json, new TypeReference<>() {
        }, List.of());
    }

    private <T> T readValue(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "anonymous" : userId.trim();
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "user";
        }
        String normalized = role.trim().toLowerCase();
        return switch (normalized) {
            case "assistant", "system", "tool" -> normalized;
            default -> "user";
        };
    }

    private List<String> cleanStrings(List<String> values, int maxSize) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (hasText(value)) {
                result.add(value.trim());
            }
            if (result.size() >= maxSize) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String compact(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }
}
