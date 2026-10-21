export interface CategoryCount {
  category: string;
  count: number;
}

export interface AchievementSummary {
  completedCount: number;
  categoryCount: number;
  categoryCounts: CategoryCount[];
}

export type CapabilityBucketType = "KNOWLEDGE" | "FUNCTION";

export interface CapabilityBucketSummary {
  bucketId: number;
  bucketType: CapabilityBucketType;
  displayName: string;
  description: string | null;
  domainName: string | null;
  groupName: string | null;
  experienceScore: number;
  abilityScore: number;
  abilityUncertainty: number;
  rankName: string;
  evidenceCount: number;
  updatedAt: string | null;
  /** The score already earns a higher rank that is waiting for a promotion review. */
  promotionPending?: boolean;
  pendingRank?: string | null;
}

export interface CapabilityV2Profile {
  knowledgeBuckets: CapabilityBucketSummary[];
  functionBuckets: CapabilityBucketSummary[];
  totalEvidenceCount: number;
  lastUpdatedAt: string | null;
}

export type CapabilityEvidenceStatus = "PENDING" | "SCORED" | "REVIEW_REQUIRED" | "REJECTED" | "SUPERSEDED";
export type CapabilityMilestoneType =
  | "FIRST_EVIDENCE"
  | "RANK_UP"
  | "PROMOTION_PENDING_REVIEW"
  | "REVIEW_PASSED"
  | "LARGEST_GAIN";

export interface CapabilityScoreChange {
  scoringRuleVersion: string;
  oldExperienceScore: number | null;
  experienceGain: number | null;
  newExperienceScore: number | null;
  oldAbilityScore: number | null;
  newAbilityScore: number | null;
  oldAbilityUncertainty: number | null;
  newAbilityUncertainty: number | null;
}

export interface CapabilityScoreFactors {
  sourceType: string | null;
  sourceCredibilityPrior: number | null;
  activityDifficulty: number | null;
  completionQuality: number | null;
  personalContribution: number | null;
  originalRelevance: number | null;
  adjustedRelevance: number | null;
  dimensionBudgetMultiplier: number | null;
  evidenceQuality: number | null;
  assessmentConfidence: number | null;
  novelty: number | null;
}

export interface CapabilityTimelineItem {
  evidenceLinkId: number;
  sourceKind: string;
  sourceId: string;
  achievementRecordId: number | null;
  title: string | null;
  summary: string | null;
  did: string | null;
  learned: string | null;
  role: "PRIMARY" | "SECONDARY" | "NONE";
  relevance: number;
  relevanceConfidence: number;
  claimedOutcome: string | null;
  evidenceFindings: string[];
  status: CapabilityEvidenceStatus;
  judgeFlags: string[];
  riskFlags: string[];
  score: CapabilityScoreChange | null;
  factors: CapabilityScoreFactors | null;
  occurredAt: string;
  scoredAt: string;
  /** The capability pipeline run that produced this evidence; quote it when reporting a bad case. */
  agentRunId: number | null;
}

export interface CapabilityMilestone {
  type: CapabilityMilestoneType;
  fromRank: string | null;
  toRank: string | null;
  experienceGain: number | null;
  evidenceLinkId: number | null;
  occurredAt: string;
}

export interface CapabilityPromotionReview {
  id: number;
  status: "IN_PROGRESS" | "COMPLETED";
  decision: "PENDING" | "PASS" | "FAIL" | "MANUAL_REVIEW";
  fromRank: string;
  targetRank: string;
  appliedRank: string | null;
  triggerEvidenceLinkId: number | null;
  questions: JudgeQuestion[];
  answers: JudgeAnswer[];
  rubric: JudgeRubric | null;
  modelEvaluated: boolean;
  createdAt: string;
  completedAt: string | null;
}

export interface CapabilityPromotion {
  pending: boolean;
  currentRank: string;
  targetRank: string;
  triggerEvidenceLinkId: number | null;
  openReview: CapabilityPromotionReview | null;
  canStartReview: boolean;
  retryAvailableAt: string | null;
  history: CapabilityPromotionReview[];
}

