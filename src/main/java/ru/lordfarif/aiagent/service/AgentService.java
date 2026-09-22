package ru.lordfarif.aiagent.service;

import org.springframework.stereotype.Service;
import ru.lordfarif.aiagent.dto.AgentChatResponse;

@Service
public class AgentService {
    private final OpenAiAgentClient client;

    public AgentService(OpenAiAgentClient client) { this.client = client; }

    public AgentChatResponse chat(String message, String sessionId) { return client.chat(message, sessionId); }
}
