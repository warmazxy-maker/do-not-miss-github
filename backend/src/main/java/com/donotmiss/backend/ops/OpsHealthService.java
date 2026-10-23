package com.donotmiss.backend.ops;

import com.donotmiss.backend.agentlog.AgentRunStatus;
import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.donotmiss.backend.capabilityv2.FunctionBucketSemanticIndex;
import com.donotmiss.backend.ops.OpsDtos.DependencyHealth;
import com.donotmiss.backend.ops.OpsDtos.HealthState;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * Probes of every external dependency the backend talks to. Each probe is independent and turns
 * its own failure into a DOWN card, so one dead dependency never breaks the whole overview.
 */
@Service
public class OpsHealthService {
    /** Known key families. Only counts are shown: auth:session keys are login tokens and never leave Redis. */
    static final List<String[]> REDIS_PREFIXES = List.of(
            new String[]{"auth:session:", "登录会话（只计数，不展示键名）"},
            new String[]{"reservation:qr:", "预约签到二维码"},
            new String[]{"search:session:", "AI 搜索多轮上下文"}
    );
    private static final int REDIS_SCAN_CAP = 10_000;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(3);

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;
    private final ConnectionFactory rabbitConnectionFactory;
    private final OpenAiCompatibleLlmClient llmClient;
    private final FunctionBucketSemanticIndex semanticIndex;
    private final OpsQueries queries;
    private final OpsMessagingService messagingService;
    private final boolean searchEnabled;
    private final String searchBaseUrl;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();

