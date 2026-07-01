export interface CategoryCount {
  category: string;
  count: number;
}

export interface AchievementSummary {
  completedCount: number;
  categoryCount: number;
  categoryCounts: CategoryCount[];
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

export interface GrowthTag {
  id: number;
  name: string;
  normalizedName: string;
  description: string;
  score: number;
  evidenceCount: number;
  importanceScore: number;
  lastUpdatedAt: string;
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

export interface GrowthTagDetail {
  tag: GrowthTag;
  evidences: GrowthEvidence[];
}

export interface AbilityEvidenceTimeline {
  abilityStateId: number;
  dimension: string;
  achievementRecordIds: number[];
  evidences: GrowthEvidence[];
  scoredRecordCount: number;
  evidenceCount: number;
  status: "READY" | "TAG_EXTRACTION_PENDING" | "NO_SCORE_RECORD";
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
