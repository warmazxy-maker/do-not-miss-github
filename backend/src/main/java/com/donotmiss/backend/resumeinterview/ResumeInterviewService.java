package com.donotmiss.backend.resumeinterview;

import com.donotmiss.backend.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class ResumeInterviewService {
    private static final int MIN_RESUME_TEXT_LENGTH = 30;
    private static final int MAX_PREVIEW_LENGTH = 300;

    private final ResumeInterviewSessionRepository sessionRepository;
    private final ResumeSnapshotRepository snapshotRepository;
    private final ResumeInterviewQuestionRepository questionRepository;
    private final ResumeInterviewAnswerRepository answerRepository;
    private final ResumeInterviewEvidenceRepository evidenceRepository;
    private final ResumeInterviewScoreSummaryRepository scoreSummaryRepository;

    public ResumeInterviewService(ResumeInterviewSessionRepository sessionRepository,
                                  ResumeSnapshotRepository snapshotRepository,
                                  ResumeInterviewQuestionRepository questionRepository,
                                  ResumeInterviewAnswerRepository answerRepository,
                                  ResumeInterviewEvidenceRepository evidenceRepository,
                                  ResumeInterviewScoreSummaryRepository scoreSummaryRepository) {
        this.sessionRepository = sessionRepository;
        this.snapshotRepository = snapshotRepository;
        this.questionRepository = questionRepository;
        this.answerRepository = answerRepository;
        this.evidenceRepository = evidenceRepository;
        this.scoreSummaryRepository = scoreSummaryRepository;
    }

    @Transactional
    public ResumeInterviewDtos.ResumeAssessmentDetailResponse submitResumeText(
            String userId,
            ResumeInterviewDtos.SubmitResumeTextRequest request
    ) {
        String resumeText = cleanResumeText(request.resumeText());
        if (resumeText.length() < MIN_RESUME_TEXT_LENGTH) {
            throw ApiException.badRequest("简历文本太短，请至少粘贴一段完整经历");
        }

        ResumeInterviewSessionEntity session = new ResumeInterviewSessionEntity();
        session.setRequestId(UUID.randomUUID().toString());
        session.setUserId(normalizeUserId(userId));
        session.setTargetRole(normalizeTargetRole(request.targetRole()));
        session.setStatus(ResumeInterviewSessionStatus.CREATED);
        session = sessionRepository.save(session);

        ResumeSnapshotEntity snapshot = new ResumeSnapshotEntity();
        snapshot.setSessionId(session.getId());
        snapshot.setResumeHash(sha256(resumeText));
        snapshot.setRawResumeText(resumeText);
        snapshot.setParsedResumeJson("{}");
        snapshot = snapshotRepository.save(snapshot);

        session.setActiveResumeSnapshotId(snapshot.getId());
        session = sessionRepository.save(session);

        return detail(session, snapshot, "简历文本已保存，下一步等待 Resume Parser Agent 抽取结构化经历。");
    }

    @Transactional(readOnly = true)
    public List<ResumeInterviewDtos.ResumeAssessmentSessionView> listSessions(String userId) {
        return sessionRepository.findByUserIdOrderByCreatedAtDesc(normalizeUserId(userId))
                .stream()
                .map(this::sessionView)
                .toList();
    }

    @Transactional(readOnly = true)
    public ResumeInterviewDtos.ResumeAssessmentDetailResponse getDetail(String userId, Long sessionId) {
        ResumeInterviewSessionEntity session = sessionRepository.findByIdAndUserId(sessionId, normalizeUserId(userId))
                .orElseThrow(() -> ApiException.notFound("简历面试评估会话不存在"));
        ResumeSnapshotEntity snapshot = activeSnapshot(session);
        return detail(session, snapshot, nextStepFor(session));
    }

    private ResumeInterviewDtos.ResumeAssessmentDetailResponse detail(ResumeInterviewSessionEntity session,
                                                                      ResumeSnapshotEntity snapshot,
                                                                      String nextStep) {
        return new ResumeInterviewDtos.ResumeAssessmentDetailResponse(
                sessionView(session),
                snapshotView(snapshot),
                questionRepository.findBySessionIdOrderBySequenceNoAsc(session.getId())
                        .stream()
                        .map(this::questionView)
                        .toList(),
                answerRepository.findBySessionIdOrderByCreatedAtAsc(session.getId())
                        .stream()
                        .map(this::answerView)
                        .toList(),
                evidenceRepository.findBySessionIdOrderByCreatedAtAsc(session.getId())
                        .stream()
                        .map(this::evidenceView)
                        .toList(),
                scoreSummaryRepository.findBySessionId(session.getId())
                        .map(this::scoreSummaryView)
                        .orElse(null),
                nextStep
        );
    }

    private ResumeSnapshotEntity activeSnapshot(ResumeInterviewSessionEntity session) {
        Long snapshotId = session.getActiveResumeSnapshotId();
        if (snapshotId == null) {
            return null;
        }
        return snapshotRepository.findById(snapshotId)
                .filter(snapshot -> session.getId().equals(snapshot.getSessionId()))
                .orElse(null);
    }

    private ResumeInterviewDtos.ResumeAssessmentSessionView sessionView(ResumeInterviewSessionEntity session) {
        return new ResumeInterviewDtos.ResumeAssessmentSessionView(
                session.getId(),
                session.getRequestId(),
                session.getUserId(),
                session.getTargetRole(),
                session.getStatus(),
                session.getActiveResumeSnapshotId(),
                session.getFinalScoreSummaryId(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                session.getCompletedAt()
        );
    }

    private ResumeInterviewDtos.ResumeSnapshotView snapshotView(ResumeSnapshotEntity snapshot) {
        if (snapshot == null) {
            return null;
        }
        String rawText = snapshot.getRawResumeText() == null ? "" : snapshot.getRawResumeText();
        return new ResumeInterviewDtos.ResumeSnapshotView(
                snapshot.getId(),
                snapshot.getResumeHash(),
                rawText.length(),
                preview(rawText),
                snapshot.getParsedResumeJson(),
                snapshot.getParserPromptVersion(),
                snapshot.getParserModelName(),
                snapshot.getParserTraceRunId(),
                snapshot.getCreatedAt()
        );
    }

    private ResumeInterviewDtos.InterviewQuestionView questionView(ResumeInterviewQuestionEntity question) {
        return new ResumeInterviewDtos.InterviewQuestionView(
                question.getId(),
                question.getSequenceNo(),
                question.getStatus(),
                question.getTargetDimension(),
                question.getNormalizedDimension(),
                question.getQuestionText(),
                question.getEvidenceRefsJson(),
                question.getGenerationReason(),
                question.getAgentTraceRunId(),
                question.getCreatedAt(),
                question.getAnsweredAt()
        );
    }

    private ResumeInterviewDtos.InterviewAnswerView answerView(ResumeInterviewAnswerEntity answer) {
        return new ResumeInterviewDtos.InterviewAnswerView(
                answer.getId(),
                answer.getQuestionId(),
                answer.getAnswerHash(),
                preview(answer.getAnswerText()),
                answer.getCreatedAt()
        );
    }

    private ResumeInterviewDtos.ResumeEvidenceCandidateView evidenceView(ResumeInterviewEvidenceEntity evidence) {
        return new ResumeInterviewDtos.ResumeEvidenceCandidateView(
                evidence.getId(),
                evidence.getEvidenceKind(),
                evidence.getSourceType(),
                evidence.getStatus(),
                evidence.getEvidenceHash(),
                evidence.getAbilityEvidenceJson(),
                evidence.getAbilityAssessmentId(),
                evidence.getAbilityScoreResultIdsJson(),
                evidence.getErrorMessage(),
                evidence.getAgentTraceRunId(),
                evidence.getCreatedAt(),
                evidence.getUpdatedAt()
        );
    }

    private ResumeInterviewDtos.ResumeScoreSummaryView scoreSummaryView(ResumeInterviewScoreSummaryEntity summary) {
        return new ResumeInterviewDtos.ResumeScoreSummaryView(
                summary.getId(),
                summary.getStatus(),
                summary.getAbilityScoreResultIdsJson(),
                summary.getScoreSummaryJson(),
                summary.getJudgeTriggeredJson(),
                summary.getScoringRuleVersion(),
                summary.getScoringTraceRunId(),
                summary.getErrorMessage(),
                summary.getCreatedAt(),
                summary.getUpdatedAt(),
                summary.getCompletedAt()
        );
    }

    private String nextStepFor(ResumeInterviewSessionEntity session) {
        return switch (session.getStatus()) {
            case CREATED -> "等待 Resume Parser Agent 抽取结构化经历。";
            case RESUME_PARSED -> "简历已结构化，下一步由 Interviewer Agent 生成追问。";
            case INTERVIEWING -> "正在面试问答中，等待用户回答问题。";
            case EVIDENCE_READY -> "面试证据已准备好，下一步进入统一评分引擎。";
            case SCORED -> "初始能力分已生成，等待完成确认。";
            case COMPLETED -> "简历定段已完成。";
            case FAILED -> "流程失败，需要查看错误信息后重试。";
            case CANCELLED -> "流程已取消。";
        };
    }

    private String cleanResumeText(String resumeText) {
        if (resumeText == null) {
            return "";
        }
        return resumeText
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
    }

    private String normalizeTargetRole(String targetRole) {
        if (targetRole == null || targetRole.isBlank()) {
            return "未指定岗位";
        }
        return targetRole.trim();
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "anonymous" : userId.trim();
    }

    private String preview(String rawText) {
        String compact = rawText.replaceAll("\\s+", " ").trim();
        return compact.length() > MAX_PREVIEW_LENGTH ? compact.substring(0, MAX_PREVIEW_LENGTH) : compact;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", ex);
        }
    }
}
