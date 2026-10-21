import { api } from "@/api/client";
import type {
  ResumeAssessmentDetailResponse,
  ResumeAssessmentSessionView
} from "@/types/resumeInterview";

export const resumeInterviewsApi = {
  list() {
    return api.get<ResumeAssessmentSessionView[]>("/api/resume-interviews");
  },
  detail(sessionId: number) {
    return api.get<ResumeAssessmentDetailResponse>(`/api/resume-interviews/${sessionId}`);
  },
  submitResumeText(targetRole: string, resumeText: string) {
    return api.post<ResumeAssessmentDetailResponse>("/api/resume-interviews", {
      targetRole,
      resumeText
    });
  },
  parse(sessionId: number) {
    return api.post<ResumeAssessmentDetailResponse>(`/api/resume-interviews/${sessionId}/parse`);
  },
  generateQuestions(sessionId: number) {
    return api.post<ResumeAssessmentDetailResponse>(`/api/resume-interviews/${sessionId}/questions`);
  },
  answerQuestion(sessionId: number, questionId: number, answerText: string) {
    return api.post<ResumeAssessmentDetailResponse>(
      `/api/resume-interviews/${sessionId}/questions/${questionId}/answer`,
      { answerText }
    );
  },
  score(sessionId: number) {
    return api.post<ResumeAssessmentDetailResponse>(`/api/resume-interviews/${sessionId}/score`);
  }
};
