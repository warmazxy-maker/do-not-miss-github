package com.donotmiss.backend.challenge;

import com.donotmiss.backend.common.CurrentUser;
import com.donotmiss.backend.common.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/challenges")
public class ChallengeController {
    private final ChallengeService challengeService;
    private final CurrentUser currentUser;

    public ChallengeController(ChallengeService challengeService, CurrentUser currentUser) {
        this.challengeService = challengeService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<ChallengeDtos.ChallengeResponse> list(@RequestParam(required = false) String status,
                                                      HttpServletRequest request) {
        return challengeService.list(currentUser.id(request), status);
    }

    @GetMapping("/page")
    public PageResponse<ChallengeDtos.ChallengeResponse> page(@RequestParam(required = false) String status,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "5") int size,
                                                              HttpServletRequest request) {
        return challengeService.page(currentUser.id(request), status, page, size);
    }

    @PostMapping
    public ChallengeDtos.ChallengeResponse create(@Valid @RequestBody ChallengeDtos.CreateChallengeRequest request,
                                                  HttpServletRequest servletRequest) {
        return challengeService.create(currentUser.id(servletRequest), request);
    }

    @PostMapping("/{challengeId}/complete")
    public ChallengeDtos.ChallengeResponse complete(@PathVariable Long challengeId,
                                                    @Valid @RequestBody ChallengeDtos.CompleteChallengeRequest request,
                                                    HttpServletRequest servletRequest) {
        return challengeService.complete(currentUser.id(servletRequest), challengeId, request);
    }

    @DeleteMapping("/{challengeId}")
    public void cancel(@PathVariable Long challengeId, HttpServletRequest request) {
        challengeService.cancel(currentUser.id(request), challengeId);
    }
}
