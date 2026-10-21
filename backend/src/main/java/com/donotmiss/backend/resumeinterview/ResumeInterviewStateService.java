package com.donotmiss.backend.resumeinterview;

import com.donotmiss.backend.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class ResumeInterviewStateService {
    private final ResumeInterviewSessionRepository sessionRepository;

    public ResumeInterviewStateService(ResumeInterviewSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Transactional
    public ResumeInterviewSessionEntity moveTo(Long sessionId, ResumeInterviewSessionStatus nextStatus) {
        ResumeInterviewSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> ApiException.notFound("简历面试评估会话不存在"));
        moveTo(session, nextStatus);
        return sessionRepository.save(session);
    }

    public void moveTo(ResumeInterviewSessionEntity session, ResumeInterviewSessionStatus nextStatus) {
        if (session == null) {
            throw ApiException.badRequest("简历面试评估会话不能为空");
        }
        ResumeInterviewSessionStatus current = session.getStatus();
        ResumeInterviewDtos.StatusTransition transition = ResumeInterviewDtos.StatusTransition.check(current, nextStatus);
        if (!transition.allowed()) {
            throw ApiException.badRequest(transition.reason());
        }
        session.setStatus(nextStatus);
        if (nextStatus == ResumeInterviewSessionStatus.COMPLETED
                || nextStatus == ResumeInterviewSessionStatus.FAILED
                || nextStatus == ResumeInterviewSessionStatus.CANCELLED) {
            session.setCompletedAt(Instant.now());
        }
    }
}
