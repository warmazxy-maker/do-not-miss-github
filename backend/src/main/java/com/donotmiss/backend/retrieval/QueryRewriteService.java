package com.donotmiss.backend.retrieval;

import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.donotmiss.backend.memory.UserMemoryDtos;
import com.donotmiss.backend.memory.UserMemoryService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Service
public class QueryRewriteService {
    private static final int MAX_INTENT_TAGS = 6;
    private static final int MAX_SKILLS = 6;
    private static final int MAX_CATEGORIES = 3;
    private static final int MAX_CONSTRAINTS = 5;
    private static final List<String> AI_AGENT_TERMS = List.of(
            "ai", "agent", "llm", "大模型", "人工智能", "智能体", "tool calling", "rag"
    );
    private static final List<String> STACK_TERMS = List.of(
            "java", "spring", "spring boot", "python", "go", "vue", "react"
    );

    private final OpenAiCompatibleLlmClient llmClient;
    private final UserMemoryService userMemoryService;

    public QueryRewriteService(OpenAiCompatibleLlmClient llmClient, UserMemoryService userMemoryService) {
        this.llmClient = llmClient;
        this.userMemoryService = userMemoryService;
    }

    public RetrievalDtos.QueryRewrite rewrite(String need, UserMemoryDtos.Profile memory) {
        return rewrite(need, memory, null);
    }

    public RetrievalDtos.QueryRewrite rewrite(String need,
                                              UserMemoryDtos.Profile memory,
                                              RetrievalDtos.SearchSessionContext previousContext) {
        RetrievalDtos.QueryRewrite ruleRewrite = ruleBasedRewrite(need);
        if (!llmClient.isEnabled()) {
            return applySessionContext(ruleRewrite, previousContext);
        }

        String systemPrompt = """
                你是 do not miss 的 Query Rewrite Agent。
                你的任务是把学生的自然语言需求改写成适合检索社会实践、挑战和学习机会的结构化查询。
                你同时负责短期检索上下文的动作选择：判断当前输入是延续上一轮、细化上一轮，还是开启新话题。
                只抽取用户明确表达或规则兜底已经识别出的信息，不要把用户画像里的长期偏好强行写进本次查询。
                不要编造活动，不要生成推荐结果，只做需求理解和检索条件抽取。
                contextDecision.action 只能取 MERGE、KEEP、CLEAR：
                - MERGE：当前输入是在上一轮目标上补充地点、时间、难度、收益等条件，例如“最好线上”。
                - KEEP：当前输入没有新增稳定条件，但仍然围绕上一轮搜索，例如“还有吗”“再推荐一点”。
                - CLEAR：当前输入切换了核心目标，例如从“学日语”切到“学编程语言”。
                输出严格 JSON，不要输出 Markdown。
                JSON 字段：
                {
                  "contextDecision": {"action":"MERGE/KEEP/CLEAR","relation":"REFINE/CONTINUE/NEW_TOPIC/AMBIGUOUS","confidence":0.0-1.0,"reason":"判断理由"},
                  "goal": "学生核心目标",
                  "level": "zero/basic/intermediate/advanced/unknown",
                  "intentTags": ["意图标签，最多6个"],
                  "skills": ["技能词，最多6个"],
                  "preferredCategories": ["偏好类别，最多3个"],
                  "preferredLocation": "地点偏好；没有明确地点就留空",
                  "benefitPreference": "skill/money/both/unknown",
                  "constraints": ["时间、地点、难度等约束，最多5个"]
                }
                """;
        String userPrompt = """
                学生原始需求：
                %s

                压缩后的学生画像，只能作为弱参考，不能覆盖本次原始需求：
                %s

                上一轮检索上下文；如果为空，说明没有可继承上下文：
                %s

                规则兜底理解：
                goal=%s
                intentTags=%s
                skills=%s
                """.formatted(
                nullToEmpty(need),
                userMemoryService.promptText(memory),
                previousContextText(previousContext),
                nullToEmpty(ruleRewrite.goal()),
                ruleRewrite.intentTags(),
                ruleRewrite.skills()
        );

        return llmClient.chatForJson(systemPrompt, userPrompt, RetrievalDtos.QueryRewriteModelResponse.class)
                .map(output -> normalizeModelRewrite(need, output, ruleRewrite, previousContext))
                .orElseGet(() -> applySessionContext(ruleRewrite, previousContext));
    }

