package com.donotmiss.backend.agentlog;

import com.donotmiss.backend.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AgentBadCaseService {
    private final AgentBadCaseRepository badCaseRepository;
    private final AgentRunRepository agentRunRepository;
    private final AgentBadCaseIntakeAgent intakeAgent;

    public AgentBadCaseService(AgentBadCaseRepository badCaseRepository,
                               AgentRunRepository agentRunRepository,
                               AgentBadCaseIntakeAgent intakeAgent) {
        this.badCaseRepository = badCaseRepository;
        this.agentRunRepository = agentRunRepository;
        this.intakeAgent = intakeAgent;
    }

    @Transactional
    public AgentBadCaseDtos.Response create(String userId, AgentBadCaseDtos.CreateRequest request) {
        if (request == null) {
            throw ApiException.badRequest("反馈内容不能为空");
        }
        String message = required(request.userMessage(), "反馈内容不能为空", 2000);

        AgentRunEntity run = null;
        if (request.agentRunId() != null) {
            run = agentRunRepository.findByIdAndUserId(request.agentRunId(), userId)
                    .orElseThrow(() -> ApiException.notFound("Agent Run 不存在，或不属于当前用户"));
        }

        AgentBadCaseEntity entity = new AgentBadCaseEntity();
        entity.setUserId(userId);
        entity.setAgentRunId(run == null ? null : run.getId());
        entity.setRunType(run == null ? null : run.getRunType());
        entity.setSourceType(request.sourceType() == null ? AgentBadCaseSourceType.USER_FEEDBACK : request.sourceType());
        entity.setIssueType(request.issueType() == null ? AgentBadCaseIssueType.UNKNOWN : request.issueType());
        entity.setSeverity(request.severity() == null ? AgentBadCaseSeverity.MEDIUM : request.severity());
        entity.setStatus(AgentBadCaseStatus.OPEN);
        entity.setPageUrl(compact(request.pageUrl(), 500));
        entity.setModuleKey(compact(request.moduleKey(), 80));
        entity.setUserMessage(message);
        entity.setExpectedBehavior(compact(request.expectedBehavior(), 2000));
        entity.setActualBehavior(compact(request.actualBehavior(), 2000));

        AgentBadCaseEntity saved = badCaseRepository.saveAndFlush(entity);
        intakeAgent.triage(userId, saved, run);
        return AgentBadCaseDtos.Response.from(saved);
    }

    @Transactional(readOnly = true)
    public List<AgentBadCaseDtos.Response> recent(String userId) {
        return badCaseRepository.findTop30ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(AgentBadCaseDtos.Response::from)
                .toList();
    }

    private String required(String value, String message, int maxLength) {
        String text = compact(value, maxLength);
        if (text.isBlank()) {
            throw ApiException.badRequest(message);
        }
        return text;
    }

    private String compact(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }
}
