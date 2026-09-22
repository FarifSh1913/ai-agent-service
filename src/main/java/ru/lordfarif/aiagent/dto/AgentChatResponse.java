package ru.lordfarif.aiagent.dto;

import java.util.List;

public record AgentChatResponse(String sessionId, String response, List<AgentImage> images) {
    public AgentChatResponse {
        images = List.copyOf(images);
    }

    public AgentChatResponse(String sessionId, String response) {
        this(sessionId, response, List.of());
    }
}