    private RetrievalDtos.QueryRewrite normalizeModelRewrite(String need,
                                                             RetrievalDtos.QueryRewriteModelResponse output,
                                                             RetrievalDtos.QueryRewrite fallback,
                                                             RetrievalDtos.SearchSessionContext previousContext) {
        LinkedHashSet<String> mergedIntentTags = merge(output.intentTags(), fallback.intentTags());
        LinkedHashSet<String> mergedSkills = merge(output.skills(), fallback.skills());
        LinkedHashSet<String> mergedCategories = merge(output.preferredCategories(), fallback.preferredCategories());
        LinkedHashSet<String> mergedConstraints = merge(output.constraints(), fallback.constraints());

        LinkedHashSet<String> intentTags = constrainTerms(need, mergedIntentTags, fallback.intentTags(), MAX_INTENT_TAGS);
        LinkedHashSet<String> skills = constrainTerms(need, mergedSkills, fallback.skills(), MAX_SKILLS);
        LinkedHashSet<String> categories = constrainTerms(need, mergedCategories, fallback.preferredCategories(), MAX_CATEGORIES);
        LinkedHashSet<String> constraints = constrainTerms(need, mergedConstraints, fallback.constraints(), MAX_CONSTRAINTS);

        String rawGoal = firstPresent(output.goal(), fallback.goal(), nullToEmpty(need));
        String goal = constrainGoal(rawGoal, need, fallback.goal());
        String level = firstPresent(output.level(), fallback.level(), "unknown");
        String location = constrainOptional(firstPresent(output.preferredLocation(), fallback.preferredLocation(), ""), need, fallback.preferredLocation());
        String benefit = constrainBenefit(firstPresent(output.benefitPreference(), fallback.benefitPreference(), "unknown"), need, fallback.benefitPreference());
        String rewrittenQuery = buildRewrittenQuery(need, goal, level, intentTags, skills, categories, location, benefit, constraints);
        RetrievalDtos.ContextDecision contextDecision = normalizeContextDecision(output.contextDecision(), fallback.contextDecision(), previousContext);

        List<String> evidence = new ArrayList<>(fallback.evidence());
        evidence.add("llm query rewrite");
        evidence.add("context action: " + contextDecision.action());
        if (!intentTags.equals(mergedIntentTags)
                || !skills.equals(mergedSkills)
                || !categories.equals(mergedCategories)
                || !constraints.equals(mergedConstraints)
                || !goal.equals(rawGoal)) {
            evidence.add("rewrite guard: conservative filtering");
        }
        return applySessionContext(new RetrievalDtos.QueryRewrite(
                llmClient.modeLabel(),
                nullToEmpty(need),
                rewrittenQuery,
                goal,
                level,
                List.copyOf(intentTags),
                List.copyOf(skills),
                List.copyOf(categories),
                location,
                benefit,
                List.copyOf(constraints),
                contextDecision,
                evidence.stream().distinct().toList()
        ), previousContext);
    }

