package com.donotmiss.backend.schedule;

import com.donotmiss.backend.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {
    private final ScheduleService scheduleService;
    private final CurrentUser currentUser;

    public ScheduleController(ScheduleService scheduleService, CurrentUser currentUser) {
        this.scheduleService = scheduleService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<ScheduleDtos.ScheduleItemResponse> list(@RequestParam(required = false) String month,
                                                        HttpServletRequest request) {
        return scheduleService.list(currentUser.id(request), month);
    }

    @PostMapping
    public ScheduleDtos.ScheduleItemResponse create(@Valid @RequestBody ScheduleDtos.CreateScheduleItemRequest request,
                                                    HttpServletRequest servletRequest) {
        return scheduleService.create(currentUser.id(servletRequest), request);
    }

    @PostMapping("/import-ai-plan")
    public ScheduleDtos.ImportAiPlanResponse importAiPlan(@Valid @RequestBody ScheduleDtos.ImportAiPlanRequest request,
                                                          HttpServletRequest servletRequest) {
        return scheduleService.importAiPlan(currentUser.id(servletRequest), request);
    }

    @PutMapping("/{itemId}")
    public ScheduleDtos.ScheduleItemResponse update(@PathVariable Long itemId,
                                                    @Valid @RequestBody ScheduleDtos.UpdateScheduleItemRequest request,
                                                    HttpServletRequest servletRequest) {
        return scheduleService.update(currentUser.id(servletRequest), itemId, request);
    }

    @DeleteMapping("/{itemId}")
    public void cancel(@PathVariable Long itemId, HttpServletRequest request) {
        scheduleService.cancel(currentUser.id(request), itemId);
    }
}
