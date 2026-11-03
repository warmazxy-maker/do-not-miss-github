package com.donotmiss.backend.capabilityv2.eval;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.access.RequirePermission;
import com.donotmiss.backend.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * Developer endpoint for the golden set. Disabled unless {@code app.capability-v2.eval.enabled=true},
 * because LIVE mode spends model quota on every call and is not a student-facing feature.
 * D4: also limited to administrators, so turning the switch on no longer opens it to everyone.
 */
@RestController
@RequestMapping("/api/capability-v2/eval")
public class CapabilityGoldenSetEvalController {
    private final CapabilityGoldenSetEvalService evalService;
    private final boolean enabled;

    public CapabilityGoldenSetEvalController(CapabilityGoldenSetEvalService evalService,
                                             @Value("${app.capability-v2.eval.enabled:false}") boolean enabled) {
        this.evalService = evalService;
        this.enabled = enabled;
    }

    @GetMapping("/golden-set")
    @RequirePermission(Permission.OPS_ACTION)
    public CapabilityGoldenSetEvalService.Report goldenSet(@RequestParam(defaultValue = "deterministic") String mode) {
        if (!enabled) {
            throw ApiException.notFound("评测接口未启用");
        }
        CapabilityGoldenSetEvalService.Mode parsed;
        try {
            parsed = CapabilityGoldenSetEvalService.Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("mode 只能是 deterministic、live 或 replay");
        }
        return evalService.run(parsed);
    }
}
