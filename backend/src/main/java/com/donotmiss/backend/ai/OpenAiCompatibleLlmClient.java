package com.donotmiss.backend.ai;

import com.donotmiss.backend.ratelimit.ModelUsageMeter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private ModelUsageMeter usageMeter;

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

    /** D4: counts each request sent to the model. A setter, so tests can build the client without it. */
    @Autowired(required = false)
    void setUsageMeter(ModelUsageMeter usageMeter) {
        this.usageMeter = usageMeter;
    }

    private void count(String kind) {
        if (usageMeter != null) {
            usageMeter.record(kind);
        }
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
        count(ModelUsageMeter.CHAT);

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
        count(ModelUsageMeter.CHAT);

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

    /**
     * F0: one round of a chat with native tool calling. Unlike the other methods this one does not
     * fall back silently: the agent loop must tell the student honestly when the model is down, so
     * every failure becomes a {@link ToolChat.UnavailableException} with a plain-language message.
     * An empty {@code tools} list asks for a final text answer.
     */
    public ToolChat.Turn chatWithTools(List<ToolChat.Message> messages, List<ToolChat.Tool> tools) {
        if (!isEnabled()) {
            throw new ToolChat.UnavailableException("没有配置真实的 AI 模型（当前是本地模拟模式）");
        }
        count(ModelUsageMeter.CHAT);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("temperature", 0.2);
        requestBody.put("messages", messages.stream().map(this::toWire).toList());
        if (tools != null && !tools.isEmpty()) {
            requestBody.put("tools", tools.stream().map(tool -> Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", tool.name(),
                            "description", tool.description(),
                            "parameters", tool.parameters()))).toList());
        }
        if (provider.toLowerCase(Locale.ROOT).contains("qwen")) {
            requestBody.put("enable_thinking", qwenThinkingEnabled);
        }

        JsonNode response;
        try {
            response = restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            log.warn("Tool-calling chat request failed: {}", ex.getMessage());
            throw new ToolChat.UnavailableException("AI 模型暂时连不上（" + shortReason(ex) + "）", ex);
        }
        return parseTurn(response);
    }

    /** Package-private so the wire format can be tested without a network. */
    static ToolChat.Turn parseTurn(JsonNode response) {
        JsonNode choice = response == null ? null : response.path("choices").path(0);
        if (choice == null || choice.isMissingNode()) {
            throw new ToolChat.UnavailableException("AI 模型返回了空结果");
        }
        JsonNode message = choice.path("message");
        List<ToolChat.Call> calls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode function = call.path("function");
            String name = function.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            JsonNode arguments = function.path("arguments");
            String argumentsJson = arguments.isTextual() ? arguments.asText() : arguments.toString();
            String id = call.path("id").asText("");
            calls.add(new ToolChat.Call(id.isBlank() ? "call_" + calls.size() : id, name,
                    argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson));
        }
        String content = message.path("content").isTextual() ? message.path("content").asText().trim() : "";
        if (content.isEmpty() && calls.isEmpty()) {
            throw new ToolChat.UnavailableException("AI 模型这次没有给出任何内容");
        }
        return new ToolChat.Turn(content, List.copyOf(calls), choice.path("finish_reason").asText(""));
    }

    private Map<String, Object> toWire(ToolChat.Message message) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("role", message.role().name().toLowerCase(Locale.ROOT));
        wire.put("content", message.content() == null ? "" : message.content());
        if (message.role() == ToolChat.Role.ASSISTANT && !message.toolCalls().isEmpty()) {
            wire.put("tool_calls", message.toolCalls().stream().map(call -> Map.of(
                    "id", call.id(),
                    "type", "function",
                    "function", Map.of("name", call.name(), "arguments", call.argumentsJson()))).toList());
        }
        if (message.role() == ToolChat.Role.TOOL) {
            wire.put("tool_call_id", message.toolCallId());
        }
        return wire;
    }

    /** Shown to students, so it never carries the gateway address or response body. */
    private static String shortReason(RestClientException ex) {
        if (ex instanceof org.springframework.web.client.RestClientResponseException response) {
            return "模型服务返回错误 " + response.getStatusCode().value();
        }
        String text = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
        if (text.contains("timed out") || text.contains("timeout")) {
            return "请求超时";
        }
        if (text.contains("extracting response")) {
            // Seen on 2026-11-12: the gateway answered with a non-JSON error page.
            return "模型服务返回了无法识别的内容";
        }
        return "网络错误";
    }

    public Optional<List<Double>> embedding(String input) {
        if (!isEmbeddingEnabled() || input == null || input.isBlank()) {
            return Optional.empty();
        }
        count(ModelUsageMeter.EMBEDDING);

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
        count(ModelUsageMeter.EMBEDDING);
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
