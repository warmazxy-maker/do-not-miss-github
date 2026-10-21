package com.donotmiss.backend.abilityscore;

import com.donotmiss.backend.common.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Deterministic guards around model-generated questions and model-graded rubrics, shared by
 * the V1 ability Judge and the V2 promotion review. The model proposes; these rules decide
 * what is accepted.
 */
public final class JudgeRubricSupport {
    public static final int QUESTION_COUNT = 3;
    public static final int QUESTION_MAX_SCORE = 10;
    private static final int MIN_ANSWER_LENGTH = 8;

    private JudgeRubricSupport() {
    }

    public static List<JudgeDtos.Question> sanitizeQuestions(List<JudgeDtos.Question> values) {
        if (values == null) {
            return List.of();
        }
        List<JudgeDtos.Question> questions = new ArrayList<>();
        for (JudgeDtos.Question value : values) {
            if (value == null || value.prompt() == null || value.prompt().isBlank()) {
                continue;
            }
            questions.add(new JudgeDtos.Question(
                    "q" + (questions.size() + 1),
                    compact(value.prompt(), 600),
                    compact(value.focus(), 180),
                    QUESTION_MAX_SCORE
            ));
            if (questions.size() >= QUESTION_COUNT) {
                break;
            }
        }
        return List.copyOf(questions);
    }

    public static List<JudgeDtos.Answer> validateAnswers(List<JudgeDtos.Question> questions,
                                                         List<JudgeDtos.Answer> submitted) {
        if (questions.isEmpty()) {
            throw ApiException.badRequest("考核题目尚未生成");
        }
        Map<String, JudgeDtos.Answer> byId = submitted == null ? Map.of() : submitted.stream()
                .filter(answer -> answer != null && answer.questionId() != null)
                .collect(Collectors.toMap(
                        answer -> answer.questionId().trim(),
                        Function.identity(),
                        (left, right) -> right,
                        LinkedHashMap::new
                ));
        Set<String> expectedIds = questions.stream()
                .map(JudgeDtos.Question::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!expectedIds.equals(byId.keySet())) {
            throw ApiException.badRequest("必须完整回答当前考核的全部题目，且不能提交未知题目");
        }
        return questions.stream()
                .map(question -> {
                    JudgeDtos.Answer answer = byId.get(question.id());
                    String text = answer.answer() == null ? "" : answer.answer().trim();
                    if (text.length() < MIN_ANSWER_LENGTH) {
                        throw ApiException.badRequest("答案过短：" + question.id());
                    }
                    return new JudgeDtos.Answer(question.id(), text);
                })
                .toList();
    }

    /** Clamps every per-question score into its range and recomputes the total; the model's own total is ignored. */
    public static JudgeDtos.RubricResult sanitizeRubric(List<JudgeDtos.Question> questions,
                                                        JudgeDtos.EvaluationModelResponse response) {
        Map<String, JudgeDtos.RubricItem> modelItems = response.items() == null ? Map.of() : response.items().stream()
                .filter(item -> item != null && item.questionId() != null)
                .collect(Collectors.toMap(
                        item -> item.questionId().trim(),
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        List<JudgeDtos.RubricItem> items = new ArrayList<>();
        int earned = 0;
        int maximum = 0;
        for (JudgeDtos.Question question : questions) {
            JudgeDtos.RubricItem raw = modelItems.get(question.id());
            int maxScore = Math.min(Math.max(question.maxScore(), 1), 20);
            int score = raw == null || raw.score() == null
                    ? 0
                    : Math.min(Math.max(raw.score(), 0), maxScore);
            earned += score;
            maximum += maxScore;
            items.add(new JudgeDtos.RubricItem(
                    question.id(),
                    score,
                    maxScore,
                    compact(raw == null ? "模型未返回该题判分" : raw.feedback(), 500),
                    raw == null || raw.evidence() == null
                            ? List.of()
                            : raw.evidence().stream()
                            .filter(value -> value != null && !value.isBlank())
                            .map(value -> compact(value, 240))
                            .limit(5)
                            .toList()
            ));
        }
        int total = maximum == 0 ? 0 : (int) Math.round(earned * 100.0 / maximum);
        return new JudgeDtos.RubricResult(
                total,
                earned,
                maximum,
                compact(response.overallFeedback(), 1000),
                List.copyOf(items)
        );
    }

    public static String compact(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
