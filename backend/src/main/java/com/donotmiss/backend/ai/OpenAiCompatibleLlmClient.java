package com.donotmiss.backend.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class OpenAiCompatibleLlmClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String provider;
    private final String model;
    private final String embeddingModel;
    private final String apiKey;

    public OpenAiCompatibleLlmClient(ObjectMapper objectMapper,
                                     @Value("${app.ai.provider:mock}") String provider,
                                     @Value("${app.ai.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
                                     @Value("${app.ai.api-key:}") String apiKey,
                                     @Value("${app.ai.model:qwen-plus}") String model,
                                     @Value("${app.ai.embedding-model:text-embedding-v4}") String embeddingModel,
                                     @Value("${app.ai.timeout-seconds:30}") long timeoutSeconds) {
        this.objectMapper = objectMapper;
        this.provider = provider == null ? "mock" : provider.trim();
        this.model = model == null || model.isBlank() ? "qwen-plus" : model.trim();
        this.embeddingModel = embeddingModel == null || embeddingModel.isBlank() ? "text-embedding-v4" : embeddingModel.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(timeoutSeconds, 1));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);

        this.restClient = RestClient.builder()
                .baseUrl(trimTrailingSlash(baseUrl))
                .requestFactory(requestFactory)
                .build();
    }

    public boolean isEnabled() {
        return !"mock".equalsIgnoreCase(provider) && !apiKey.isBlank();
    }

    public String modeLabel() {
        return isEnabled() ? provider + ":" + model : "mock";
    }

    public String embeddingModeLabel() {
        return isEnabled() ? provider + ":" + embeddingModel : "mock";
    }

    public Optional<String> chatPlain(String userPrompt) {
        if (!isEnabled()) {
            return Optional.empty();
        }

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "temperature", 0.2,
                "messages", List.of(
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        try {
            JsonNode response = restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);

            String content = response == null
                    ? ""
                    : response.path("choices").path(0).path("message").path("content").asText("");

            if (content.isBlank()) {
                log.warn("LLM plain response has no assistant content.");
                return Optional.empty();
            }

            return Optional.of(content.trim());
        } catch (RestClientException ex) {
            log.warn("LLM plain request failed, falling back to local mock rules: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public <T> Optional<T> chatForJson(String systemPrompt, String userPrompt, Class<T> responseType) {
        if (!isEnabled()) {
            return Optional.empty();
        }

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "temperature", 0.2,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        try {
            JsonNode response = restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);

            String content = response == null
                    ? ""
                    : response.path("choices").path(0).path("message").path("content").asText("");

            if (content.isBlank()) {
                log.warn("LLM response has no assistant content.");
                return Optional.empty();
            }

            return Optional.of(objectMapper.readValue(extractJsonObject(content), responseType));
        } catch (RestClientException | JsonProcessingException ex) {
            log.warn("LLM request failed, falling back to local mock rules: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public Optional<List<Double>> embedding(String input) {
        if (!isEnabled() || input == null || input.isBlank()) {
            return Optional.empty();
        }

        Map<String, Object> requestBody = Map.of(
                "model", embeddingModel,
                "input", input
        );

        try {
            JsonNode response = restClient.post()
                    .uri("/embeddings")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode embedding = response == null
                    ? null
                    : response.path("data").path(0).path("embedding");

            if (embedding == null || !embedding.isArray() || embedding.isEmpty()) {
                log.warn("Embedding response has no vector content.");
                return Optional.empty();
            }

            List<Double> vector = new ArrayList<>();
            for (JsonNode item : embedding) {
                if (!item.isNumber()) {
                    return Optional.empty();
                }
                vector.add(item.asDouble());
            }
            return Optional.of(vector);
        } catch (RestClientException ex) {
            log.warn("Embedding request failed, vector retrieval will be skipped: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private String extractJsonObject(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return content;
    }

    private String trimTrailingSlash(String value) {
        String normalized = value == null || value.isBlank()
                ? "https://dashscope.aliyuncs.com/compatible-mode/v1"
                : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
