package com.donotmiss.backend.agentkit.actions;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.access.RequirePermission;
import com.donotmiss.backend.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The buttons on a confirmation card (F2). Only these clicks change data: a "好的" typed in the
 * chat never confirms anything (F2 decision: only the button counts). A card belongs to one
 * student; another student's id is simply "not found".
 */
@RestController
@RequestMapping("/api/agent-actions")
@RequirePermission(Permission.STUDENT_FEATURES)
public class AgentActionController {
    private final PendingActionService actions;
    private final CurrentUser currentUser;

    public AgentActionController(PendingActionService actions, CurrentUser currentUser) {
        this.actions = actions;
        this.currentUser = currentUser;
    }

    @PostMapping("/{id}/confirm")
    public PendingActionService.Outcome confirm(@PathVariable Long id, HttpServletRequest request) {
        return actions.confirm(currentUser.id(request), id);
    }

    @PostMapping("/{id}/cancel")
    public PendingActionService.Outcome cancel(@PathVariable Long id, HttpServletRequest request) {
        return actions.cancel(currentUser.id(request), id);
    }

    @PostMapping("/{id}/undo")
    public PendingActionService.Outcome undo(@PathVariable Long id, HttpServletRequest request) {
        return actions.undo(currentUser.id(request), id);
    }
}