    private RetrievalDtos.QueryRewrite ruleBasedRewrite(String need) {
        String originalNeed = nullToEmpty(need);
        String normalizedNeed = originalNeed.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> intentTags = new LinkedHashSet<>();
        LinkedHashSet<String> skills = new LinkedHashSet<>();
        LinkedHashSet<String> categories = new LinkedHashSet<>();
        LinkedHashSet<String> constraints = new LinkedHashSet<>();
        List<String> evidence = new ArrayList<>();
        String goal = "";
        String level = "unknown";
        String location = "";
        String benefit = "unknown";

        boolean aiAgentIntent = matches(normalizedNeed, "ai", "agent", "llm", "大模型", "人工智能", "智能体", "rag");
        boolean explicitProgrammingStack = matches(normalizedNeed, "java", "spring", "spring boot", "python", "go", "vue", "react");

        if (aiAgentIntent) {
            goal = "AI Agent 开发学习";
            intentTags.addAll(List.of("AI", "Agent", "LLM", "大模型", "AI应用开发"));
            skills.addAll(List.of("Agent开发", "LLM应用", "提示词工程", "工具调用", "RAG"));
            categories.addAll(List.of("线上", "企业"));
            benefit = "skill";
            evidence.add("rule: ai/agent intent");
        }

        if ((!aiAgentIntent || explicitProgrammingStack)
                && matches(normalizedNeed, "java", "spring", "spring boot", "后端", "编程", "程序设计", "程序", "开发", "项目", "外卖", "微信")) {
            goal = firstPresent(goal, "编程项目实践");
            intentTags.addAll(List.of("java", "后端开发", "项目开发", "代码实践"));
            skills.addAll(List.of("Java", "Spring Boot", "项目开发"));
            categories.add("企业");
            evidence.add("rule: programming/java intent");
        }
        if (matches(normalizedNeed, "日语", "n1", "n2", "n3", "n4", "n5", "日本语", "口语", "留学", "翻译")) {
            goal = firstPresent(goal, "日语学习与留学准备");
            intentTags.addAll(List.of("日语", "日本语", "口语", "翻译", "语言沟通", "留学准备"));
            skills.addAll(List.of("日语基础", "日语口语", "中日翻译", "跨文化沟通"));
            categories.addAll(List.of("校内", "文化", "公益"));
            evidence.add("rule: japanese/language intent");
        }
        if (matches(normalizedNeed, "身体", "锻炼", "健身", "运动", "减肥", "跑步")) {
            goal = firstPresent(goal, "身体锻炼");
            intentTags.addAll(List.of("身体锻炼", "运动", "健身", "体能"));
            skills.addAll(List.of("运动习惯", "体能管理"));
            evidence.add("rule: fitness intent");
        }
        if (matches(normalizedNeed, "研究", "调研", "访谈", "问卷", "数据", "论文")) {
            goal = firstPresent(goal, "研究调研能力提升");
            intentTags.addAll(List.of("研究", "调研", "访谈", "问卷", "数据整理"));
            skills.addAll(List.of("社会调研", "访谈整理", "数据录入"));
            categories.add("研究");
            evidence.add("rule: research intent");
        }
        if (matches(normalizedNeed, "零基础", "从零", "初学", "新手", "入门")) {
            level = "zero";
            constraints.add("低门槛");
            constraints.add("适合初学者");
            evidence.add("rule: zero/basic level");
        }
        if (matches(normalizedNeed, "大阪")) {
            location = "大阪";
            evidence.add("rule: location Osaka");
        } else if (matches(normalizedNeed, "东京")) {
            location = "东京";
            evidence.add("rule: location Tokyo");
        } else if (matches(normalizedNeed, "线上", "远程", "在家")) {
            location = "线上";
            constraints.add("线上可参与");
            evidence.add("rule: online preference");
        }
        if (matches(normalizedNeed, "报酬", "金钱", "兼职", "有偿", "钱")) {
            benefit = "money";
            evidence.add("rule: money benefit");
        } else if (matches(normalizedNeed, "技能", "经验", "成长", "练习", "学习", "学")) {
            benefit = firstPresent(benefit, "skill");
            evidence.add("rule: skill benefit");
        }

        String rewrittenQuery = buildRewrittenQuery(originalNeed, goal, level, intentTags, skills, categories, location, benefit, constraints);
        return new RetrievalDtos.QueryRewrite(
                "rule",
                originalNeed,
                rewrittenQuery,
                firstPresent(goal, originalNeed),
                level,
                List.copyOf(intentTags),
                List.copyOf(skills),
                List.copyOf(categories),
                location,
                benefit,
                List.copyOf(constraints),
                ruleContextDecision(normalizedNeed),
                evidence
        );
    }

