package com.donotmiss.backend.retrieval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class SearchSessionContextService {
    private static final Logger log = LoggerFactory.getLogger(SearchSessionContextService.class);
    private static final String KEY_PREFIX = "search:session:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public SearchSessionContextService(StringRedisTemplate redisTemplate,
                                       ObjectMapper objectMapper,
                                       @Value("${app.search-session.ttl-minutes:60}") long ttlMinutes) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofMinutes(Math.max(ttlMinutes, 1));
    }

    public Optional<RetrievalDtos.SearchSessionContext> load(String userId) {
        try {
            String json = redisTemplate.opsForValue().get(key(userId));
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            RetrievalDtos.SearchSessionContext context = objectMapper.readValue(json, RetrievalDtos.SearchSessionContext.class);
            if (context.updatedAt() != null && context.updatedAt().plus(ttl).isBefore(Instant.now())) {
                clear(userId);
                return Optional.empty();
            }
            return Optional.of(context);
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("Failed to load search session context: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public RetrievalDtos.SearchSessionContext save(String userId,
                                                   RetrievalDtos.QueryRewrite query,
                                                   RetrievalDtos.SearchSessionContext previous) {
        RetrievalDtos.SearchSessionContext next = toContext(query, previous);
        try {
            redisTemplate.opsForValue().set(key(userId), objectMapper.writeValueAsString(next), ttl);
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("Failed to save search session context: {}", ex.getMessage());
        }
        return next;
    }

    public void clear(String userId) {
        try {
            redisTemplate.delete(key(userId));
        } catch (RuntimeException ex) {
            log.warn("Failed to clear search session context: {}", ex.getMessage());
        }
    }

    private RetrievalDtos.SearchSessionContext toContext(RetrievalDtos.QueryRewrite query,
                                                         RetrievalDtos.SearchSessionContext previous) {
        List<String> messages = new ArrayList<>();
        if (previous != null && previous.recentUserMessages() != null) {
            messages.addAll(previous.recentUserMessages());
        }
        if (query.originalQuery() != null && !query.originalQuery().isBlank()) {
            messages.add(query.originalQuery());
        }
        int fromIndex = Math.max(messages.size() - 6, 0);
        List<String> recentMessages = messages.subList(fromIndex, messages.size());

        return new RetrievalDtos.SearchSessionContext(
                query.originalQuery(),
                query.rewrittenQuery(),
                query.goal(),
                query.level(),
                query.intentTags(),
                query.skills(),
                query.preferredCategories(),
                query.preferredLocation(),
                query.benefitPreference(),
                query.constraints(),
                List.copyOf(recentMessages),
                Instant.now()
        );
    }

    private String key(String userId) {
        return KEY_PREFIX + userId;
    }
}
