package com.donotmiss.backend.capabilityv2;

import com.donotmiss.backend.abilityscore.AbilityEvidenceSourceType;
import com.donotmiss.backend.achievement.AchievementRecordEntity;
import com.donotmiss.backend.achievement.AchievementRecordRepository;
import com.donotmiss.backend.achievement.AchievementSourceType;
import com.donotmiss.backend.coach.CoachLogEntity;
import com.donotmiss.backend.coach.CoachLogRepository;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.eventquality.EventQualityReportEntity;
import com.donotmiss.backend.eventquality.EventQualityReportRepository;
import com.donotmiss.backend.resumeinterview.ResumeInterviewAnswerEntity;
import com.donotmiss.backend.resumeinterview.ResumeInterviewAnswerRepository;
import com.donotmiss.backend.resumeinterview.ResumeInterviewEvidenceEntity;
import com.donotmiss.backend.resumeinterview.ResumeInterviewEvidenceRepository;
import com.donotmiss.backend.resumeinterview.ResumeInterviewEvidenceStatus;
import com.donotmiss.backend.resumeinterview.ResumeInterviewQuestionEntity;
import com.donotmiss.backend.resumeinterview.ResumeInterviewQuestionRepository;
import com.donotmiss.backend.resumeinterview.ResumeInterviewSessionEntity;
import com.donotmiss.backend.resumeinterview.ResumeInterviewSessionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapts existing business records into the one evidence contract used by capability V2.
 *
 * <p>It intentionally has no publisher and no score-engine dependency. This lets V2 be
 * introduced behind the existing production path without a completion, coach log, or
 * resume answer unexpectedly changing a student's current score.</p>
 */
@Service
public class CapabilityEvidenceInputService {
    private static final String SCHEMA_VERSION = "capability-evidence-v2";

    private final AchievementRecordRepository achievementRecordRepository;
    private final CoachLogRepository coachLogRepository;
    private final ResumeInterviewEvidenceRepository resumeInterviewEvidenceRepository;
    private final ResumeInterviewSessionRepository resumeInterviewSessionRepository;
    private final ResumeInterviewQuestionRepository resumeInterviewQuestionRepository;
    private final ResumeInterviewAnswerRepository resumeInterviewAnswerRepository;
    private final EventQualityReportRepository eventQualityReportRepository;
    private final ObjectMapper objectMapper;

    public CapabilityEvidenceInputService(
            AchievementRecordRepository achievementRecordRepository,
            CoachLogRepository coachLogRepository,
            ResumeInterviewEvidenceRepository resumeInterviewEvidenceRepository,
            ResumeInterviewSessionRepository resumeInterviewSessionRepository,
            ResumeInterviewQuestionRepository resumeInterviewQuestionRepository,
            ResumeInterviewAnswerRepository resumeInterviewAnswerRepository,
            EventQualityReportRepository eventQualityReportRepository,
            ObjectMapper objectMapper
    ) {
        this.achievementRecordRepository = achievementRecordRepository;
        this.coachLogRepository = coachLogRepository;
        this.resumeInterviewEvidenceRepository = resumeInterviewEvidenceRepository;
        this.resumeInterviewSessionRepository = resumeInterviewSessionRepository;
        this.resumeInterviewQuestionRepository = resumeInterviewQuestionRepository;
        this.resumeInterviewAnswerRepository = resumeInterviewAnswerRepository;
        this.eventQualityReportRepository = eventQualityReportRepository;
        this.objectMapper = objectMapper;
    }

    public CapabilityEvidenceInput fromAchievement(Long recordId, String userId) {
        AchievementRecordEntity record = achievementRecordRepository.findByIdAndUserId(recordId, userId)
                .orElseThrow(() -> ApiException.notFound("成长记录不存在"));
        boolean eventCompletion = record.getSourceType() == AchievementSourceType.EVENT;
        CapabilityEvidenceSourceKind sourceKind = eventCompletion
                ? CapabilityEvidenceSourceKind.ACTIVITY_COMPLETION
                : CapabilityEvidenceSourceKind.CHALLENGE_COMPLETION;
        AbilityEvidenceSourceType sourceType = eventCompletion
                ? AbilityEvidenceSourceType.ORGANIZATION_ACTIVITY_RECORD
                : AbilityEvidenceSourceType.SELF_REPORT;
        String reflection = joinNonBlank(record.getDid(), record.getLearned());
        String qualityReportJson = eventCompletion
                ? eventQualityReportRepository.findByEventId(record.getEventId())
                .map(this::toQualityReportJson)
                .orElse("{}")
                : "{}";
        String payload = toJson(achievementPayload(record));
        List<String> facts = achievementFacts(record);

        return build(
                sourceKind,
                "achievement-record:" + record.getId(),
                String.valueOf(record.getSourceId()),
                record.getUserId(),
                sourceType,
                record.getEventTitle(),
                record.getContent(),
                reflection,
                payload,
                qualityReportJson,
                facts,
                record.getCompletedAt(),
                true,
                "已完成的活动或挑战记录可进入后续分类与评分流程"
        );
    }

