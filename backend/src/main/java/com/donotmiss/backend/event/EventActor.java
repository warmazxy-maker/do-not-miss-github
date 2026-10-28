package com.donotmiss.backend.event;

/** Who triggers a transition. userId is null for SYSTEM and AI. */
public record EventActor(EventActorType type, String userId) {
    public static final EventActor SYSTEM = new EventActor(EventActorType.SYSTEM, null);
    public static final EventActor AI = new EventActor(EventActorType.AI, null);

    public static EventActor organizer(String userId) {
        return new EventActor(EventActorType.ORGANIZER, userId);
    }

    public static EventActor operator(String userId) {
        return new EventActor(EventActorType.OPERATOR, userId);
    }
}
