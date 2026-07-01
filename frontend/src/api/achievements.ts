import { api } from "@/api/client";
import type {
  AbilityAppeal,
  AbilityCluster,
  AbilityEvidenceTimeline,
  AbilityScoreResult,
  AbilityState,
  AchievementRecord,
  AchievementSummary,
  GrowthEvidence,
  GrowthTag,
  GrowthTagDetail,
  JudgeAnswer,
  JudgeAssessment,
  PageResponse
} from "@/types/achievements";

export const achievementsApi = {
  summary() {
    return api.get<AchievementSummary>("/api/achievements/summary");
  },
  history(page = 0, size = 5) {
    return api.get<PageResponse<AchievementRecord>>(
      `/api/achievements/history/page?page=${page}&size=${size}`
    );
  },
  updateReflection(recordId: number, did: string, learned: string) {
    return api.put<AchievementRecord>(`/api/achievements/history/${recordId}/reflection`, {
      did,
      learned
    });
  },
  growthTags() {
    return api.get<GrowthTag[]>("/api/achievements/growth-tags");
  },
  growthTagDetail(tagId: number) {
    return api.get<GrowthTagDetail>(`/api/achievements/growth-tags/${tagId}`);
  },
  markMilestone(evidenceId: number, milestone: boolean, reason: string) {
    return api.put<GrowthEvidence>(
      `/api/achievements/growth-tags/evidences/${evidenceId}/milestone`,
      { milestone, reason }
    );
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