export interface CapabilityBucketDetail extends CapabilityBucketSummary {
  promotion: CapabilityPromotion;
  consistency: {
    explainedExperience: number;
    stateExperience: number;
    experienceReconciled: boolean;
    replayedRank: string;
    rankReconciled: boolean;
  };
  timeline: CapabilityTimelineItem[];
  milestones: CapabilityMilestone[];
}

export interface AchievementRecord {
  id: number;
  sourceType: "EVENT" | "CHALLENGE";
  sourceId: number;
  eventId: number;
  eventTitle: string;
  organizationName: string;
  category: string;
  eventStartTime: string;
  location: string;
  content: string;
  benefitType: string;
  skill: string | null;
  moneyAmount: number | null;
  completedAt: string;
  did: string | null;
  learned: string | null;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface GrowthEvidence {
  id: number;
  tagId: number;
  recordId: number;
  sourceType: string;
  sourceId: number;
  title: string;
  summary: string;
  did: string | null;
  learned: string | null;
  scoreDelta: number;
  milestone: boolean;
  milestoneReason: string | null;
  occurredAt: string;
}

export interface AbilityEvidenceTimeline {
  abilityStateId: number;
  dimension: string;
  achievementRecordIds: number[];
  evidences: GrowthEvidence[];
  scoredRecordCount: number;
  evidenceCount: number;
  status: "READY" | "NO_SCORE_RECORD";
}

export interface AbilityState {
  id: number;
  dimension: string;
  normalizedDimension: string;
  experienceScore: number;
  abilityScore: number;
  abilityUncertainty: number;
  rank: string;
}

export interface AbilityClusterMember {
  abilityStateId: number;
  dimension: string;
  normalizedDimension: string;
  experienceScore: number;
  abilityScore: number;
  abilityUncertainty: number;
  rank: string;
  similarityToCluster: number;
}

export interface AbilityCluster {
  clusterKey: string;
  name: string;
  abilityScore: number;
  experienceScore: number;
  abilityUncertainty: number;
  rank: string;
  memberCount: number;
  algorithmVersion: string;
  members: AbilityClusterMember[];
}

export interface AbilityScoreResult {
  id: number;
  achievementRecordId: number;
  dimension: string;
  status: "PROVISIONAL" | "VERIFIED" | "REVIEW_REQUIRED" | "SUPERSEDED";
  verifiedExperienceGain: number;
  oldAbilityScore: number;
  newAbilityScore: number;
  newAbilityUncertainty: number;
  scoringRuleVersion: string;
  supersedesResultId: number | null;
  createdAt: string;
}

export interface JudgeQuestion {
  id: string;
  prompt: string;
  focus: string;
  maxScore: number;
}

export interface JudgeAnswer {
  questionId: string;
  answer: string;
}

export interface JudgeRubricItem {
  questionId: string;
  score: number;
  maxScore: number;
  feedback: string;
  evidence: string[];
}

export interface JudgeRubric {
  totalScore: number;
  earnedScore: number;
  maxScore: number;
  overallFeedback: string;
  items: JudgeRubricItem[];
}

export interface JudgeAssessment {
  id: number;
  requestId: string;
  status: "PENDING" | "IN_PROGRESS" | "COMPLETED" | "FAILED";
  decision: "PENDING" | "PASS" | "FAIL" | "MANUAL_REVIEW";
  scoreResultId: number;
  abilityStateId: number;
  dimension: string;
  normalizedDimension: string;
  triggerReasons: string[];
  questions: JudgeQuestion[];
  answers: JudgeAnswer[];
  rubric: JudgeRubric | null;
  abilityScoreAtTrigger: number;
  confidenceBefore: number;
  confidenceDelta: number;
  confidenceAfter: number | null;
  currentRank: string;
  proposedRank: string;
  reviewReason: string;
  startedAt: string | null;
  completedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AbilityAppeal {
  id: number;
  requestId: string;
  status: string;
  scoreResultId: number;
  reason: string;
  evidenceNote: string;
  resolution: string | null;
  createdAt: string;
}
