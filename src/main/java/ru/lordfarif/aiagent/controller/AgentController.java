package ru.lordfarif.aiagent.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import ru.lordfarif.aiagent.dto.AgentChatRequest;
import ru.lordfarif.aiagent.dto.AgentChatResponse;
import ru.lordfarif.aiagent.service.AgentService;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {
    private final AgentService service;

    public AgentController(AgentService service) { this.service = service; }

    @PostMapping("/chat")
    public AgentChatResponse chat(@Valid @RequestBody AgentChatRequest request) {
        return service.chat(request.message(), request.sessionId());
    }
}
