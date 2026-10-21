package com.donotmiss.backend.resumeinterview;

import com.donotmiss.backend.abilityscore.AbilityEvidenceSourceType;
import com.donotmiss.backend.abilityscore.EvidenceAssessmentDtos;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class ResumeInterviewDtos {
    public static final String EVIDENCE_SCHEMA_VERSION = "resume-interview-evidence-v1";

    private ResumeInterviewDtos() {
    }

    public record SubmitResumeTextRequest(
            @Size(max = 160) String targetRole,
            @NotBlank @Size(max = 20000) String resumeText
    ) {
    }

    public record SubmitInterviewAnswerRequest(
            @NotBlank @Size(max = 5000) String answerText
    ) {
    }

    public record ResumeAssessmentDetailResponse(
            ResumeAssessmentSessionView session,
            ResumeSnapshotView activeResumeSnapshot,
            List<InterviewQuestionView> questions,
            List<InterviewAnswerView> answers,
            List<ResumeEvidenceCandidateView> evidenceCandidates,
            ResumeScoreSummaryView scoreSummary,
            String nextStep
    ) {
    }

    public record ResumeAssessmentSessionView(
            Long id,
            String requestId,
            String userId,
            String targetRole,
            ResumeInterviewSessionStatus status,
            Long activeResumeSnapshotId,
            Long finalScoreSummaryId,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt
    ) {
    }

    public record ResumeSnapshotView(
            Long id,
            String resumeHash,
            int textLength,
            String rawResumePreview,
            String parsedResumeJson,
            String parserPromptVersion,
            String parserModelName,
            Long parserTraceRunId,
            Instant createdAt
    ) {
    }

    public record InterviewQuestionView(
            Long id,
            int sequenceNo,
            ResumeInterviewQuestionStatus status,
            String targetDimension,
            String normalizedDimension,
            String questionText,
            String evidenceRefsJson,
            String generationReason,
            Long agentTraceRunId,
            Instant createdAt,
            Instant answeredAt
    ) {
    }

    public record InterviewAnswerView(
            Long id,
            Long questionId,
            String answerHash,
            String answerPreview,
            Instant createdAt
    ) {
    }

    public record ResumeEvidenceCandidateView(
            Long id,
            ResumeInterviewEvidenceKind evidenceKind,
            AbilityEvidenceSourceType sourceType,
            ResumeInterviewEvidenceStatus status,
            String evidenceHash,
            String abilityEvidenceJson,
            Long abilityAssessmentId,
            String abilityScoreResultIdsJson,
            String errorMessage,
            Long agentTraceRunId,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record ResumeScoreSummaryView(
            Long id,
            ResumeInterviewScoreStatus status,
            String abilityScoreResultIdsJson,
            String scoreSummaryJson,
            String judgeTriggeredJson,
            String scoringRuleVersion,
            Long scoringTraceRunId,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt
    ) {
    }

    public record ParsedResume(
            List<ResumeSection> education,
            List<ResumeSection> projects,
            List<ResumeSection> internships,
            List<ResumeSection> contests,
            List<ResumeSection> certificates,
            List<String> skills,
            List<String> claimedRoles,
            List<String> weakSignals,
            List<String> parserWarnings
    ) {
    }

    public record ResumeSection(
            String id,
            String title,
            String organization,
            String timeRange,
            String description,
            List<String> evidenceRefs
    ) {
    }

    public record InterviewQuestionDraft(
            int sequenceNo,
            String targetDimension,
            String normalizedDimension,
            String questionText,
            List<String> evidenceRefs,
            String reason
    ) {
    }

    public record InterviewQuestionGenerationModelResponse(
            List<InterviewQuestionDraft> questions,
            List<String> verificationPriorities,
            List<String> warnings
    ) {
    }

    public record UnifiedAbilityEvidence(
            String evidenceSchemaVersion,
            AbilityEvidenceSourceType sourceType,
            ResumeInterviewEvidenceKind evidenceKind,
            Long sessionId,
            Long resumeSnapshotId,
            Long questionId,
            Long answerId,
            String evidenceHash,
            EvidenceAssessmentDtos.ModelResponse assessment
    ) {
        public UnifiedAbilityEvidence {
            evidenceSchemaVersion = evidenceSchemaVersion == null || evidenceSchemaVersion.isBlank()
                    ? EVIDENCE_SCHEMA_VERSION
                    : evidenceSchemaVersion;
            sourceType = sourceType == null ? AbilityEvidenceSourceType.RESUME_INTERVIEW : sourceType;
            evidenceKind = evidenceKind == null ? ResumeInterviewEvidenceKind.RESUME_AND_INTERVIEW : evidenceKind;
        }
    }

    public record StatusTransition(
            ResumeInterviewSessionStatus from,
            ResumeInterviewSessionStatus to,
            boolean allowed,
            String reason
    ) {
        public static StatusTransition check(ResumeInterviewSessionStatus from, ResumeInterviewSessionStatus to) {
            if (from == null || to == null) {
                return new StatusTransition(from, to, false, "状态不能为空");
            }
            return new StatusTransition(
                    from,
                    to,
                    from.canMoveTo(to),
                    from.canMoveTo(to) ? "允许流转" : "不允许从 " + from + " 流转到 " + to
            );
        }
    }
}
