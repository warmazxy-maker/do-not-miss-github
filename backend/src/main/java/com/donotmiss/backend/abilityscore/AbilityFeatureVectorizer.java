package com.donotmiss.backend.abilityscore;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
class AbilityFeatureVectorizer {
    private static final Pattern LATIN_TOKEN = Pattern.compile("[a-z][a-z0-9+#.]*");
    private static final List<AnchorDefinition> ANCHORS = List.of(
            anchor("java", "Java", "java", "spring", "springboot", "jvm", "maven"),
            anchor("javascript", "JavaScript", "javascript", "js", "typescript", "ts"),
            anchor("python", "Python", "python"),
            anchor("golang", "Go", "golang", "go语言"),
            anchor("agent", "Agent", "agent", "智能体", "langgraph", "multiagent"),
            anchor("llm", "LLM", "llm", "largelanguagemodel", "大语言模型", "大模型"),
            anchor("ai", "AI Engineering", "ai", "人工智能"),
            anchor("mysql", "MySQL", "mysql"),
            anchor("redis", "Redis", "redis"),
            anchor("opensearch", "OpenSearch", "opensearch", "elasticsearch", "es检索"),
            anchor("日语", "日语", "日语", "日本语", "jlpt", "n1", "n2", "n3", "n4", "n5"),
            anchor("英语", "英语", "英语", "英文", "cet4", "cet-4", "cet6", "cet-6", "雅思", "托福"),
            anchor("研究", "研究", "研究", "科研", "论文", "学术"),
            anchor("社会调研", "社会调研", "社会调研", "问卷", "访谈", "田野调查"),
            anchor("项目管理", "项目管理", "项目管理", "项目协调", "进度管理"),
            anchor("沟通", "沟通", "沟通", "表达", "协作", "团队合作"),
            anchor("内容运营", "内容运营", "内容运营", "新媒体运营", "社媒运营"),
            anchor("数据分析", "数据分析", "数据分析", "数据处理", "数据可视化"),
            anchor("游戏开发", "游戏开发", "游戏开发", "游戏设计")
    );

    FeatureVector vectorize(String label) {
        return vectorize(label, List.of());
    }

    FeatureVector vectorize(String label, List<AnchorDefinition> dynamicAnchors) {
        String normalized = normalize(label);
        Set<String> anchors = detectAnchors(normalized, dynamicAnchors);
        Set<String> tokens = tokens(normalized);
        Map<String, Double> vector = new HashMap<>();

        for (String token : tokens) {
            vector.merge("token:" + token, 1.0, Double::sum);
        }
        for (String gram : characterNgrams(normalized)) {
            vector.merge("gram:" + gram, 0.35, Double::sum);
        }
        for (String anchor : anchors) {
            vector.merge("anchor:" + anchor, 2.4, Double::sum);
        }
        return new FeatureVector(normalized, anchors, tokens, vector);
    }

    String preferredName(Set<String> anchors, List<UserAbilityStateEntity> members) {
        return preferredName(anchors, members, List.of());
    }

    String preferredName(Set<String> anchors,
                         List<UserAbilityStateEntity> members,
                         List<AnchorDefinition> dynamicAnchors) {
        if (anchors.size() == 1) {
            String key = anchors.iterator().next();
            return allAnchors(dynamicAnchors).stream()
                    .filter(anchor -> anchor.key().equals(key))
                    .map(AnchorDefinition::displayName)
                    .findFirst()
                    .orElseGet(() -> representativeName(members));
        }
        return representativeName(members);
    }

    double similarity(FeatureVector left, FeatureVector right) {
        double anchorScore = anchorScore(left.anchors(), right.anchors());
        double tokenScore = jaccard(left.tokens(), right.tokens());
        double cosineScore = cosine(left.vector(), right.vector());
        double score = 0.55 * anchorScore + 0.20 * tokenScore + 0.25 * cosineScore;

        if (!left.anchors().isEmpty()
                && !right.anchors().isEmpty()
                && left.anchors().stream().noneMatch(right.anchors()::contains)) {
            score = Math.min(score, 0.28);
        }
        return clamp(score);
    }

    Set<String> sharedAnchors(List<FeatureVector> vectors) {
        Set<String> result = new LinkedHashSet<>();
        for (FeatureVector vector : vectors) {
            result.addAll(vector.anchors());
        }
        return result;
    }

