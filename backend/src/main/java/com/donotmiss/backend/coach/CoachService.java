package com.donotmiss.backend.coach;

import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.donotmiss.backend.memory.UserMemoryDtos;
import com.donotmiss.backend.memory.UserMemoryService;
import com.donotmiss.backend.memory.UserProfileSnapshotDirtyService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CoachService {
    private final CoachMessageRepository messageRepository;
    private final CoachLogRepository logRepository;
    private final CoachMemoryReviewRepository memoryReviewRepository;
    private final UserMemoryService userMemoryService;
    private final UserProfileSnapshotDirtyService profileSnapshotDirtyService;
    private final OpenAiCompatibleLlmClient llmClient;
    private final ObjectMapper objectMapper;

    public CoachService(CoachMessageRepository messageRepository,
                        CoachLogRepository logRepository,
                        CoachMemoryReviewRepository memoryReviewRepository,
                        UserMemoryService userMemoryService,
                        UserProfileSnapshotDirtyService profileSnapshotDirtyService,
                        OpenAiCompatibleLlmClient llmClient,
                        ObjectMapper objectMapper) {
        this.messageRepository = messageRepository;
        this.logRepository = logRepository;
        this.memoryReviewRepository = memoryReviewRepository;
        this.userMemoryService = userMemoryService;
        this.profileSnapshotDirtyService = profileSnapshotDirtyService;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<CoachDtos.CoachMessageResponse> messages(String userId, LocalDate date) {
        return messageRepository.findByUserIdAndMessageDateOrderByCreatedAtAsc(userId, dateOrToday(date)).stream()
                .map(CoachDtos.CoachMessageResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CoachDtos.CoachLogResponse> logs(String userId) {
        return logRepository.findByUserIdOrderByLogDateDesc(userId).stream()
                .map(CoachDtos.CoachLogResponse::from)
                .toList();
    }

    @Transactional
    public List<CoachDtos.CoachMemoryReviewResponse> memoryReviews(String userId) {
        ensureMemoryReviews(userId);
        return memoryReviewRepository.findTop20ByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(CoachDtos.CoachMemoryReviewResponse::from)
                .toList();
    }

    @Transactional
    public CoachDtos.ChatResponse chat(String userId, CoachDtos.ChatRequest request) {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        CoachMessageEntity userMessage = saveMessage(userId, CoachMessageRole.USER, request.message().trim(), today);
        List<CoachMessageEntity> todaysMessages = messageRepository.findByUserIdAndMessageDateOrderByCreatedAtAsc(userId, today);
        UserMemoryDtos.Profile memory = userMemoryService.profile(userId);
        ensureMemoryReviews(userId);
        boolean answeringReviewPrompt = answersRecentReviewPrompt(todaysMessages);
        List<CoachMemoryReviewEntity> dueReviews = answeringReviewPrompt
                ? List.of()
                : dueMemoryReviews(userId).stream().limit(1).toList();

        CoachDtos.CoachLogResponse generatedLog = null;
        boolean shouldGenerateLog = wantsLog(request.message());
        String reply;

        if (shouldGenerateLog) {
            generatedLog = generateLogEntity(userId, today, todaysMessages, memory)
                    .map(CoachDtos.CoachLogResponse::from)
                    .orElse(null);
            reply = generatedLog == null
                    ? "我已经整理了今天的对话，但内容还不够生成一篇有价值的日志。可以再告诉我今天做了什么、卡在哪里、学到了什么。"
                    : "日志已经生成并保存。你可以在右侧日志列表里查看，也可以继续补充今天的收获。";
        } else {
            reply = coachReply(request.message(), todaysMessages, memory, dueReviews, answeringReviewPrompt);
            if (!answeringReviewPrompt) {
                markReviewsUsed(dueReviews);
            }
        }

        CoachMessageEntity assistant = saveMessage(userId, CoachMessageRole.ASSISTANT, reply, today);
        return new CoachDtos.ChatResponse(CoachDtos.CoachMessageResponse.from(assistant), generatedLog, generatedLog != null);
    }

    @Transactional
    public CoachDtos.CoachLogResponse generateLog(String userId, CoachDtos.GenerateLogRequest request) {
        LocalDate date = dateOrToday(request.date());
        List<CoachMessageEntity> messages = messageRepository.findByUserIdAndMessageDateOrderByCreatedAtAsc(userId, date);
        UserMemoryDtos.Profile memory = userMemoryService.profile(userId);
        return CoachDtos.CoachLogResponse.from(generateLogEntity(userId, date, messages, memory)
                .orElseGet(() -> saveLog(userId, date, "成长日志", "今天的对话内容还较少。", "今天暂时没有足够上下文，建议补充做了什么、学到了什么和下一步计划。", List.of("待补充"))));
    }

    private String coachReply(String latestMessage,
                              List<CoachMessageEntity> messages,
                              UserMemoryDtos.Profile memory,
                              List<CoachMemoryReviewEntity> dueReviews,
                              boolean answeringReviewPrompt) {
        if (!llmClient.isEnabled()) {
            return fallbackReply(latestMessage, dueReviews);
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("latestMessage", latestMessage);
        context.put("studentMemory", memory);
        context.put("todayMessages", messages.stream().limit(20).map(this::messageContext).toList());
        context.put("dueLongTermMemories", dueReviews.stream().map(this::memoryReviewContext).toList());
        context.put("latestMessageAppearsToAnswerReview", answeringReviewPrompt);

        String prompt = """
                重要规则：如果后端上下文 latestMessageAppearsToAnswerReview=true，说明用户正在回答上一轮复习问题，本轮不要再追加新的复习问题；请先评价、纠正或延展用户这次回答。
                你是 do not miss 的学习教练，风格自然、聪明、能正常聊技术问题。
                你需要根据用户最新消息直接回答，不要把所有问题都强行理解成留学、成长或活动复盘。
                如果 dueLongTermMemories 不为空，请在回答中自然加入 1 个短复习问题，帮助用户回忆过去日志里的经验；
                如果用户当前问题很明确且与复习无关，先认真回答当前问题，再用一句话轻轻带到复习，不要喧宾夺主。
                不要编造用户没有做过的经历。回答控制在 600 字以内。

                后端上下文 JSON：
                %s
                """.formatted(toJsonLike(context));

        return llmClient.chatPlain(prompt)
                .filter(reply -> reply != null && !reply.isBlank())
                .map(reply -> compact(reply, 800))
                .orElseGet(() -> fallbackReply(latestMessage, dueReviews));
    }

    private Optional<CoachLogEntity> generateLogEntity(String userId,
                                                       LocalDate date,
                                                       List<CoachMessageEntity> messages,
                                                       UserMemoryDtos.Profile memory) {
        if (messages.isEmpty()) {
            return Optional.empty();
        }

        if (!llmClient.isEnabled()) {
            String content = messages.stream()
                    .filter(message -> message.getRole() == CoachMessageRole.USER)
                    .map(CoachMessageEntity::getContent)
                    .reduce("", (left, right) -> left + "\n" + right)
                    .trim();
            if (content.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(saveLog(userId, date, "今日成长日志", compact(content, 300), content, List.of("复盘", "自我记录")));
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("studentMemory", memory);
        context.put("messages", messages.stream().limit(30).map(this::messageContext).toList());

        String systemPrompt = """
                你是 do not miss 的日志整理 Agent。
                你只能根据今天的教练对话生成日志，不能编造用户没有说过的行动。
                输出严格 JSON：
                {"title":"标题","summary":"摘要","content":"日志正文","tags":["标签"]}
                """;
        String userPrompt = """
                请把下面对话整理成一篇成长日志。
                要求：
                1. title 不超过 30 字。
                2. summary 不超过 120 字。
                3. content 包含：今天做了什么、学到了什么、遇到的问题、下一步。
                4. tags 返回 2-5 个短标签。

                后端数据 JSON：
                %s
                """.formatted(toJsonLike(context));

        return llmClient.chatForJson(systemPrompt, userPrompt, CoachDtos.CoachLogModelResponse.class)
                .map(output -> saveLog(
                        userId,
                        date,
                        compact(firstPresent(output.title(), "今日成长日志"), 120),
                        compact(firstPresent(output.summary(), "已根据今天的教练对话生成日志。"), 1000),
                        compact(firstPresent(output.content(), "今天完成了一些学习或实践，并进行了简短复盘。"), 3000),
                        cleanTags(output.tags())
                ));
    }

    private CoachLogEntity saveLog(String userId, LocalDate date, String title, String summary, String content, List<String> tags) {
        CoachLogEntity log = logRepository.findByUserIdAndLogDate(userId, date).orElseGet(CoachLogEntity::new);
        log.setUserId(userId);
        log.setLogDate(date);
        log.setTitle(title);
        log.setSummary(summary);
        log.setContent(content);
        log.setTags(String.join(",", tags));
        CoachLogEntity saved = logRepository.save(log);
        upsertMemoryReview(userId, saved);
        profileSnapshotDirtyService.markDirty(userId, "coach-log-saved");
        return saved;
    }

    private CoachMessageEntity saveMessage(String userId, CoachMessageRole role, String content, LocalDate date) {
        CoachMessageEntity message = new CoachMessageEntity();
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(compact(content, 3000));
        message.setMessageDate(date);
        return messageRepository.save(message);
    }

    private Map<String, Object> messageContext(CoachMessageEntity message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("role", message.getRole().name());
        data.put("content", message.getContent());
        data.put("createdAt", message.getCreatedAt());
        return data;
    }

    private Map<String, Object> memoryReviewContext(CoachMemoryReviewEntity memory) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", memory.getId());
        data.put("title", memory.getTitle());
        data.put("memoryText", memory.getMemoryText());
        data.put("tags", memory.getTags());
        data.put("reviewCount", memory.getReviewCount());
        data.put("lastReviewedAt", memory.getLastReviewedAt());
        data.put("nextReviewAt", memory.getNextReviewAt());
        return data;
    }

    private boolean wantsLog(String message) {
        String normalized = message == null ? "" : message;
        return normalized.contains("生成日志")
                || normalized.contains("写日志")
                || normalized.contains("保存日志")
                || normalized.contains("总结今天")
                || normalized.toLowerCase().contains("generate log");
    }

    private boolean answersRecentReviewPrompt(List<CoachMessageEntity> messages) {
        if (messages == null || messages.size() < 2) {
            return false;
        }
        CoachMessageEntity latest = messages.get(messages.size() - 1);
        if (latest.getRole() != CoachMessageRole.USER) {
            return false;
        }
        int lowerBound = Math.max(messages.size() - 6, 0);
        for (int i = messages.size() - 2; i >= lowerBound; i--) {
            CoachMessageEntity message = messages.get(i);
            if (message.getRole() == CoachMessageRole.ASSISTANT) {
                return looksLikeReviewPrompt(message.getContent());
            }
        }
        return false;
    }

    private boolean looksLikeReviewPrompt(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        boolean reviewLike = content.contains("\u590d\u4e60")
                || content.contains("\u590d\u76d8\u65e5\u5fd7")
                || content.contains("\u8fd8\u8bb0\u5f97")
                || content.contains("\u957f\u671f\u8bb0\u5fc6");
        boolean questionLike = content.contains("?")
                || content.contains("\uff1f")
                || content.contains("\u5417")
                || content.contains("\u4ec0\u4e48")
                || content.contains("\u54ea");
        return reviewLike && questionLike;
    }

    private String fallbackReply(String latestMessage, List<CoachMemoryReviewEntity> dueReviews) {
        if (!dueReviews.isEmpty()) {
            CoachMemoryReviewEntity memory = dueReviews.get(0);
            return "我先接住你现在说的事。顺便做一个很短的复习：之前你在《"
                    + memory.getTitle()
                    + "》里记录过一段经验，你现在还记得当时最有用的一个方法或一个坑是什么吗？";
        }
        String text = latestMessage == null ? "" : latestMessage.trim();
        if (text.length() < 12) {
            return "我在。你可以先用一句话说今天做了什么，我会帮你把它拆成“行动、收获、下一步”。";
        }
        return "听起来今天已经有一些推进了。为了让它变成可积累的经历，我想追问一个问题：这件事里你最明确学到的一个方法或一个坑是什么？";
    }

    private void ensureMemoryReviews(String userId) {
        logRepository.findByUserIdOrderByLogDateDesc(userId).stream()
                .limit(30)
                .forEach(log -> upsertMemoryReview(userId, log));
    }

    private List<CoachMemoryReviewEntity> dueMemoryReviews(String userId) {
        return memoryReviewRepository.findTop3ByUserIdAndNextReviewAtLessThanEqualOrderByNextReviewAtAsc(userId, Instant.now());
    }

    private void upsertMemoryReview(String userId, CoachLogEntity log) {
        if (log.getId() == null) {
            return;
        }
        CoachMemoryReviewEntity memory = memoryReviewRepository.findByUserIdAndSourceLogId(userId, log.getId())
                .orElseGet(CoachMemoryReviewEntity::new);
        memory.setUserId(userId);
        memory.setSourceLogId(log.getId());
        memory.setMemoryType(CoachMemoryType.REFLECTION);
        memory.setTitle(compact(firstPresent(log.getTitle(), "成长复习"), 160));
        memory.setMemoryText(compact(memoryText(log), 1200));
        memory.setTags(log.getTags());
        memory.setStrength(Math.max(1, Math.min(5, tagCount(log.getTags()))));
        if (memory.getNextReviewAt() == null) {
            memory.setNextReviewAt(Instant.now());
        }
        memoryReviewRepository.save(memory);
    }

    private void markReviewsUsed(List<CoachMemoryReviewEntity> reviews) {
        if (reviews.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (CoachMemoryReviewEntity review : reviews) {
            int nextCount = review.getReviewCount() + 1;
            review.setReviewCount(nextCount);
            review.setLastReviewedAt(now);
            review.setNextReviewAt(now.plus(reviewInterval(nextCount)));
        }
        memoryReviewRepository.saveAll(reviews);
    }

    private Duration reviewInterval(int reviewCount) {
        return switch (reviewCount) {
            case 1 -> Duration.ofDays(1);
            case 2 -> Duration.ofDays(3);
            case 3 -> Duration.ofDays(7);
            case 4 -> Duration.ofDays(14);
            default -> Duration.ofDays(30);
        };
    }

    private String memoryText(CoachLogEntity log) {
        return ("标题：" + firstPresent(log.getTitle(), "成长日志")
                + "\n摘要：" + firstPresent(log.getSummary(), "")
                + "\n正文：" + firstPresent(log.getContent(), "")).trim();
    }

    private int tagCount(String tags) {
        if (tags == null || tags.isBlank()) {
            return 1;
        }
        return (int) List.of(tags.split(",")).stream()
                .map(String::trim)
                .filter(tag -> !tag.isBlank())
                .count();
    }

    private List<String> cleanTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of("复盘");
        }
        return tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(tag -> compact(tag.trim(), 24))
                .distinct()
                .limit(5)
                .toList();
    }

    private LocalDate dateOrToday(LocalDate date) {
        return date == null ? LocalDate.now(ZoneId.systemDefault()) : date;
    }

    private String toJsonLike(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    private String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String compact(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
