package com.donotmiss.backend.resumeinterview;

import com.donotmiss.backend.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/resume-interviews")
public class ResumeInterviewController {
    private final ResumeInterviewService resumeInterviewService;
    private final ResumeParserAgentService resumeParserAgentService;
    private final ResumeInterviewerAgentService resumeInterviewerAgentService;
    private final ResumeAnswerEvidenceEvaluatorService resumeAnswerEvidenceEvaluatorService;
    private final ResumeAbilityScoringService resumeAbilityScoringService;
    private final CurrentUser currentUser;

    public ResumeInterviewController(ResumeInterviewService resumeInterviewService,
                                     ResumeParserAgentService resumeParserAgentService,
                                     ResumeInterviewerAgentService resumeInterviewerAgentService,
                                     ResumeAnswerEvidenceEvaluatorService resumeAnswerEvidenceEvaluatorService,
                                     ResumeAbilityScoringService resumeAbilityScoringService,
                                     CurrentUser currentUser) {
        this.resumeInterviewService = resumeInterviewService;
        this.resumeParserAgentService = resumeParserAgentService;
        this.resumeInterviewerAgentService = resumeInterviewerAgentService;
        this.resumeAnswerEvidenceEvaluatorService = resumeAnswerEvidenceEvaluatorService;
        this.resumeAbilityScoringService = resumeAbilityScoringService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public ResumeInterviewDtos.ResumeAssessmentDetailResponse submitResumeText(
            @Valid @RequestBody ResumeInterviewDtos.SubmitResumeTextRequest request,
            HttpServletRequest servletRequest
    ) {
        return resumeInterviewService.submitResumeText(currentUser.id(servletRequest), request);
    }

    @GetMapping
    public List<ResumeInterviewDtos.ResumeAssessmentSessionView> list(HttpServletRequest request) {
        return resumeInterviewService.listSessions(currentUser.id(request));
    }

    @GetMapping("/{sessionId}")
    public ResumeInterviewDtos.ResumeAssessmentDetailResponse detail(@PathVariable Long sessionId,
                                                                     HttpServletRequest request) {
        return resumeInterviewService.getDetail(currentUser.id(request), sessionId);
    }

    @PostMapping("/{sessionId}/parse")
    public ResumeInterviewDtos.ResumeAssessmentDetailResponse parseResume(@PathVariable Long sessionId,
                                                                          HttpServletRequest request) {
        return resumeParserAgentService.parse(currentUser.id(request), sessionId);
    }

    @PostMapping("/{sessionId}/questions")
    public ResumeInterviewDtos.ResumeAssessmentDetailResponse generateQuestions(@PathVariable Long sessionId,
                                                                                HttpServletRequest request) {
        return resumeInterviewerAgentService.generateQuestions(currentUser.id(request), sessionId);
    }

    @PostMapping("/{sessionId}/questions/{questionId}/answer")
    public ResumeInterviewDtos.ResumeAssessmentDetailResponse answerQuestion(
            @PathVariable Long sessionId,
            @PathVariable Long questionId,
            @Valid @RequestBody ResumeInterviewDtos.SubmitInterviewAnswerRequest request,
            HttpServletRequest servletRequest
    ) {
        return resumeAnswerEvidenceEvaluatorService.answerAndEvaluate(
                currentUser.id(servletRequest),
                sessionId,
                questionId,
                request
        );
    }

    @PostMapping("/{sessionId}/score")
    public ResumeInterviewDtos.ResumeAssessmentDetailResponse score(@PathVariable Long sessionId,
                                                                    HttpServletRequest servletRequest) {
        return resumeAbilityScoringService.score(currentUser.id(servletRequest), sessionId);
    }
}
