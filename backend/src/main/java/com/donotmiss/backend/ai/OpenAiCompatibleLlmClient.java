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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class OpenAiCompatibleLlmClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final RestClient embeddingRestClient;
    private final String provider;
    private final String model;
    private final String embeddingModel;
    private final String apiKey;
    private final String embeddingApiKey;
    private final boolean qwenThinkingEnabled;

    public OpenAiCompatibleLlmClient(ObjectMapper objectMapper,
                                     @Value("${app.ai.provider:mock}") String provider,
                                     @Value("${app.ai.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
                                     @Value("${app.ai.api-key:}") String apiKey,
                                     @Value("${app.ai.model:qwen-plus}") String model,
                                     @Value("${app.ai.embedding-model:text-embedding-v4}") String embeddingModel,
                                     @Value("${app.ai.qwen-thinking-enabled:false}") boolean qwenThinkingEnabled,
                                     @Value("${app.ai.timeout-seconds:30}") long timeoutSeconds,
                                     @Value("${app.ai.embedding-base-url:}") String embeddingBaseUrl,
                                     @Value("${app.ai.embedding-api-key:}") String embeddingApiKey) {
        this.objectMapper = objectMapper;
        this.provider = provider == null ? "mock" : provider.trim();
        this.model = model == null || model.isBlank() ? "qwen-plus" : model.trim();
        this.embeddingModel = embeddingModel == null || embeddingModel.isBlank() ? "text-embedding-v4" : embeddingModel.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        // Embedding may use a different gateway/key than chat (e.g. chat on a DeepSeek
        // relay group that has no embedding channel). Blank values fall back to chat settings.
        this.embeddingApiKey = embeddingApiKey == null || embeddingApiKey.isBlank()
                ? this.apiKey
                : embeddingApiKey.trim();
        this.qwenThinkingEnabled = qwenThinkingEnabled;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(timeoutSeconds, 1));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);

        this.restClient = RestClient.builder()
                .baseUrl(trimTrailingSlash(baseUrl))
                .requestFactory(requestFactory)
                .build();

        this.embeddingRestClient = embeddingBaseUrl == null || embeddingBaseUrl.isBlank()
                ? this.restClient
                : RestClient.builder()
                        .baseUrl(trimTrailingSlash(embeddingBaseUrl))
                        .requestFactory(requestFactory)
                        .build();
    }

    public boolean isEnabled() {
        return !"mock".equalsIgnoreCase(provider) && !apiKey.isBlank();
    }

    public boolean isEmbeddingEnabled() {
        return !"mock".equalsIgnoreCase(provider) && !embeddingApiKey.isBlank();
    }

    public String modeLabel() {
        return isEnabled() ? provider + ":" + model : "mock";
    }

    public String embeddingModeLabel() {
        return isEmbeddingEnabled() ? provider + ":" + embeddingModel : "mock";
    }

    public Optional<String> chatPlain(String userPrompt) {
        if (!isEnabled()) {
            return Optional.empty();
        }

        Map<String, Object> requestBody = chatRequestBody(List.of(
                Map.of("role", "user", "content", userPrompt)
        ));

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

        Map<String, Object> requestBody = chatRequestBody(List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        requestBody.put("response_format", Map.of("type", "json_object"));

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
        if (!isEmbeddingEnabled() || input == null || input.isBlank()) {
            return Optional.empty();
        }

        Map<String, Object> requestBody = Map.of(
                "model", embeddingModel,
                "input", input
        );

        try {
            JsonNode response = embeddingRestClient.post()
                    .uri("/embeddings")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + embeddingApiKey)
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

    /**
     * Embeds several texts in one request (OpenAI-compatible {@code input} array). Results are
     * returned in input order; empty when embeddings are disabled or the request fails.
     */
    public Optional<List<List<Double>>> embeddings(List<String> inputs) {
        if (!isEmbeddingEnabled() || inputs == null || inputs.isEmpty()
                || inputs.stream().anyMatch(input -> input == null || input.isBlank())) {
            return Optional.empty();
        }
        try {
            JsonNode response = embeddingRestClient.post()
                    .uri("/embeddings")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + embeddingApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", embeddingModel, "input", inputs))
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode data = response == null ? null : response.path("data");
            if (data == null || !data.isArray() || data.size() != inputs.size()) {
                log.warn("Batch embedding response size does not match the request.");
                return Optional.empty();
            }
            List<List<Double>> vectors = new ArrayList<>(java.util.Collections.nCopies(inputs.size(), null));
            for (JsonNode item : data) {
                int index = item.path("index").asInt(-1);
                JsonNode embedding = item.path("embedding");
                if (index < 0 || index >= inputs.size() || !embedding.isArray() || embedding.isEmpty()) {
                    return Optional.empty();
                }
                List<Double> vector = new ArrayList<>(embedding.size());
                for (JsonNode value : embedding) {
                    if (!value.isNumber()) {
                        return Optional.empty();
                    }
                    vector.add(value.asDouble());
                }
                vectors.set(index, vector);
            }
            return vectors.contains(null) ? Optional.empty() : Optional.of(vectors);
        } catch (RestClientException ex) {
            log.warn("Batch embedding request failed: {}", ex.getMessage());
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

    private Map<String, Object> chatRequestBody(List<Map<String, String>> messages) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("temperature", 0.2);
        requestBody.put("messages", messages);
        // Qwen 3.8 defaults to very deep reasoning. Structured agent calls need a
        // concise final JSON answer, so keep thinking opt-in through configuration.
        if (provider.toLowerCase(Locale.ROOT).contains("qwen")) {
            requestBody.put("enable_thinking", qwenThinkingEnabled);
        }
        return requestBody;
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