    private RetrievalDtos.ContextDecision ruleContextDecision(String normalizedNeed) {
        if (matches(normalizedNeed, "重新", "重来", "换个", "换一个", "不找", "清空", "算了")) {
            return new RetrievalDtos.ContextDecision("CLEAR", "NEW_TOPIC", 0.9, "用户显式要求重新开始或换方向");
        }
        if (matches(normalizedNeed, "线上", "线下", "大阪", "东京", "周末", "晚上", "下午", "报酬", "有偿", "零基础", "从零", "初学", "最好", "更好")
                && !matches(normalizedNeed, "日语", "n1", "n2", "n3", "java", "spring", "python", "go", "ai", "agent", "llm", "大模型", "研究", "健身")) {
            return new RetrievalDtos.ContextDecision("MERGE", "REFINE", 0.72, "规则判断为补充地点、时间、收益或难度约束");
        }
        if (matches(normalizedNeed, "还有", "再推荐", "换一批", "更多")) {
            return new RetrievalDtos.ContextDecision("KEEP", "CONTINUE", 0.7, "规则判断为继续上一轮搜索");
        }
        return new RetrievalDtos.ContextDecision("CLEAR", "NEW_TOPIC", 0.62, "规则默认把明确需求作为新搜索主题");
    }

    private RetrievalDtos.ContextDecision normalizeContextDecision(RetrievalDtos.ContextDecision modelDecision,
                                                                   RetrievalDtos.ContextDecision fallbackDecision,
                                                                   RetrievalDtos.SearchSessionContext previousContext) {
        if (previousContext == null) {
            return new RetrievalDtos.ContextDecision("CLEAR", "NEW_TOPIC", 1.0, "没有可继承的上一轮检索上下文");
        }
        RetrievalDtos.ContextDecision fallback = fallbackDecision == null
                ? new RetrievalDtos.ContextDecision("CLEAR", "NEW_TOPIC", 0.6, "缺少模型上下文决策，使用默认新主题")
                : fallbackDecision;
        if (modelDecision == null || modelDecision.action() == null || modelDecision.action().isBlank()) {
            return fallback;
        }
        String action = normalizeAction(modelDecision.action());
        if (modelDecision.confidence() < 0.55) {
            return fallback;
        }
        return new RetrievalDtos.ContextDecision(
                action,
                firstPresent(modelDecision.relation(), fallback.relation(), relationForAction(action)),
                Math.min(Math.max(modelDecision.confidence(), 0), 1),
                firstPresent(modelDecision.reason(), fallback.reason(), "模型判断上下文动作")
        );
    }

    private RetrievalDtos.QueryRewrite applySessionContext(RetrievalDtos.QueryRewrite current,
                                                           RetrievalDtos.SearchSessionContext previousContext) {
        RetrievalDtos.ContextDecision decision = current.contextDecision();
        if (previousContext == null || "CLEAR".equalsIgnoreCase(decision.action())) {
            return current;
        }
        if ("KEEP".equalsIgnoreCase(decision.action())) {
            List<String> evidence = new ArrayList<>(current.evidence());
            evidence.add("search session: keep previous context");
            return new RetrievalDtos.QueryRewrite(
                    current.mode(),
                    current.originalQuery(),
                    previousContext.rewrittenQuery(),
                    previousContext.goal(),
                    previousContext.level(),
                    previousContext.intentTags(),
                    previousContext.skills(),
                    previousContext.preferredCategories(),
                    previousContext.preferredLocation(),
                    previousContext.benefitPreference(),
                    previousContext.constraints(),
                    decision,
                    evidence.stream().distinct().toList()
            );
        }

        LinkedHashSet<String> intentTags = merge(previousContext.intentTags(), current.intentTags());
        LinkedHashSet<String> skills = merge(previousContext.skills(), current.skills());
        LinkedHashSet<String> categories = merge(previousContext.preferredCategories(), current.preferredCategories());
        LinkedHashSet<String> constraints = merge(previousContext.constraints(), current.constraints());
        String goal = hasTopicSignals(current) ? current.goal() : previousContext.goal();
        String level = "unknown".equalsIgnoreCase(nullToEmpty(current.level())) ? previousContext.level() : current.level();
        String location = firstPresent(current.preferredLocation(), previousContext.preferredLocation());
        String benefit = "unknown".equalsIgnoreCase(nullToEmpty(current.benefitPreference()))
                ? previousContext.benefitPreference()
                : current.benefitPreference();
        String combinedNeed = String.join(" ", List.of(
                nullToEmpty(previousContext.rewrittenQuery()),
                nullToEmpty(current.originalQuery())
        )).trim();
        String rewrittenQuery = buildRewrittenQuery(combinedNeed, goal, level, intentTags, skills, categories, location, benefit, constraints);
        List<String> evidence = new ArrayList<>(current.evidence());
        evidence.add("search session: merged previous context");

        return new RetrievalDtos.QueryRewrite(
                current.mode(),
                current.originalQuery(),
                rewrittenQuery,
                goal,
                level,
                List.copyOf(intentTags),
                List.copyOf(skills),
                List.copyOf(categories),
                location,
                benefit,
                List.copyOf(constraints),
                decision,
                evidence.stream().distinct().toList()
        );
    }