    /**
     * Coach logs are valuable context, but a diary alone must not silently become a score.
     * Stage 10 can attach it to matching evidence; only then may a later policy enable scoring.
     */
    public CapabilityEvidenceInput fromCoachLog(Long logId, String userId) {
        CoachLogEntity log = coachLogRepository.findById(logId)
                .filter(item -> userId.equals(item.getUserId()))
                .orElseThrow(() -> ApiException.notFound("教练日志不存在"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("logDate", log.getLogDate() == null ? "" : log.getLogDate().toString());
        payload.put("summary", safe(log.getSummary()));
        payload.put("content", safe(log.getContent()));
        payload.put("tags", safe(log.getTags()));

        return build(
                CapabilityEvidenceSourceKind.COACH_LOG,
                "coach-log:" + log.getId(),
                String.valueOf(log.getId()),
                log.getUserId(),
                AbilityEvidenceSourceType.PRIVATE_PERSONAL_RECORD,
                log.getTitle(),
                log.getSummary(),
                log.getContent(),
                toJson(payload),
                "{}",
                nonBlankFacts("日志日期：" + log.getLogDate(), "标签：" + safe(log.getTags())),
                log.getCreatedAt(),
                false,
                "教练日志仅作为画像和证据解释材料，需关联完成事项或验证后才可计分"
        );
    }

    /**
     * The question is system-generated and goes to {@code description}; the answer is the
     * student's own text and goes to {@code reflection}, which the V2 prompts treat as untrusted.
     * The evidence status is deliberately kept out of the payload so the hash describes content,
     * not the V1 lifecycle (EVIDENCE_READY → SCORED).
     */
    public CapabilityEvidenceInput fromResumeInterviewEvidence(Long evidenceId, String userId) {
        ResumeInterviewEvidenceEntity evidence = resumeInterviewEvidenceRepository.findById(evidenceId)
                .orElseThrow(() -> ApiException.notFound("面试证据不存在"));
        ResumeInterviewSessionEntity session = resumeInterviewSessionRepository.findByIdAndUserId(evidence.getSessionId(), userId)
                .orElseThrow(() -> ApiException.notFound("面试证据不存在"));
        ResumeInterviewQuestionEntity question = evidence.getQuestionId() == null
                ? null
                : resumeInterviewQuestionRepository.findByIdAndSessionId(evidence.getQuestionId(), evidence.getSessionId())
                .orElse(null);
        ResumeInterviewAnswerEntity answer = evidence.getAnswerId() == null
                ? null
                : resumeInterviewAnswerRepository.findById(evidence.getAnswerId())
                .filter(item -> evidence.getSessionId().equals(item.getSessionId()))
                .orElse(null);
        boolean ready = evidence.getStatus() == ResumeInterviewEvidenceStatus.EVIDENCE_READY
                || evidence.getStatus() == ResumeInterviewEvidenceStatus.SCORED;
        boolean answered = answer != null && !safe(answer.getAnswerText()).isBlank();
        String questionText = question == null ? "" : safe(question.getQuestionText());
        String targetDimension = question == null ? "" : safe(question.getTargetDimension());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", evidence.getSessionId());
        payload.put("targetRole", safe(session.getTargetRole()));
        payload.put("evidenceKind", evidence.getEvidenceKind() == null ? "" : evidence.getEvidenceKind().name());
        payload.put("abilityEvidence", safe(evidence.getAbilityEvidenceJson()));
        payload.put("questionId", evidence.getQuestionId());
        payload.put("answerId", evidence.getAnswerId());
        payload.put("targetDimension", targetDimension);

        String eligibilityReason;
        if (!ready) {
            eligibilityReason = "面试证据尚未就绪，暂不进入评分流程";
        } else if (!answered) {
            eligibilityReason = "面试回答缺失，无法据此归因";
        } else {
            eligibilityReason = "面试证据已就绪，可进入后续统一评分流程";
        }

        return build(
                CapabilityEvidenceSourceKind.RESUME_INTERVIEW,
                "resume-interview-evidence:" + evidence.getId(),
                String.valueOf(evidence.getId()),
                session.getUserId(),
                evidence.getSourceType() == null ? AbilityEvidenceSourceType.RESUME_INTERVIEW : evidence.getSourceType(),
                "简历面试：" + safe(session.getTargetRole()),
                questionText.isBlank() ? "来自简历与问答的能力证据候选" : "面试问题：" + questionText,
                answered ? safe(answer.getAnswerText()) : "",
                toJson(payload),
                "{}",
                nonBlankFacts(
                        "目标岗位：" + safe(session.getTargetRole()),
                        "证据类型：" + (evidence.getEvidenceKind() == null ? "" : evidence.getEvidenceKind().name()),
                        targetDimension.isBlank() ? "" : "考察维度：" + targetDimension
                ),
                evidence.getCreatedAt(),
                ready && answered,
                eligibilityReason
        );
    }

    private CapabilityEvidenceInput build(
            CapabilityEvidenceSourceKind sourceKind,
            String evidenceId,
            String sourceId,
            String userId,
            AbilityEvidenceSourceType sourceType,
            String title,
            String description,
            String reflection,
            String payload,
            String qualityReportJson,
            List<String> facts,
            Instant occurredAt,
            boolean scoringEligible,
            String eligibilityReason
    ) {
        String hashMaterial = String.join("\n",
                SCHEMA_VERSION,
                sourceKind.name(),
                safe(userId),
                safe(sourceId),
                safe(title),
                safe(description),
                safe(reflection),
                safe(payload),
                safe(qualityReportJson)
        );
        String evidenceHash = sha256(hashMaterial);
        return new CapabilityEvidenceInput(
                SCHEMA_VERSION,
                evidenceId,
                "capability-v2:" + evidenceId + ":" + evidenceHash,
                sourceKind,
                userId,
                sourceId,
                sourceType.name(),
                sourceType.credibilityPrior(),
                title,
                description,
                reflection,
                payload,
                qualityReportJson,
                facts,
                occurredAt,
                scoringEligible,
                eligibilityReason,
                evidenceHash
        );
    }

    private Map<String, Object> achievementPayload(AchievementRecordEntity record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("achievementRecordId", record.getId());
        payload.put("businessSourceType", record.getSourceType() == null ? "" : record.getSourceType().name());
        payload.put("businessSourceId", record.getSourceId());
        payload.put("eventId", record.getEventId());
        payload.put("organization", safe(record.getOrganizationName()));
        payload.put("category", safe(record.getCategory()));
        payload.put("location", safe(record.getLocation()));
        payload.put("benefitType", record.getBenefitType() == null ? "" : record.getBenefitType().name());
        payload.put("declaredSkill", safe(record.getSkill()));
        payload.put("moneyAmount", record.getMoneyAmount() == null ? "" : record.getMoneyAmount().toPlainString());
        payload.put("eventStartTime", record.getEventStartTime() == null ? "" : record.getEventStartTime().toString());
        payload.put("completedAt", record.getCompletedAt() == null ? "" : record.getCompletedAt().toString());
        payload.put("did", safe(record.getDid()));
        payload.put("learned", safe(record.getLearned()));
        return payload;
    }

    private List<String> achievementFacts(AchievementRecordEntity record) {
        return nonBlankFacts(
                "组织：" + safe(record.getOrganizationName()),
                "类别：" + safe(record.getCategory()),
                "地点：" + safe(record.getLocation()),
                "技能：" + safe(record.getSkill()),
                "做了：" + safe(record.getDid()),
                "学到：" + safe(record.getLearned())
        );
    }

    private String toQualityReportJson(EventQualityReportEntity report) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", report.getEventId());
        payload.put("qualityScore", report.getQualityScore());
        payload.put("qualityLevel", safe(report.getQualityLevel()));
        payload.put("reviewSuggestion", safe(report.getReviewSuggestion()));
        payload.put("difficulty", safe(report.getDifficulty()));
        payload.put("summary", safe(report.getSummary()));
        payload.put("targetStudentsJson", safe(report.getTargetStudentsJson()));
        payload.put("prerequisitesJson", safe(report.getPrerequisitesJson()));
        payload.put("learningOutcomesJson", safe(report.getLearningOutcomesJson()));
        payload.put("extractedTagsJson", safe(report.getExtractedTagsJson()));
        payload.put("abilityImpactsJson", safe(report.getAbilityImpactsJson()));
        payload.put("riskFlagsJson", safe(report.getRiskFlagsJson()));
        payload.put("missingFieldsJson", safe(report.getMissingFieldsJson()));
        payload.put("duplicateEventIdsJson", safe(report.getDuplicateEventIdsJson()));
        payload.put("modelName", safe(report.getModelName()));
        payload.put("confidence", report.getConfidence());
        return toJson(payload);
    }

    private List<String> nonBlankFacts(String... values) {
        List<String> facts = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                facts.add(value.trim());
            }
        }
        return facts;
    }

    private String joinNonBlank(String first, String second) {
        return String.join("\n", nonBlankFacts(first, second));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法序列化能力证据输入", ex);
        }
    }

    private String sha256(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前环境不支持 SHA-256", ex);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
