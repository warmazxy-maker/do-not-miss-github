package com.donotmiss.backend.follow;

import com.donotmiss.backend.organization.OrganizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class FollowService {
    private final FollowRepository followRepository;
    private final OrganizationService organizationService;

    public FollowService(FollowRepository followRepository, OrganizationService organizationService) {
        this.followRepository = followRepository;
        this.organizationService = organizationService;
    }

    @Transactional
    public FollowDtos.FollowResponse follow(String userId, String organizationName) {
        organizationService.ensureOrganization(organizationName);
        return followRepository.findByUserIdAndOrganizationName(userId, organizationName)
                .map(FollowDtos.FollowResponse::from)
                .orElseGet(() -> {
                    FollowEntity follow = new FollowEntity();
                    follow.setUserId(userId);
                    follow.setOrganizationName(organizationName);
                    return FollowDtos.FollowResponse.from(followRepository.save(follow));
                });
    }

    @Transactional
    public void unfollow(String userId, String organizationName) {
        followRepository.findByUserIdAndOrganizationName(userId, organizationName)
                .ifPresent(followRepository::delete);
    }

    @Transactional(readOnly = true)
    public List<FollowDtos.FollowResponse> list(String userId) {
        return followRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(FollowDtos.FollowResponse::from)
                .toList();
    }
}
