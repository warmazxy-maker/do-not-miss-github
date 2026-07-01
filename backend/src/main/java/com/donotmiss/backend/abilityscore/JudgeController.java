package com.donotmiss.backend.abilityscore;

import com.donotmiss.backend.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ability-judges")
public class JudgeController {
    private final JudgeAssessmentService judgeService;
    private final CurrentUser currentUser;

    public JudgeController(JudgeAssessmentService judgeService, CurrentUser currentUser) {
        this.judgeService = judgeService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<JudgeDtos.AssessmentResponse> list(HttpServletRequest request) {
        return judgeService.list(currentUser.id(request));
    }

    @GetMapping("/{judgeId}")
    public JudgeDtos.AssessmentResponse get(@PathVariable Long judgeId,
                                            HttpServletRequest request) {
        return judgeService.get(currentUser.id(request), judgeId);
    }

    @PostMapping("/{judgeId}/start")
    public JudgeDtos.AssessmentResponse start(@PathVariable Long judgeId,
                                              HttpServletRequest request) {
        return judgeService.start(currentUser.id(request), judgeId);
    }

    @PostMapping("/{judgeId}/submit")
    public JudgeDtos.AssessmentResponse submit(@PathVariable Long judgeId,
                                               @Valid @RequestBody JudgeDtos.SubmitRequest body,
                                               HttpServletRequest request) {
        return judgeService.submit(currentUser.id(request), judgeId, body);
    }
}
