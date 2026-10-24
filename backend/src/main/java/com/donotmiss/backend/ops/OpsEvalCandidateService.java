package com.donotmiss.backend.ops;

import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.event.EventEntity;
import com.donotmiss.backend.event.EventRepository;
import com.donotmiss.backend.eventquality.EventQualityReportEntity;
import com.donotmiss.backend.eventquality.EventQualityReportRepository;
import com.donotmiss.backend.recommendation.EvalCandidateEntity;
import com.donotmiss.backend.recommendation.EvalCandidateRepository;
import com.donotmiss.backend.recommendation.EvalCandidateStatus;
import com.donotmiss.backend.retrieval.eval.RetrievalEvalFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Review queue that turns "not relevant" clicks into evaluation cases. Approval only marks the
 * candidate; the evaluation set changes when a developer commits the exported next version.
 */
@Service
public class OpsEvalCandidateService {
    private static final DateTimeFormatter FIXTURE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final EvalCandidateRepository candidateRepository;
    private final EventRepository eventRepository;
    private final EventQualityReportRepository qualityReportRepository;
    private final RetrievalEvalFixtures fixtures;
    private final ObjectMapper objectMapper;
    private final ZoneId zone;

    public OpsEvalCandidateService(EvalCandidateRepository candidateRepository,
                                   EventRepository eventRepository,
                                   EventQualityReportRepository qualityReportRepository,
                                   RetrievalEvalFixtures fixtures,
                                   ObjectMapper objectMapper,
                                   @Value("${app.mcp.default-timezone:Asia/Tokyo}") String timezone) {
        this.candidateRepository = candidateRepository;
        this.eventRepository = eventRepository;
        this.qualityReportRepository = qualityReportRepository;
        this.fixtures = fixtures;
        this.objectMapper = objectMapper;
        this.zone = ZoneId.of(timezone);
    }

    @Transactional(readOnly = true)
    public List<OpsDtos.EvalCandidateRow> list(String status) {
        EvalCandidateStatus filter = status == null || status.isBlank()
                ? EvalCandidateStatus.PENDING : EvalCandidateStatus.valueOf(status.trim().toUpperCase());
        List<EvalCandidateEntity> candidates = candidateRepository.findTop100ByStatusOrderByCreatedAtDesc(filter);
        Map<Long, String> titles = eventRepository.findAllById(
                        candidates.stream().map(EvalCandidateEntity::getEventId).distinct().toList()).stream()
                .collect(Collectors.toMap(EventEntity::getId, EventEntity::getTitle, (a, b) -> a));
        Set<String> inSet = new HashSet<>();
        fixtures.rawEvalSet().path("cases").forEach(item -> inSet.add(item.path("id").asText()));
        return candidates.stream().map(item -> new OpsDtos.EvalCandidateRow(
                item.getId(), item.getRunId(), item.getUserId(), item.getEventId(), titles.get(item.getEventId()),
                item.getNeedText(), item.getReason(), item.getStatus().name(), item.getReviewerNote(),
                item.getReviewedBy(), item.getReviewedAt(), item.getCreatedAt(),
                inSet.contains(EvalSetExport.caseId(item.getId())))).toList();
    }

    @Transactional
    public void review(Long id, EvalCandidateStatus decision, String needText, String note, String reviewer) {
        if (decision == EvalCandidateStatus.PENDING) {
            throw ApiException.badRequest("只能采纳或驳回");
        }
        EvalCandidateEntity candidate = candidateRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("候选不存在"));
        if (decision == EvalCandidateStatus.APPROVED) {
            String need = needText == null || needText.isBlank() ? candidate.getNeedText() : needText;
            if (need == null || need.isBlank()) {
                throw ApiException.badRequest("采纳前需要填写用户需求");
            }
        }
        candidate.review(decision, needText, note == null ? null : note.trim(), reviewer);
    }

    /** Preview of the next evaluation set version; nothing is written. */
    @Transactional(readOnly = true)
    public OpsDtos.EvalExport export() {
        List<EvalCandidateEntity> approved = candidateRepository.findByStatusOrderByIdAsc(EvalCandidateStatus.APPROVED);
        List<EvalSetExport.ApprovedCase> cases = approved.stream()
                .map(item -> new EvalSetExport.ApprovedCase(item.getId(), item.getEventId(), item.getNeedText(),
                        item.getReason(), item.getReviewerNote()))
                .toList();
        Map<Long, JsonNode> snapshots = new LinkedHashMap<>();
        for (EventEntity event : eventRepository.findAllById(approved.stream().map(EvalCandidateEntity::getEventId).distinct().toList())) {
            snapshots.put(event.getId(), snapshot(event, qualityReportRepository.findByEventId(event.getId()).orElse(null)));
        }
        EvalSetExport.Result result = EvalSetExport.build(objectMapper, fixtures.rawEvalSet(), fixtures.rawCorpus(),
                cases, snapshots, LocalDate.now(zone));
        try {
            return new OpsDtos.EvalExport(
                    result.evalSetVersion(),
                    result.corpusVersion(),
                    result.newCaseIds(),
                    result.addedEventIds(),
                    result.warnings(),
                    "retrieval-eval-set.json",
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result.evalSet()),
                    result.corpusChanged() ? RetrievalEvalFixtures.corpusFileName(result.corpusVersion()) : null,
                    result.corpusChanged() ? objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result.corpus()) : null);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to render the exported evaluation set", ex);
        }
    }

    /** Same shape as the frozen fixture: event fields plus the quality report with JSON arrays. */
    private JsonNode snapshot(EventEntity event, EventQualityReportEntity report) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", event.getId());
        node.put("title", event.getTitle());
        node.put("organizationName", event.getOrganizationName());
        node.put("category", event.getCategory().name());
        node.put("startTime", event.getStartTime().format(FIXTURE_TIME));
        node.put("endTime", event.getEndTime().format(FIXTURE_TIME));
        node.put("location", event.getLocation());
        node.put("content", event.getContent());
        node.put("benefitType", event.getBenefitType().name());
        node.put("skill", event.getSkill());
        if (event.getMoneyAmount() == null) {
            node.putNull("moneyAmount");
        } else {
            node.put("moneyAmount", event.getMoneyAmount());
        }
        if (report == null) {
            node.putNull("qualityReport");
            return node;
        }
        ObjectNode quality = node.putObject("qualityReport");
        quality.put("qualityScore", report.getQualityScore());
        quality.put("qualityLevel", report.getQualityLevel());
        quality.put("difficulty", report.getDifficulty());
        quality.put("summary", report.getSummary());
        quality.set("targetStudentsJson", jsonArray(report.getTargetStudentsJson()));
        quality.set("prerequisitesJson", jsonArray(report.getPrerequisitesJson()));
        quality.set("learningOutcomesJson", jsonArray(report.getLearningOutcomesJson()));
        quality.set("extractedTagsJson", jsonArray(report.getExtractedTagsJson()));
        quality.set("abilityImpactsJson", jsonArray(report.getAbilityImpactsJson()));
        quality.set("riskFlagsJson", jsonArray(report.getRiskFlagsJson()));
        return node;
    }

    private JsonNode jsonArray(String text) {
        try {
            return objectMapper.readTree(text == null || text.isBlank() ? "[]" : text);
        } catch (IOException ex) {
            return objectMapper.createArrayNode();
        }
    }
}
