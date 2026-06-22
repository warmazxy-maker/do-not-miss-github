package com.donotmiss.backend.follow;

import com.donotmiss.backend.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/follows")
public class FollowController {
    private final FollowService followService;
    private final CurrentUser currentUser;

    public FollowController(FollowService followService, CurrentUser currentUser) {
        this.followService = followService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<FollowDtos.FollowResponse> list(HttpServletRequest request) {
        return followService.list(currentUser.id(request));
    }

    @PostMapping
    public FollowDtos.FollowResponse follow(@Valid @RequestBody FollowDtos.FollowRequest request,
                                            HttpServletRequest servletRequest) {
        return followService.follow(currentUser.id(servletRequest), request.organizationName());
    }

    @DeleteMapping("/{organizationName}")
    public void unfollow(@PathVariable String organizationName, HttpServletRequest request) {
        followService.unfollow(currentUser.id(request), organizationName);
    }
}