    public OpsHealthService(JdbcTemplate jdbcTemplate,
                            DataSource dataSource,
                            StringRedisTemplate redisTemplate,
                            ConnectionFactory rabbitConnectionFactory,
                            OpenAiCompatibleLlmClient llmClient,
                            FunctionBucketSemanticIndex semanticIndex,
                            OpsQueries queries,
                            OpsMessagingService messagingService,
                            @Value("${app.search.enabled:false}") boolean searchEnabled,
                            @Value("${app.search.base-url:http://localhost:9200}") String searchBaseUrl) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
        this.rabbitConnectionFactory = rabbitConnectionFactory;
        this.llmClient = llmClient;
        this.semanticIndex = semanticIndex;
        this.queries = queries;
        this.messagingService = messagingService;
        this.searchEnabled = searchEnabled;
        this.searchBaseUrl = searchBaseUrl;
    }

    public OpsDtos.Overview overview() {
        List<DependencyHealth> dependencies = List.of(
                probe("mysql", "MySQL", this::mysql),
                probe("redis", "Redis", this::redis),
                probe("rabbitmq", "RabbitMQ", this::rabbit),
                probe("opensearch", "OpenSearch", this::openSearch),
                probe("llm", "大模型（对话）", this::chatModel),
                probe("embedding", "向量模型 / 语义召回", this::embedding)
        );
        Map<String, Long> counters = new LinkedHashMap<>();
        safeCount(counters, "users", queries::countUsers);
        Instant since = Instant.now().minus(Duration.ofHours(24));
        safeCount(counters, "agentRuns24h", () -> (long) queries.runsSince(since).size());
        safeCount(counters, "agentRunsFailed24h", () -> queries.runsSince(since).stream()
                .filter(run -> run.getStatus() == AgentRunStatus.FAILED).count());
        safeCount(counters, "agentRunsStuck", () -> queries.countRunsStartedBefore(
                AgentRunStatus.RUNNING, Instant.now().minus(Duration.ofMinutes(10))));
        safeCount(counters, "assessmentJobsFailed", queries::countFailedJobs);
        try {
            OpsDtos.OutboxStats outbox = messagingService.outboxStats();
            counters.put("outboxPending", outbox.pending());
            counters.put("outboxDeferred", outbox.deferred());
            counters.put("outboxExhausted", outbox.exhausted());
        } catch (RuntimeException ignored) {
            // The MySQL card already reports why.
        }
        return new OpsDtos.Overview(Instant.now(), dependencies, counters);
    }

    // ---- Dependency probes ----

    private DependencyHealth mysql() {
        long started = System.nanoTime();
        jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        long latency = millisSince(started);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("version", jdbcTemplate.queryForObject("SELECT VERSION()", String.class));
        details.put("flyway", latestMigration().orElse("-"));
        details.putAll(pool());
        return new DependencyHealth("mysql", "MySQL", HealthState.UP, latency,
                "迁移版本 V" + details.get("flyway") + "，连接池 " + details.getOrDefault("active", "?")
                        + "/" + details.getOrDefault("max", "?") + " 使用中",
                details);
    }

    private DependencyHealth redis() {
        long started = System.nanoTime();
        String pong = redisTemplate.execute((RedisCallback<String>) RedisConnection::ping);
        long latency = millisSince(started);
        Long dbSize = redisTemplate.execute((RedisCallback<Long>) connection -> connection.serverCommands().dbSize());
        Properties memory = redisTemplate.execute((RedisCallback<Properties>) connection -> connection.serverCommands().info("memory"));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("ping", pong);
        details.put("keys", dbSize);
        details.put("usedMemory", memory == null ? null : memory.getProperty("used_memory_human"));
        return new DependencyHealth("redis", "Redis", HealthState.UP, latency,
                dbSize + " 个键，内存 " + details.get("usedMemory"), details);
    }

    private DependencyHealth rabbit() {
        long started = System.nanoTime();
        try (Connection connection = rabbitConnectionFactory.createConnection()) {
            if (!connection.isOpen()) {
                throw new IllegalStateException("connection is closed");
            }
        }
        long latency = millisSince(started);
        List<OpsDtos.QueueRow> queues = messagingService.queues();
        long backlog = queues.stream().mapToLong(row -> row.messageCount() == null ? 0 : row.messageCount()).sum();
        // A queue nobody publishes to or listens on is idle, not broken. It is a problem only when
        // messages wait without a consumer, or this JVM's listener for it has stopped.
        long stalled = queues.stream().filter(row -> row.consumerCount() != null && row.consumerCount() == 0
                && row.messageCount() != null && row.messageCount() > 0).count();
        long stoppedListeners = queues.stream().filter(row -> Boolean.FALSE.equals(row.listenerRunning())).count();
        long idle = queues.stream().filter(row -> row.listenerRunning() == null
                && (row.messageCount() == null || row.messageCount() == 0)).count();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("queues", queues.size());
        details.put("backlog", backlog);
        details.put("stalledQueues", stalled);
        details.put("stoppedListeners", stoppedListeners);
        details.put("idleQueues", idle);
        HealthState state = stalled > 0 || stoppedListeners > 0 ? HealthState.DEGRADED : HealthState.UP;
        String summary = queues.size() + " 个队列，积压 " + backlog + " 条"
                + (stalled > 0 ? "，" + stalled + " 个队列有积压但无消费者" : "")
                + (stoppedListeners > 0 ? "，" + stoppedListeners + " 个监听器已停止" : "")
                + (idle > 0 ? "，" + idle + " 个闲置队列" : "");
        return new DependencyHealth("rabbitmq", "RabbitMQ", state, latency, summary, details);
    }

    private DependencyHealth openSearch() throws Exception {
        if (!searchEnabled) {
            return new DependencyHealth("opensearch", "OpenSearch", HealthState.DISABLED, null,
                    "未启用（SEARCH_ENABLED=false），搜索走 MySQL 兜底", Map.of());
        }
        long started = System.nanoTime();
        HttpRequest request = HttpRequest.newBuilder(URI.create(stripSlash(searchBaseUrl) + "/_cluster/health"))
                .timeout(HTTP_TIMEOUT).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        long latency = millisSince(started);
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        String body = response.body();
        String color = body.contains("\"green\"") ? "green" : body.contains("\"yellow\"") ? "yellow" : "red";
        boolean singleNode = body.contains("\"number_of_data_nodes\":1");
        // Yellow = all primaries assigned, some replicas not. On a single node replicas never can be.
        HealthState state = "red".equals(color) ? HealthState.DOWN
                : "yellow".equals(color) && !singleNode ? HealthState.DEGRADED : HealthState.UP;
        String summary = "集群状态 " + color + ("yellow".equals(color) && singleNode ? "（单节点副本无法分配，开发环境正常）" : "");
        return new DependencyHealth("opensearch", "OpenSearch", state, latency, summary,
                Map.of("cluster", color, "singleNode", singleNode));
    }

    private DependencyHealth chatModel() {
        if (!llmClient.isEnabled()) {
            return new DependencyHealth("llm", "大模型（对话）", HealthState.DISABLED, null,
                    "mock 模式：未配置真实模型，Agent 走规则兜底", Map.of("mode", llmClient.modeLabel()));
        }
        return new DependencyHealth("llm", "大模型（对话）", HealthState.UP, null,
                "已配置 " + llmClient.modeLabel() + "（未实际调用，可在下方手动探活）",
                Map.of("mode", llmClient.modeLabel()));
    }

    private DependencyHealth embedding() {
        Map<String, Object> status = semanticIndex.status();
        if (!llmClient.isEmbeddingEnabled()) {
            return new DependencyHealth("embedding", "向量模型 / 语义召回", HealthState.DISABLED, null,
                    "未配置向量模型，职能召回退回关键词", status);
        }
        boolean built = Boolean.TRUE.equals(status.get("built"));
        boolean failedRecently = status.get("lastFailureAt") != null;
        HealthState state = failedRecently ? HealthState.DEGRADED : HealthState.UP;
        String summary = built
                ? "语义索引已建好（" + status.get("vectors") + " 个职能向量）"
                : failedRecently ? "语义索引构建失败，冷却中，期间退回关键词召回" : "语义索引尚未构建（首次评分时懒加载）";
        return new DependencyHealth("embedding", "向量模型 / 语义召回", state, null, summary, status);
    }

    /** Spends one tiny request on each model, only when an operator presses the button. */
    public List<DependencyHealth> probeModels() {
        List<DependencyHealth> results = new ArrayList<>();
        results.add(probe("llm", "大模型（对话）", () -> {
            if (!llmClient.isEnabled()) {
                return chatModel();
            }
            long started = System.nanoTime();
            Optional<String> reply = llmClient.chatPlain("只回复 OK");
            long latency = millisSince(started);
            return reply.isPresent()
                    ? new DependencyHealth("llm", "大模型（对话）", HealthState.UP, latency,
                    "实测可用：" + abbreviate(reply.get(), 40), Map.of("mode", llmClient.modeLabel()))
                    : new DependencyHealth("llm", "大模型（对话）", HealthState.DOWN, latency,
                    "调用失败或超时（详见后端日志）", Map.of("mode", llmClient.modeLabel()));
        }));
        results.add(probe("embedding", "向量模型 / 语义召回", () -> {
            if (!llmClient.isEmbeddingEnabled()) {
                return embedding();
            }
            long started = System.nanoTime();
            Optional<List<Double>> vector = llmClient.embedding("连通性测试");
            long latency = millisSince(started);
            return vector.isPresent()
                    ? new DependencyHealth("embedding", "向量模型 / 语义召回", HealthState.UP, latency,
                    "实测可用：返回 " + vector.get().size() + " 维向量", semanticIndex.status())
                    : new DependencyHealth("embedding", "向量模型 / 语义召回", HealthState.DOWN, latency,
                    "调用失败或超时（详见后端日志）", semanticIndex.status());
        }));
        return results;
    }

    // ---- Redis and database pages ----

    public OpsDtos.RedisInfo redisInfo() {
        try {
            long started = System.nanoTime();
            redisTemplate.execute((RedisCallback<String>) RedisConnection::ping);
            long latency = millisSince(started);
            Long dbSize = redisTemplate.execute((RedisCallback<Long>) connection -> connection.serverCommands().dbSize());
            Properties memory = redisTemplate.execute((RedisCallback<Properties>) connection -> connection.serverCommands().info("memory"));
            List<OpsDtos.RedisPrefix> prefixes = new ArrayList<>();
            long known = 0;
            for (String[] prefix : REDIS_PREFIXES) {
                long count = countKeys(prefix[0]);
                known += count;
                prefixes.add(new OpsDtos.RedisPrefix(prefix[0] + "*", prefix[1], count, count >= REDIS_SCAN_CAP));
            }
            if (dbSize != null) {
                prefixes.add(new OpsDtos.RedisPrefix("（其他）", "不属于以上前缀的键", Math.max(0, dbSize - known), false));
            }
            return new OpsDtos.RedisInfo(HealthState.UP, latency, dbSize,
                    memory == null ? null : memory.getProperty("used_memory_human"),
                    memory == null ? null : memory.getProperty("maxmemory_human"),
                    prefixes, null);
        } catch (RuntimeException ex) {
            return new OpsDtos.RedisInfo(HealthState.DOWN, null, null, null, null, List.of(), describe(ex));
        }
    }

    /** SCAN, never KEYS: it walks the keyspace in small batches and does not block Redis. */
    private long countKeys(String prefix) {
        Long count = redisTemplate.execute((RedisCallback<Long>) connection -> {
            long found = 0;
            ScanOptions options = ScanOptions.scanOptions().match(prefix + "*").count(500).build();
            try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                while (cursor.hasNext() && found < REDIS_SCAN_CAP) {
                    cursor.next();
                    found++;
                }
            }
            return found;
        });
        return count == null ? 0 : count;
    }

    public OpsDtos.DatabaseInfo databaseInfo() {
        String product = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
        List<OpsDtos.Migration> migrations = jdbcTemplate.query("""
                        SELECT version, description, success, installed_on, execution_time
                        FROM flyway_schema_history
                        WHERE version IS NOT NULL
                        ORDER BY installed_rank DESC
                        LIMIT 12
                        """,
                (rs, rowNum) -> {
                    Timestamp installedOn = rs.getTimestamp("installed_on");
                    return new OpsDtos.Migration(
                            rs.getString("version"),
                            rs.getString("description"),
                            rs.getBoolean("success"),
                            installedOn == null ? null : installedOn.toInstant(),
                            rs.getLong("execution_time"));
                });
        List<OpsDtos.TableSize> tables = jdbcTemplate.query("""
                        SELECT table_name, table_rows, data_length, index_length
                        FROM information_schema.tables
                        WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
                        ORDER BY data_length + index_length DESC
                        """,
                (rs, rowNum) -> new OpsDtos.TableSize(
                        rs.getString("table_name"),
                        rs.getLong("table_rows"),
                        rs.getLong("data_length") / 1024,
                        rs.getLong("index_length") / 1024));
        return new OpsDtos.DatabaseInfo(product, latestMigration().orElse(null), migrations, pool(), tables);
    }

    private Optional<String> latestMigration() {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT version FROM flyway_schema_history
                    WHERE success = 1 AND version IS NOT NULL
                    ORDER BY installed_rank DESC LIMIT 1
                    """, String.class));
        } catch (DataAccessException ex) {
            return Optional.empty();
        }
    }

    private Map<String, Object> pool() {
        Map<String, Object> pool = new LinkedHashMap<>();
        try {
            HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
            HikariPoolMXBean bean = hikari.getHikariPoolMXBean();
            pool.put("max", hikari.getMaximumPoolSize());
            if (bean != null) {
                pool.put("active", bean.getActiveConnections());
                pool.put("idle", bean.getIdleConnections());
                pool.put("total", bean.getTotalConnections());
                pool.put("waiting", bean.getThreadsAwaitingConnection());
            }
        } catch (Exception ignored) {
            // Not a Hikari pool (tests); the card simply shows no pool numbers.
        }
        return pool;
    }

    // ---- Helpers ----

    interface Probe {
        DependencyHealth run() throws Exception;
    }

    static DependencyHealth probe(String key, String name, Probe probe) {
        try {
            return probe.run();
        } catch (Exception ex) {
            return new DependencyHealth(key, name, HealthState.DOWN, null, "连接失败：" + describe(ex), Map.of());
        }
    }

    private static void safeCount(Map<String, Long> counters, String key, Supplier<Long> supplier) {
        try {
            counters.put(key, supplier.get());
        } catch (RuntimeException ignored) {
            // Missing counter rather than a broken overview.
        }
    }

    static String describe(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return abbreviate(message == null || message.isBlank() ? root.getClass().getSimpleName() : message, 200);
    }

    private static String abbreviate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static long millisSince(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }
}
