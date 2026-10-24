package com.donotmiss.backend.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the next version of the evaluation set from approved feedback cases. Pure: it takes the
 * current files and returns new file contents; a developer reviews and commits them. The running
 * application never rewrites its own evaluation set, so a version number always means the same
 * cases and labels.
 */
final class EvalSetExport {
    private static final Pattern TRAILING_NUMBER = Pattern.compile("^(.*?)(\\d+)$");

    private EvalSetExport() {
    }

    /** One approved candidate. No user id: the case is committed to a public repository. */
    record ApprovedCase(Long candidateId, Long eventId, String need, String reason, String reviewerNote) {
    }

    record Result(
            String evalSetVersion,
            String corpusVersion,
            boolean corpusChanged,
            List<String> newCaseIds,
            List<Long> addedEventIds,
            List<String> warnings,
            ObjectNode evalSet,
            ObjectNode corpus
    ) {
    }

    static String caseId(Long candidateId) {
        return "feedback-" + candidateId;
    }

    /**
     * @param eventSnapshots fixture-shaped JSON for events that may need to join the corpus,
     *                       keyed by event id; only those not already frozen are added
     */
    static Result build(ObjectMapper objectMapper,
                        JsonNode currentSet,
                        JsonNode currentCorpus,
                        List<ApprovedCase> approved,
                        Map<Long, JsonNode> eventSnapshots,
                        LocalDate today) {
        Set<String> existingCaseIds = new HashSet<>();
        currentSet.path("cases").forEach(item -> existingCaseIds.add(item.path("id").asText()));
        Set<Long> frozenEventIds = new HashSet<>();
        currentCorpus.path("events").forEach(item -> frozenEventIds.add(item.path("id").asLong()));
        OffsetDateTime asOf = OffsetDateTime.parse(currentSet.path("asOf").asText());

        List<ApprovedCase> fresh = approved.stream().filter(item -> !existingCaseIds.contains(caseId(item.candidateId()))).toList();
        List<String> warnings = new ArrayList<>();
        Map<Long, JsonNode> toFreeze = new LinkedHashMap<>();
        List<ApprovedCase> usable = new ArrayList<>();
        for (ApprovedCase item : fresh) {
            if (frozenEventIds.contains(item.eventId()) || toFreeze.containsKey(item.eventId())) {
                usable.add(item);
                continue;
            }
            JsonNode snapshot = eventSnapshots.get(item.eventId());
            if (snapshot == null) {
                warnings.add("候选 #" + item.candidateId() + " 的活动 #" + item.eventId() + " 已不存在，跳过");
                continue;
            }
            LocalDateTime end = LocalDateTime.parse(snapshot.path("endTime").asText());
            if (end.isBefore(asOf.toLocalDateTime())) {
                warnings.add("候选 #" + item.candidateId() + " 的活动 #" + item.eventId()
                        + " 在评测时钟 asOf 之前已结束，评测时会被当作过期，跳过");
                continue;
            }
            toFreeze.put(item.eventId(), snapshot);
            usable.add(item);
        }

        String currentSetVersion = currentSet.path("version").asText();
        String currentCorpusVersion = currentSet.path("corpusVersion").asText();
        if (usable.isEmpty()) {
            return new Result(currentSetVersion, currentCorpusVersion, false, List.of(), List.of(), warnings,
                    currentSet.deepCopy(), currentCorpus.deepCopy());
        }

        boolean corpusChanged = !toFreeze.isEmpty();
        String nextSetVersion = bump(currentSetVersion);
        String nextCorpusVersion = corpusChanged ? bump(currentCorpusVersion) : currentCorpusVersion;

        ObjectNode corpus = currentCorpus.deepCopy();
        if (corpusChanged) {
            ArrayNode events = (ArrayNode) corpus.path("events");
            toFreeze.values().forEach(events::add);
            corpus.put("corpusVersion", nextCorpusVersion);
            corpus.put("frozenAt", today.toString());
            corpus.put("eventCount", events.size());
            corpus.put("source", currentCorpus.path("source").asText()
                    + " " + today + "：追加 " + toFreeze.size() + " 个来自学生反馈的活动 " + toFreeze.keySet() + "。");
        }

        ObjectNode set = currentSet.deepCopy();
        set.put("version", nextSetVersion);
        set.put("corpusVersion", nextCorpusVersion);
        ArrayNode cases = (ArrayNode) set.path("cases");
        List<String> newIds = new ArrayList<>();
        for (ApprovedCase item : usable) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", caseId(item.candidateId()));
            node.put("need", item.need());
            node.putArray("judgments");
            node.putArray("rejectedEventIds").add(item.eventId());
            node.put("topK", 5);
            node.put("note", note(item));
            node.put("source", "feedback:candidate-" + item.candidateId());
            cases.add(node);
            newIds.add(caseId(item.candidateId()));
        }
        ObjectNode change = objectMapper.createObjectNode();
        change.put("version", nextSetVersion);
        change.put("date", today.toString());
        change.put("note", "新增 " + newIds.size() + " 条来自学生“不相关”反馈的用例 " + newIds
                + (corpusChanged ? "；语料升级为 " + nextCorpusVersion : "") + "。");
        ((ArrayNode) set.withArray("changelog")).add(change);

        return new Result(nextSetVersion, nextCorpusVersion, corpusChanged, newIds, List.copyOf(toFreeze.keySet()),
                warnings, set, corpus);
    }

    static String bump(String version) {
        Matcher matcher = TRAILING_NUMBER.matcher(version);
        if (!matcher.matches()) {
            return version + "-2";
        }
        return matcher.group(1) + (Long.parseLong(matcher.group(2)) + 1);
    }

    private static String note(ApprovedCase item) {
        StringBuilder note = new StringBuilder("学生把这个活动标为不相关");
        if (item.reason() != null && !item.reason().isBlank()) {
            note.append("，原因：").append(item.reason().trim());
        }
        if (item.reviewerNote() != null && !item.reviewerNote().isBlank()) {
            note.append("；审核备注：").append(item.reviewerNote().trim());
        }
        return note.append("。").toString();
    }
}