    private double anchorScore(Set<String> left, Set<String> right) {
        if (left.isEmpty() && right.isEmpty()) {
            return 0.0;
        }
        if (left.isEmpty() || right.isEmpty()) {
            return 0.15;
        }
        return jaccard(left, right);
    }

    private Set<String> detectAnchors(String normalized, List<AnchorDefinition> dynamicAnchors) {
        Set<String> result = new LinkedHashSet<>();
        for (AnchorDefinition anchor : allAnchors(dynamicAnchors)) {
            if (anchor.aliases().stream().anyMatch(alias -> containsAlias(normalized, normalize(alias)))) {
                result.add(anchor.key());
            }
        }
        if (result.contains("agent") || result.contains("llm")) {
            result.remove("ai");
        }
        return result;
    }

    private boolean containsAlias(String value, String alias) {
        if (alias.chars().allMatch(ch -> ch < 128) && alias.matches("[a-z0-9+#.]+")) {
            Matcher matcher = LATIN_TOKEN.matcher(value);
            while (matcher.find()) {
                if (matcher.group().equals(alias)) {
                    return true;
                }
            }
            return false;
        }
        return value.contains(alias);
    }

    private Set<String> tokens(String normalized) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = LATIN_TOKEN.matcher(normalized);
        while (matcher.find()) {
            result.add(matcher.group());
        }

        String chinese = normalized.replaceAll("[^\\u4e00-\\u9fff]", "");
        for (int i = 0; i < chinese.length() - 1; i++) {
            result.add(chinese.substring(i, i + 2));
        }
        return result;
    }

    private List<String> characterNgrams(String normalized) {
        String compact = normalized.replaceAll("[^a-z0-9\\u4e00-\\u9fff]", "");
        List<String> grams = new ArrayList<>();
        for (int i = 0; i < compact.length() - 1; i++) {
            grams.add(compact.substring(i, i + 2));
        }
        return grams;
    }

    private double cosine(Map<String, Double> left, Map<String, Double> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (Map.Entry<String, Double> entry : left.entrySet()) {
            leftNorm += entry.getValue() * entry.getValue();
            dot += entry.getValue() * right.getOrDefault(entry.getKey(), 0.0);
        }
        for (double value : right.values()) {
            rightNorm += value * value;
        }
        return leftNorm == 0.0 || rightNorm == 0.0 ? 0.0 : dot / Math.sqrt(leftNorm * rightNorm);
    }

    private double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() && right.isEmpty()) {
            return 0.0;
        }
        long intersection = left.stream().filter(right::contains).count();
        long union = left.size() + right.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private String representativeName(List<UserAbilityStateEntity> members) {
        return members.stream()
                .sorted((left, right) -> {
                    int score = right.getAbilityScore().compareTo(left.getAbilityScore());
                    if (score != 0) {
                        return score;
                    }
                    return Integer.compare(left.getDimensionName().length(), right.getDimensionName().length());
                })
                .map(UserAbilityStateEntity::getDimensionName)
                .findFirst()
                .orElse("未命名能力");
    }

    private String normalize(String value) {
        return String.valueOf(value == null ? "" : value)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('－', '-')
                .replace('＋', '+')
                .replaceAll("\\s+", "");
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    String normalizeKey(String value) {
        return normalize(value)
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "");
    }

    private List<AnchorDefinition> allAnchors(List<AnchorDefinition> dynamicAnchors) {
        if (dynamicAnchors == null || dynamicAnchors.isEmpty()) {
            return ANCHORS;
        }
        List<AnchorDefinition> all = new ArrayList<>(ANCHORS.size() + dynamicAnchors.size());
        all.addAll(ANCHORS);
        all.addAll(dynamicAnchors);
        return all;
    }

    private static AnchorDefinition anchor(String key, String displayName, String... aliases) {
        return new AnchorDefinition(key, displayName, List.of(aliases), "STATIC", "APPROVED");
    }

    record FeatureVector(
            String normalized,
            Set<String> anchors,
            Set<String> tokens,
            Map<String, Double> vector
    ) {
    }

    record AnchorDefinition(String key, String displayName, List<String> aliases, String source, String status) {
    }
}
