package ru.lordfarif.aiagent.dto;

import jakarta.validation.constraints.NotBlank;

public record AgentChatRequest(@NotBlank String message, String sessionId) {}
