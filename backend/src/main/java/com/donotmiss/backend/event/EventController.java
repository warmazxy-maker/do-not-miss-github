package com.donotmiss.backend.event;

import com.donotmiss.backend.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/events")
public class EventController {
    private final EventService eventService;
    private final CurrentUser currentUser;

    public EventController(EventService eventService, CurrentUser currentUser) {
        this.eventService = eventService;
        this.currentUser = currentUser;
    }

    /**
     * 社会端发布事件。
     */
    @PostMapping
    public EventDtos.EventResponse create(@Valid @RequestBody EventDtos.CreateEventRequest request,
                                          HttpServletRequest servletRequest) {
        return eventService.create(request, currentUser.id(servletRequest));
    }

    /**
     * 学生端普通搜索：关键词、分类、地点、收益类型都在这里处理。
     */
    @GetMapping
    public List<EventDtos.EventResponse> search(@RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String category,
                                                @RequestParam(required = false) String benefitType,
                                                @RequestParam(required = false) String location) {
        return eventService.search(new EventDtos.EventSearchRequest(keyword, category, benefitType, location));
    }

    @GetMapping("/mine")
    public List<EventDtos.EventResponse> mine(HttpServletRequest request) {
        return eventService.mine(currentUser.id(request));
    }

    @DeleteMapping("/{eventId}")
    public void delete(@PathVariable Long eventId, HttpServletRequest request) {
        eventService.delete(eventId, currentUser.id(request));
    }
}
