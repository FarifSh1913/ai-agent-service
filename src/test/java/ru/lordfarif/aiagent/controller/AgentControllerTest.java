package ru.lordfarif.aiagent.controller;

import org.junit.jupiter.api.Test;
import ru.lordfarif.aiagent.dto.AgentChatResponse;
import ru.lordfarif.aiagent.dto.AgentImage;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.HttpStatus;
import ru.lordfarif.aiagent.service.AgentService;
import ru.lordfarif.aiagent.service.AgentException;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AgentController.class)
class AgentControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AgentService service;

    @Test void returnsAgentAnswer() throws Exception {
        when(service.chat("Бюджет 30000", null)).thenReturn(new AgentChatResponse("sess_1", "Что планируете строить?"));
        mvc.perform(post("/api/v1/agent/chat").contentType("application/json")
                .content("{\"message\":\"Бюджет 30000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("sess_1"))
                .andExpect(jsonPath("$.response").value("Что планируете строить?"))
                .andExpect(jsonPath("$.images").isArray()).andExpect(jsonPath("$.images").isEmpty());
        verify(service).chat("Бюджет 30000", null);
    }

    @Test void acceptsExplicitNullSession() throws Exception {
        when(service.chat("hello", null)).thenReturn(new AgentChatResponse("sess_1", "answer"));
        mvc.perform(post("/api/v1/agent/chat").contentType("application/json")
                .content("{\"message\":\"hello\",\"sessionId\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value("sess_1"));
        verify(service).chat("hello", null);
    }

    @Test void forwardsExistingSessionAndReturnsIt() throws Exception {
        when(service.chat("continue", "sess_1")).thenReturn(new AgentChatResponse("sess_1", "next answer"));
        mvc.perform(post("/api/v1/agent/chat").contentType("application/json")
                .content("{\"message\":\"continue\",\"sessionId\":\"sess_1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value("sess_1"))
                .andExpect(jsonPath("$.response").value("next answer"));
        verify(service).chat("continue", "sess_1");
    }

    @Test void returnsGeneratedImages() throws Exception {
        when(service.chat("green", "sess_1")).thenReturn(new AgentChatResponse("sess_1", "Готово",
                List.of(new AgentImage("550e8400-e29b-41d4-a716-446655440000", "/api/v1/images/550e8400-e29b-41d4-a716-446655440000"))));
        mvc.perform(post("/api/v1/agent/chat").contentType("application/json")
                .content("{\"message\":\"green\",\"sessionId\":\"sess_1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value("sess_1"))
                .andExpect(jsonPath("$.images[0].id").value("550e8400-e29b-41d4-a716-446655440000"))
                .andExpect(jsonPath("$.images[0].url").value("/api/v1/images/550e8400-e29b-41d4-a716-446655440000"));
    }

    @Test void mapsMissingSession() throws Exception {
        when(service.chat("hello", "missing")).thenThrow(new AgentException(HttpStatus.NOT_FOUND,
                "SESSION_NOT_FOUND", "OpenAI session was not found."));
        mvc.perform(post("/api/v1/agent/chat").contentType("application/json")
                .content("{\"message\":\"hello\",\"sessionId\":\"missing\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"message\":\" \",\"sessionId\":\"sess_1\"}", "{}", "{\"message\":null}", "{\"message\":\"\"}", "{\"message\":\"   \"}", "{"})
    void rejectsInvalidRequests(String body) throws Exception {
        mvc.perform(post("/api/v1/agent/chat").contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test void mapsAgentError() throws Exception {
        when(service.chat("hello", null)).thenThrow(new AgentException(HttpStatus.GATEWAY_TIMEOUT,
                "OPENAI_TIMEOUT", "OpenAI response timed out."));
        mvc.perform(post("/api/v1/agent/chat").contentType("application/json").content("{\"message\":\"hello\"}"))
                .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value("OPENAI_TIMEOUT"));
    }

    @Test void hidesUnexpectedExceptionDetails() throws Exception {
        when(service.chat("hello", null)).thenThrow(new RuntimeException("secret stack trace"));
        mvc.perform(post("/api/v1/agent/chat").contentType("application/json").content("{\"message\":\"hello\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"code\":\"INTERNAL_ERROR\",\"error\":\"Internal server error.\"}"));
    }
}
