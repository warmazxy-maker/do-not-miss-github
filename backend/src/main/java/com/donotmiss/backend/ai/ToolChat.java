package com.donotmiss.backend.ai;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * F0: the message shapes of a chat with native tool calling (OpenAI-compatible {@code tools}).
 * Kept free of agent concepts so the client only translates, and the agent loop owns the logic.
 */
public final class ToolChat {
    private ToolChat() {
    }

    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    /** One message of the conversation sent to the model. */
    public record Message(Role role, String content, List<Call> toolCalls, String toolCallId) {
        public static Message system(String content) {
            return new Message(Role.SYSTEM, content, List.of(), null);
        }

        public static Message user(String content) {
            return new Message(Role.USER, content, List.of(), null);
        }

        public static Message assistant(String content, List<Call> toolCalls) {
            return new Message(Role.ASSISTANT, content, toolCalls == null ? List.of() : toolCalls, null);
        }

        public static Message toolResult(String toolCallId, String content) {
            return new Message(Role.TOOL, content, List.of(), toolCallId);
        }
    }

    /** A tool the model may call: name, description for the model, JSON Schema of the arguments. */
    public record Tool(String name, String description, JsonNode parameters) {
    }

    /** A call the model asked for; {@code argumentsJson} is the raw string the model produced. */
    public record Call(String id, String name, String argumentsJson) {
    }

    /** What the model answered in one round: text, tool calls, or both. */
    public record Turn(String content, List<Call> toolCalls, String finishReason) {
        public boolean wantsTools() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    /** The model could not be reached or answered something unusable; the message is plain Chinese. */
    public static class UnavailableException extends RuntimeException {
        public UnavailableException(String message) {
            super(message);
        }

        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
