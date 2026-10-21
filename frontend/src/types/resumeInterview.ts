export type ResumeInterviewSessionStatus =
  | "CREATED"
  | "RESUME_PARSED"
  | "INTERVIEWING"
  | "EVIDENCE_READY"
  | "SCORED"
  | "COMPLETED";

export type ResumeInterviewQuestionStatus =
  | "ASKED"
  | "ANSWERED"
  | "EVALUATED"
  | "SKIPPED";

export type ResumeInterviewEvidenceStatus =
  | "DRAFT"
  | "EVIDENCE_READY"
  | "SCORED"
  | "FAILED";

export type ResumeInterviewScoreStatus =
  | "DRAFT"
  | "SCORING"
  | "SCORED"
  | "FAILED";

export interface ResumeAssessmentSessionView {
  id: number;
  requestId: string;
  userId: string;
  targetRole: string;
  status: ResumeInterviewSessionStatus;
  activeResumeSnapshotId: number | null;
  finalScoreSummaryId: number | null;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export interface ResumeSnapshotView {
  id: number;
  resumeHash: string;
  textLength: number;
  rawResumePreview: string;
  parsedResumeJson: string;
  parserPromptVersion: string | null;
  parserModelName: string | null;
  parserTraceRunId: number | null;
  createdAt: string;
}

export interface InterviewQuestionView {
  id: number;
  sequenceNo: number;
  status: ResumeInterviewQuestionStatus;
  targetDimension: string;
  normalizedDimension: string;
  questionText: string;
  evidenceRefsJson: string;
  generationReason: string;
  agentTraceRunId: number | null;
  createdAt: string;
  answeredAt: string | null;
}

export interface InterviewAnswerView {
  id: number;
  questionId: number;
  answerHash: string;
  answerPreview: string;
  createdAt: string;
}

export interface ResumeEvidenceCandidateView {
  id: number;
  evidenceKind: string;
  sourceType: string;
  status: ResumeInterviewEvidenceStatus;
  evidenceHash: string;
  abilityEvidenceJson: string;
  abilityAssessmentId: number | null;
  abilityScoreResultIdsJson: string;
  errorMessage: string | null;
  agentTraceRunId: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface ResumeScoreSummaryView {
  id: number;
  status: ResumeInterviewScoreStatus;
  abilityScoreResultIdsJson: string;
  scoreSummaryJson: string;
  judgeTriggeredJson: string;
  scoringRuleVersion: string | null;
  scoringTraceRunId: number | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export interface ResumeAssessmentDetailResponse {
  session: ResumeAssessmentSessionView;
  activeResumeSnapshot: ResumeSnapshotView | null;
  questions: InterviewQuestionView[];
  answers: InterviewAnswerView[];
  evidenceCandidates: ResumeEvidenceCandidateView[];
  scoreSummary: ResumeScoreSummaryView | null;
  nextStep: string;
}

export interface ResumeSection {
  id: string;
  title: string;
  organization: string;
  timeRange: string;
  description: string;
  evidenceRefs: string[];
}

export interface ParsedResume {
  education?: ResumeSection[];
  projects?: ResumeSection[];
  internships?: ResumeSection[];
  contests?: ResumeSection[];
  certificates?: ResumeSection[];
  skills?: string[];
  claimedRoles?: string[];
  weakSignals?: string[];
  parserWarnings?: string[];
}

export interface EvidenceDimension {
  dimension?: string;
  normalizedDimension?: string;
  relevance?: number;
  confidence?: number;
  claimedOutcome?: string;
  evidenceRefs?: string[];
}

export interface EvidenceFinding {
  finding?: string;
  evidence?: string;
  quality?: number;
}

export interface EvidenceAssessment {
  normalizedActivityType?: string;
  activityDifficulty?: number;
  completionQuality?: number;
  personalContribution?: number;
  assessmentConfidence?: number;
  dimensions?: EvidenceDimension[];
  evidenceFindings?: EvidenceFinding[];
  riskFlags?: string[];
  judgeRecommendation?: {
    suggestReview?: boolean;
    reasons?: string[];
  };
}

export interface UnifiedAbilityEvidence {
  evidenceSchemaVersion?: string;
  sourceType?: string;
  evidenceKind?: string;
  sessionId?: number;
  resumeSnapshotId?: number;
  questionId?: number;
  answerId?: number;
  evidenceHash?: string;
  assessment?: EvidenceAssessment;
}
