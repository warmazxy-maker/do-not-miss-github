package com.donotmiss.backend.agentkit;

import com.donotmiss.backend.ai.OpenAiCompatibleLlmClient;
import com.donotmiss.backend.ai.ToolChat;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The model as the agent loop sees it. An interface so the loop can be tested with a scripted
 * model; in the application it is the OpenAI-compatible client.
 */
public interface ToolChatModel {
    /** Throws {@link ToolChat.UnavailableException} with a plain reason when the model fails. */
    ToolChat.Turn chat(List<ToolChat.Message> messages, List<ToolChat.Tool> tools);

    @Component
    class FromLlmClient implements ToolChatModel {
        private final OpenAiCompatibleLlmClient client;

        public FromLlmClient(OpenAiCompatibleLlmClient client) {
            this.client = client;
        }

        @Override
        public ToolChat.Turn chat(List<ToolChat.Message> messages, List<ToolChat.Tool> tools) {
            return client.chatWithTools(messages, tools);
        }
    }
}
