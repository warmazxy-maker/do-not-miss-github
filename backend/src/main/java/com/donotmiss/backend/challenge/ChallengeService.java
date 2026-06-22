package com.donotmiss.backend.challenge;

import com.donotmiss.backend.achievement.AchievementService;
import com.donotmiss.backend.common.ApiException;
import com.donotmiss.backend.common.PageResponse;
import com.donotmiss.backend.schedule.ScheduleItemType;
import com.donotmiss.backend.schedule.ScheduleService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ChallengeService {
    private final ChallengeRepository challengeRepository;
    private final AchievementService achievementService;
    private final ScheduleService scheduleService;

    public ChallengeService(ChallengeRepository challengeRepository,
                            AchievementService achievementService,
                            ScheduleService scheduleService) {
        this.challengeRepository = challengeRepository;
        this.achievementService = achievementService;
        this.scheduleService = scheduleService;
    }

    @Transactional
    public ChallengeDtos.ChallengeResponse create(String userId, ChallengeDtos.CreateChallengeRequest request) {
        ChallengeEntity challenge = new ChallengeEntity();
        challenge.setUserId(userId);
        challenge.setTitle(request.title().trim());
        challenge.setCategory(request.category().trim());
        challenge.setGoal(request.goal().trim());
        challenge.setDescription(request.description().trim());
        challenge.setStatus(ChallengeStatus.ACTIVE);
        return ChallengeDtos.ChallengeResponse.from(challengeRepository.save(challenge));
    }

    @Transactional(readOnly = true)
    public List<ChallengeDtos.ChallengeResponse> list(String userId, String status) {
        List<ChallengeEntity> challenges;

        if (status == null || status.isBlank()) {
            challenges = challengeRepository.findByUserIdOrderByCreatedAtDesc(userId);
        } else {
            challenges = challengeRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, ChallengeStatus.fromText(status));
        }

        return challenges.stream()
                .map(ChallengeDtos.ChallengeResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<ChallengeDtos.ChallengeResponse> page(String userId, String status, int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50));
        Page<ChallengeEntity> result;

        if (status == null || status.isBlank()) {
            result = challengeRepository.findByUserIdOrderByCreatedAtDesc(userId, pageRequest);
        } else {
            result = challengeRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, ChallengeStatus.fromText(status), pageRequest);
        }

        return PageResponse.from(result, ChallengeDtos.ChallengeResponse::from);
    }

    @Transactional
    public ChallengeDtos.ChallengeResponse complete(String userId, Long challengeId, ChallengeDtos.CompleteChallengeRequest request) {
        ChallengeEntity challenge = challengeRepository.findByIdAndUserId(challengeId, userId)
                .orElseThrow(() -> ApiException.notFound("挑战不存在：" + challengeId));

        if (challenge.getStatus() == ChallengeStatus.CANCELLED) {
            throw ApiException.badRequest("已取消的挑战不能完成");
        }

        challenge.setStatus(ChallengeStatus.COMPLETED);
        challenge.setCompletedAt(Instant.now());
        challenge.setDid(blankToNull(request.did()));
        challenge.setLearned(blankToNull(request.learned()));
        achievementService.createFromChallenge(userId, challenge);
        scheduleService.syncChallengeTitle(userId, challenge);
        return ChallengeDtos.ChallengeResponse.from(challenge);
    }

    @Transactional
    public void cancel(String userId, Long challengeId) {
        ChallengeEntity challenge = challengeRepository.findByIdAndUserId(challengeId, userId)
                .orElseThrow(() -> ApiException.notFound("挑战不存在：" + challengeId));

        if (challenge.getStatus() == ChallengeStatus.COMPLETED) {
            throw ApiException.badRequest("已完成的挑战不能取消");
        }

        challenge.setStatus(ChallengeStatus.CANCELLED);
        scheduleService.cancelSource(userId, ScheduleItemType.CHALLENGE, challenge.getId());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