    private boolean hasTopicSignals(RetrievalDtos.QueryRewrite current) {
        return !current.intentTags().isEmpty()
                || !current.skills().isEmpty()
                || !current.preferredCategories().isEmpty();
    }

    private String previousContextText(RetrievalDtos.SearchSessionContext previousContext) {
        if (previousContext == null) {
            return "{}";
        }
        return """
                {
                  "goal": "%s",
                  "intentTags": %s,
                  "skills": %s,
                  "preferredCategories": %s,
                  "preferredLocation": "%s",
                  "benefitPreference": "%s",
                  "constraints": %s,
                  "recentUserMessages": %s
                }
                """.formatted(
                nullToEmpty(previousContext.goal()),
                previousContext.intentTags(),
                previousContext.skills(),
                previousContext.preferredCategories(),
                nullToEmpty(previousContext.preferredLocation()),
                nullToEmpty(previousContext.benefitPreference()),
                previousContext.constraints(),
                previousContext.recentUserMessages()
        );
    }

    private String normalizeAction(String action) {
        String normalized = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "MERGE", "KEEP", "CLEAR" -> normalized;
            default -> "CLEAR";
        };
    }

    private String relationForAction(String action) {
        return switch (normalizeAction(action)) {
            case "MERGE" -> "REFINE";
            case "KEEP" -> "CONTINUE";
            default -> "NEW_TOPIC";
        };
    }

    private String buildRewrittenQuery(String need,
                                       String goal,
                                       String level,
                                       LinkedHashSet<String> intentTags,
                                       LinkedHashSet<String> skills,
                                       LinkedHashSet<String> categories,
                                       String location,
                                       String benefit,
                                       LinkedHashSet<String> constraints) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        terms.add(nullToEmpty(need));
        terms.add(nullToEmpty(goal));
        terms.add(nullToEmpty(level));
        terms.addAll(intentTags);
        terms.addAll(skills);
        terms.addAll(categories);
        terms.add(nullToEmpty(location));
        terms.add(nullToEmpty(benefit));
        terms.addAll(constraints);
        return String.join(" ", terms.stream().filter(value -> value != null && !value.isBlank()).toList()).trim();
    }

    private LinkedHashSet<String> constrainTerms(String need, LinkedHashSet<String> values, List<String> fallback, int max) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        List<String> fallbackValues = fallback == null ? List.of() : fallback;
        String sourceText = (nullToEmpty(need) + " " + String.join(" ", fallbackValues)).toLowerCase(Locale.ROOT);

        for (String value : values) {
            String cleaned = normalizeTerm(value);
            if (cleaned.isBlank()) {
                continue;
            }
            if (containsIgnoreCase(fallbackValues, cleaned) || isSupportedTerm(cleaned, sourceText, need)) {
                result.add(cleaned);
            }
            if (result.size() >= max) {
                break;
            }
        }

        for (String value : fallbackValues) {
            if (result.size() >= max) {
                break;
            }
            String cleaned = normalizeTerm(value);
            if (!cleaned.isBlank()) {
                result.add(cleaned);
            }
        }
        return result;
    }

    private String constrainGoal(String goal, String need, String fallbackGoal) {
        String cleaned = normalizeTerm(goal);
        if (cleaned.isBlank()) {
            return firstPresent(fallbackGoal, need);
        }
        String normalizedNeed = nullToEmpty(need).toLowerCase(Locale.ROOT);
        String normalizedGoal = cleaned.toLowerCase(Locale.ROOT);
        if (hasAiAgentSignal(normalizedNeed)
                && !hasExplicitStackSignal(normalizedNeed)
                && STACK_TERMS.stream().anyMatch(normalizedGoal::contains)) {
            return firstPresent(fallbackGoal, need);
        }
        if (cleaned.length() > 80) {
            return firstPresent(fallbackGoal, need);
        }
        return cleaned;
    }

    private String constrainOptional(String value, String need, String fallback) {
        String cleaned = normalizeTerm(value);
        String fallbackValue = normalizeTerm(fallback);
        if (cleaned.isBlank()) {
            return fallbackValue;
        }
        if (cleaned.equals(fallbackValue) || nullToEmpty(need).toLowerCase(Locale.ROOT).contains(cleaned.toLowerCase(Locale.ROOT))) {
            return cleaned;
        }
        return fallbackValue;
    }

    private String constrainBenefit(String value, String need, String fallback) {
        String cleaned = normalizeTerm(value).toLowerCase(Locale.ROOT);
        String fallbackValue = normalizeTerm(fallback).toLowerCase(Locale.ROOT);
        if (!fallbackValue.isBlank() && !"unknown".equals(fallbackValue)) {
            return fallbackValue;
        }
        String normalizedNeed = nullToEmpty(need).toLowerCase(Locale.ROOT);
        if (matches(normalizedNeed, "报酬", "金钱", "兼职", "有偿", "钱")) {
            return "money";
        }
        if (matches(normalizedNeed, "技能", "经验", "成长", "练习", "学习", "学", "提升")) {
            return "skill";
        }
        return List.of("skill", "money", "both", "unknown").contains(cleaned) ? cleaned : "unknown";
    }

    private boolean isSupportedTerm(String term, String sourceText, String need) {
        String normalizedTerm = term.toLowerCase(Locale.ROOT);
        if (sourceText.contains(normalizedTerm)) {
            return true;
        }
        if (hasAiAgentSignal(nullToEmpty(need).toLowerCase(Locale.ROOT))
                && AI_AGENT_TERMS.stream().anyMatch(aiTerm -> normalizedTerm.contains(aiTerm) || aiTerm.contains(normalizedTerm))) {
            return true;
        }
        return simpleTokens(sourceText).stream()
                .filter(token -> token.length() >= 2)
                .anyMatch(token -> normalizedTerm.contains(token) || token.contains(normalizedTerm));
    }

    private LinkedHashSet<String> merge(List<String> primary, List<String> fallback) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (primary != null) {
            primary.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).forEach(values::add);
        }
        if (fallback != null) {
            fallback.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).forEach(values::add);
        }
        return values;
    }

    private boolean matches(String text, String... words) {
        for (String word : words) {
            if (text.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAiAgentSignal(String normalizedNeed) {
        return AI_AGENT_TERMS.stream().anyMatch(normalizedNeed::contains);
    }

    private boolean hasExplicitStackSignal(String normalizedNeed) {
        return STACK_TERMS.stream().anyMatch(normalizedNeed::contains);
    }

    private boolean containsIgnoreCase(List<String> values, String target) {
        return values.stream()
                .filter(value -> value != null)
                .anyMatch(value -> value.trim().equalsIgnoreCase(target));
    }

    private List<String> simpleTokens(String value) {
        String normalized = nullToEmpty(value).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return List.of();
        }
        return List.of(normalized.split("[\\s,;，；、。！？!?()（）\\[\\]【】]+"));
    }

    private String normalizeTerm(String value) {
        return value == null ? "" : value.trim();
    }

    private String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
