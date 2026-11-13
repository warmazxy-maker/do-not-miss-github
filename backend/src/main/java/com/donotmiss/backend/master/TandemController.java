package com.donotmiss.backend.master;

import com.donotmiss.backend.access.Permission;
import com.donotmiss.backend.access.RequirePermission;
import com.donotmiss.backend.agentkit.AgentLoop;
import com.donotmiss.backend.agentkit.ToolResultStatus;
import com.donotmiss.backend.common.CurrentUser;
import com.donotmiss.backend.ratelimit.RateLimitTier;
import com.donotmiss.backend.ratelimit.RateLimiter;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tandem endpoints (F1). The chat streams its progress as server-sent events: one "step" event per
 * change of the step list (F1 decision 5: the list grows as Tandem decides what to look up), then
 * a "reply" with the saved answer, or an "error".
 */
@RestController
@RequestMapping("/api/tandem")
@RequirePermission(Permission.STUDENT_FEATURES)
public class TandemController {
    private static final Logger log = LoggerFactory.getLogger(TandemController.class);
    /** Longer than the 90 s turn budget plus saving. */
    private static final long STREAM_TIMEOUT_MILLIS = 120_000;

    private final TandemService tandemService;
    private final CurrentUser currentUser;
    private final RateLimiter rateLimiter;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public TandemController(TandemService tandemService, CurrentUser currentUser, RateLimiter rateLimiter) {
        this.tandemService = tandemService;
        this.currentUser = currentUser;
        this.rateLimiter = rateLimiter;
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }

    /**
     * The rate limit and the AI slot are taken here, before the stream starts, so a refusal is an
     * ordinary 429 response. The slot is held until the turn ends on the worker thread; with the
     * {@code @RateLimited} annotation it would be released as soon as this method returns.
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody TandemDtos.ChatRequest body, HttpServletRequest request,
                           HttpServletResponse response) {
        String userId = currentUser.id(request);
        rateLimiter.consume(RateLimitTier.AGENT_TURN, userId);
        RateLimiter.Permit permit = rateLimiter.enter(userId);
        // Behind nginx, do not buffer the stream.
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        workers.submit(() -> {
            try (permit) {
                TandemDtos.Message reply = tandemService.turn(userId, body.message(), new StreamingListener(emitter));
                send(emitter, "reply", reply);
                emitter.complete();
            } catch (Exception ex) {
                log.warn("Tandem turn failed.", ex);
                send(emitter, "error", Map.of("message", "这次没能完成，系统出了点问题。你稍后再试一次。"));
                emitter.complete();
            }
        });
        return emitter;
    }

    @GetMapping("/messages")
    public TandemDtos.MessagePage messages(@RequestParam(required = false) Long before, HttpServletRequest request) {
        return tandemService.page(currentUser.id(request), before);
    }

    @DeleteMapping("/messages")
    public void clear(HttpServletRequest request) {
        tandemService.clear(currentUser.id(request));
    }

    @GetMapping("/memories")
    public TandemDtos.Memories memories(HttpServletRequest request) {
        return tandemService.memories(currentUser.id(request));
    }

    @DeleteMapping("/memories/{id}")
    public void deleteMemory(@PathVariable Long id, HttpServletRequest request) {
        tandemService.deleteMemory(currentUser.id(request), id);
    }

    private static void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException gone) {
            // The student closed the page; the turn still finishes and is saved.
        }
    }

    /** Turns loop callbacks into step events. Keys are per turn: "t1", "t2"… for tools, "think" for thinking. */
    private static final class StreamingListener implements AgentLoop.Listener {
        private final SseEmitter emitter;
        private final AtomicInteger counter = new AtomicInteger();
        private final Map<String, String> keyOfCall = new HashMap<>();

        StreamingListener(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void onThinking(int round, boolean afterTools) {
            send(emitter, "step", new TandemDtos.Progress("think", "thinking",
                    afterTools ? "整理回答" : "理解你的问题", "", "running", ""));
        }

        @Override
        public synchronized void onToolStart(String callId, String toolName, String title, String detail) {
            String key = "t" + counter.incrementAndGet();
            keyOfCall.put(callId, key);
            send(emitter, "step", new TandemDtos.Progress(key, "tool", title, detail, "running", ""));
        }

        @Override
        public synchronized void onToolDone(String callId, ToolResultStatus status, String summaryForUser) {
            String key = keyOfCall.remove(callId);
            String state = status == ToolResultStatus.OK ? "ok" : status == ToolResultStatus.DENIED ? "denied" : "error";
            send(emitter, "step", new TandemDtos.Progress(key, "tool", null, null, state, summaryForUser));
        }
    }
}
