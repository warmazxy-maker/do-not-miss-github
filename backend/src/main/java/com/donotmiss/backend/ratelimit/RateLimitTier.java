package com.donotmiss.backend.ratelimit;

import java.time.Duration;
import java.util.List;

/**
 * Every limited endpoint belongs to one tier (D4). The numbers live here and nowhere else, like
 * the transition table of D2: an endpoint only says which tier it is in.
 *
 * <p>Windows are fixed (they reset at the start of each minute / hour / local day) and are checked
 * shortest first, so a request refused by the per-minute window does not use up the daily quota.</p>
 */
public enum RateLimitTier {
    /** Several model calls per request: action plans, guide chat, event recommendations. */
    AI_HEAVY("重 AI 功能", Subject.USER, true, Window.minutes(3), Window.days(30)),
    /**
     * F0: one user turn of a tool-using agent (several model rounds plus tools). Tools that call a
     * model are charged again under their own tier.
     */
    AGENT_TURN("Agent 对话", Subject.USER, true, Window.minutes(5), Window.days(60)),
    /** About one model call per request. */
    AI_LIGHT("AI 功能", Subject.USER, true, Window.minutes(10), Window.days(100)),
    /** An organiser sending an event to the AI review. Charged in the lifecycle, not on an endpoint. */
    EVENT_SUBMIT("提交活动审核", Subject.USER, false, Window.days(20)),
    REGISTER("注册", Subject.IP, false, Window.hours(5)),
    /** All login attempts from one IP; wrong passwords are handled separately by {@link LoginGuard}. */
    LOGIN("登录", Subject.IP, false, Window.minutes(20));

    /** Who is counted: the logged-in account, or the client IP for endpoints used before login. */
    public enum Subject { USER, IP }

    public record Window(String code, Duration length, int limit) {
        static Window minutes(int limit) {
            return new Window("m", Duration.ofMinutes(1), limit);
        }

        static Window hours(int limit) {
            return new Window("h", Duration.ofHours(1), limit);
        }

        static Window days(int limit) {
            return new Window("d", Duration.ofDays(1), limit);
        }
    }

    private final String label;
    private final Subject subject;
    private final boolean concurrencyGated;
    private final List<Window> windows;

    RateLimitTier(String label, Subject subject, boolean concurrencyGated, Window... windows) {
        this.label = label;
        this.subject = subject;
        this.concurrencyGated = concurrencyGated;
        this.windows = List.of(windows);
    }

    public String label() {
        return label;
    }

    public Subject subject() {
        return subject;
    }

    /** AI tiers also share the per-user concurrency gate ({@link RateLimiter#MAX_IN_FLIGHT}). */
    public boolean concurrencyGated() {
        return concurrencyGated;
    }

    public List<Window> windows() {
        return windows;
    }
}
