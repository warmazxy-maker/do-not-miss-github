import { api } from "@/api/client";
import type {
  AbilityAppeal,
  AbilityCluster,
  AbilityEvidenceTimeline,
  AbilityScoreResult,
  AbilityState,
  AchievementRecord,
  AchievementSummary,
  CapabilityBucketDetail,
  CapabilityPromotionReview,
  CapabilityV2Profile,
  JudgeAnswer,
  JudgeAssessment,
  PageResponse
} from "@/types/achievements";

export const achievementsApi = {
  summary() {
    return api.get<AchievementSummary>("/api/achievements/summary");
  },
  capabilityProfile() {
    return api.get<CapabilityV2Profile>("/api/capability-v2/profile");
  },
  capabilityBucketDetail(bucketId: number) {
    return api.get<CapabilityBucketDetail>(`/api/capability-v2/buckets/${bucketId}`);
  },
  startPromotionReview(bucketId: number) {
    return api.post<CapabilityPromotionReview>(`/api/capability-v2/buckets/${bucketId}/promotion-review`);
  },
  submitPromotionReview(reviewId: number, answers: JudgeAnswer[]) {
    return api.post<CapabilityPromotionReview>(`/api/capability-v2/promotion-reviews/${reviewId}/submit`, {
      answers
    });
  },
  history(page = 0, size = 5) {
    return api.get<PageResponse<AchievementRecord>>(
      `/api/achievements/history/page?page=${page}&size=${size}`
    );
  },
  historyAll() {
    return api.get<AchievementRecord[]>("/api/achievements/history");
  },
  updateReflection(recordId: number, did: string, learned: string) {
    return api.put<AchievementRecord>(`/api/achievements/history/${recordId}/reflection`, {
      did,
      learned
    });
  },
  abilityStates() {
    return api.get<AbilityState[]>("/api/ability-scoring/states");
  },
  abilityClusters() {
    return api.get<AbilityCluster[]>("/api/ability-scoring/clusters");
  },
  abilityEvidence(stateId: number) {
    return api.get<AbilityEvidenceTimeline>(`/api/ability-scoring/states/${stateId}/evidences`);
  },
  abilityResults() {
    return api.get<AbilityScoreResult[]>("/api/ability-scoring/results");
  },
  appeals() {
    return api.get<AbilityAppeal[]>("/api/ability-scoring/appeals");
  },
  appeal(resultId: number, reason: string) {
    return api.post<AbilityAppeal>(`/api/ability-scoring/results/${resultId}/appeals`, {
      reason,
      evidenceNote: reason
    });
  },
  judges() {
    return api.get<JudgeAssessment[]>("/api/ability-judges");
  },
  judge(judgeId: number) {
    return api.get<JudgeAssessment>(`/api/ability-judges/${judgeId}`);
  },
  startJudge(judgeId: number) {
    return api.post<JudgeAssessment>(`/api/ability-judges/${judgeId}/start`);
  },
  submitJudge(judgeId: number, answers: JudgeAnswer[]) {
    return api.post<JudgeAssessment>(`/api/ability-judges/${judgeId}/submit`, { answers });
  }
};
